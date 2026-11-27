package qupath.ext.cidenoise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.images.writers.ome.zarr.PyramidalOMEZarrWriter;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Blocking denoising API for QuPath scripts and the extension dialog. */
public final class CIDenoise {
    public static final int DEFAULT_TILE = 512;
    public static final int DEFAULT_OVERLAP = 128;
    private static final Logger logger = LoggerFactory.getLogger(CIDenoise.class);

    private CIDenoise() {}

    public enum Format {
        OME_ZARR("OME-Zarr", ".ome.zarr"),
        OME_TIFF("OME-TIFF", ".ome.tif");

        private final String label;
        private final String suffix;

        Format(String label, String suffix) {
            this.label = label;
            this.suffix = suffix;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Device {
        AUTO("Auto"), CPU("CPU"), CUDA("NVIDIA CUDA");

        private final String label;

        Device(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record Options(Format format, boolean maxZ, int tileSize, int overlap, List<Integer> channels,
                          Device device, Path pythonExecutable) {
        public Options(Format format, boolean maxZ, int tileSize, int overlap, List<Integer> channels) {
            this(format, maxZ, tileSize, overlap, channels, Device.CPU, null);
        }

        public Options {
            Objects.requireNonNull(format);
            Objects.requireNonNull(device);
            channels = List.copyOf(channels);
            if (tileSize < 64 || tileSize > 512 || tileSize % 32 != 0 || overlap < 0 || overlap >= tileSize)
                throw new IllegalArgumentException("Tile size must be a multiple of 32 from 64 to 512, with overlap smaller than the tile.");
            if (channels.stream().anyMatch(channel -> channel < 0))
                throw new IllegalArgumentException("Channel indices must be zero-based.");
        }

        /** An empty channel list selects all channels. */
        public static Options defaults() {
            return new Options(Format.OME_ZARR, false, DEFAULT_TILE, DEFAULT_OVERLAP, List.of());
        }
    }

    public record Region(int x, int y, int width, int height, String name) {
        public Region {
            if (x < 0 || y < 0 || width <= 0 || height <= 0)
                throw new IllegalArgumentException("The selected region has no area inside the image.");
        }

        public static Region fullImage(ImageServer<?> source) {
            return new Region(0, 0, source.getWidth(), source.getHeight(), sourceName(source));
        }

        /** Crop to the annotation's XY bounds and retain the source's full Z and T. */
        public static Region annotation(ImageServer<?> source, ROI roi, String name) {
            if (!roi.isArea() || roi.isEmpty())
                throw new IllegalArgumentException("Select an annotation with an area.");
            int x = Math.max(0, (int)Math.floor(roi.getBoundsX()));
            int y = Math.max(0, (int)Math.floor(roi.getBoundsY()));
            int right = Math.min(source.getWidth(), (int)Math.ceil(roi.getBoundsX() + roi.getBoundsWidth()));
            int bottom = Math.min(source.getHeight(), (int)Math.ceil(roi.getBoundsY() + roi.getBoundsHeight()));
            if (right <= x || bottom <= y)
                throw new IllegalArgumentException("An annotation has no area inside the image.");
            return new Region(x, y, right - x, bottom - y, name);
        }
    }

    @FunctionalInterface
    interface Progress {
        void update(long done, long total, String message);
    }

    public static Path run(ImageServer<BufferedImage> source, Region region, Path directory, Options options)
            throws Exception {
        return run(source, List.of(region), directory, options, () -> false, (done, total, message) -> {}).getFirst();
    }

    static List<Path> run(ImageServer<BufferedImage> source, List<Region> regions, Path directory, Options options,
                          BooleanSupplier cancelled, Progress progress) throws Exception {
        for (Region region : regions) {
            if ((long)region.x + region.width > source.getWidth() || (long)region.y + region.height > source.getHeight())
                throw new IllegalArgumentException("The selected region extends outside the source image.");
        }
        for (int channel : options.channels) {
            if (channel >= source.nChannels())
                throw new IllegalArgumentException("Channel " + (channel + 1) + " is outside the source image.");
        }
        long planes = (long)source.nTimepoints() * source.nZSlices() * source.nChannels();
        long total = (planes + 1) * regions.size();
        Files.createDirectories(directory);
        var results = new java.util.ArrayList<Path>();
        progress.update(0, total, "Loading Noise2Noise-FMD…");
        try (var model = openModel(options.tileSize(), options.device(), options.pythonExecutable(), cancelled)) {
            logger.info("Noise2Noise-FMD inference on {}", model.deviceName());
            for (int index = 0; index < regions.size(); index++) {
                checkCancelled(cancelled);
                var region = regions.get(index);
                String basename = safeName(region.name) + (options.maxZ ? "_denoise_maxZ" : "_denoise");
                progress.update(index * (planes + 1), total, "Processing " + region.name);
                var scratch = Files.createTempDirectory(directory, ".cidenoise-");
                try {
                    var staged = scratch.resolve(basename + options.format.suffix);
                    long initial = index * (planes + 1);
                    try (var pixels = new DenoisedImageServer(scratch.resolve("pixels"), source, region,
                            options.maxZ, basename, cancelled)) {
                        var processor = new PlaneProcessor(source, region, options, model, pixels, cancelled);
                        long done = initial;
                        for (int t = 0; t < source.nTimepoints(); t++) {
                            for (int z = 0; z < source.nZSlices(); z++) {
                                for (int c = 0; c < source.nChannels(); c++) {
                                    String status = String.format("%s (%d/%d)  •  T %d/%d  •  Z %d/%d  •  Channel %d/%d  •  %s",
                                            region.name, index + 1, regions.size(),
                                            t + 1, source.nTimepoints(), z + 1, source.nZSlices(), c + 1, source.nChannels(),
                                            model.deviceName());
                                    progress.update(done++, total, status);
                                    processor.process(z, t, c);
                                }
                            }
                        }
                        progress.update(done, total, "Writing " + basename + options.format.suffix);
                        write(pixels, staged, options.format, cancelled);
                    }
                    checkCancelled(cancelled);
                    var output = publish(staged, directory, basename, options.format.suffix, cancelled);
                    results.add(output);
                    logger.info("Wrote {}", output);
                    progress.update(initial + planes + 1, total, "Saved " + output.getFileName());
                } finally {
                    deleteTemporaryFiles(scratch);
                }
            }
        }
        return List.copyOf(results);
    }

    public static void validateModel() throws IOException {
        validateModel(Device.CPU, null);
    }

    public static String validateModel(Device device, Path pythonExecutable) throws IOException {
        try (var model = openModel(64, device, pythonExecutable, () -> false)) {
            var pixels = new float[64 * 64];
            Arrays.fill(pixels, -0.25f);
            model.predict(pixels);
            for (float pixel : pixels) {
                if (!Float.isFinite(pixel))
                    throw new IOException("Noise2Noise-FMD returned non-finite values.");
            }
            return model.deviceName();
        }
    }

    private static TilePredictor openModel(int tile, Device device, Path python, BooleanSupplier cancelled)
            throws IOException {
        if (device == Device.CPU)
            return new Noise2Noise(tile);
        if (python == null)
            python = bundledPython();
        if (python == null && device == Device.CUDA)
            throw new IOException("Select a Python executable with CUDA-enabled PyTorch in Advanced settings.");
        if (python != null) {
            try {
                return new CudaNoise2Noise(tile, python, cancelled);
            } catch (IOException e) {
                if (device == Device.CUDA)
                    throw e;
                logger.info("CUDA could not start. Using CPU for this run. {}", e.getMessage());
            }
        }
        return new Noise2Noise(tile);
    }

    static Path bundledPython() {
        String value = System.getenv("QUPATH_FULL_PYTHON");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Denoising cancelled.");
    }

    private static void write(DenoisedImageServer pixels, Path target, Format format, BooleanSupplier cancelled)
            throws Exception {
        checkCancelled(cancelled);
        // QuPath's writers publish channel metadata, physical pixel sizes and T/Z dimensions.
        double[] scales = java.util.stream.IntStream.range(0, 16)
                .mapToDouble(level -> Math.scalb(1.0, level))
                .takeWhile(scale -> scale == 1 || Math.min(pixels.getWidth(), pixels.getHeight()) / (scale / 2) > 512)
                .toArray();
        if (format == Format.OME_ZARR) {
            new PyramidalOMEZarrWriter.Builder(pixels)
                    .tileSize(512).downsamples(scales).parallelize(2)
                    .onProgress(progress -> checkCancelled(cancelled))
                    .build(target.toString()).writeImage();
        } else {
            var pyramid = scales.length == 1 ? pixels : ImageServers.pyramidalizeTiled(pixels, 512, 512, scales);
            new OMEPyramidWriter.Builder(pyramid)
                    .channelsPlanar().allZSlices().allTimePoints()
                    .losslessCompression().tileSize(512).downsamples(scales)
                    .build().writePyramid(target.toString());
        }
    }

    private static Path publish(Path staged, Path directory, String stem, String suffix, BooleanSupplier cancelled)
            throws IOException {
        for (int number = 1; ; number++) {
            checkCancelled(cancelled);
            var candidate = directory.resolve(stem + (number == 1 ? "" : "_" + number) + suffix);
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))
                continue;
            try {
                return moveCompletedExport(staged, candidate, cancelled);
            } catch (IOException e) {
                // Another run can publish the same name after the existence check.
                if (!(e instanceof FileAlreadyExistsException) && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))
                    throw e;
            }
        }
    }

    private static Path moveCompletedExport(Path staged, Path candidate, BooleanSupplier cancelled) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        for (int delay = 50; ; delay = Math.min(400, delay * 2)) {
            checkCancelled(cancelled);
            try {
                return Files.move(staged, candidate);
            } catch (AccessDeniedException e) {
                // Windows can temporarily lock a just-written OME-Zarr directory while its files are scanned.
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) || System.nanoTime() >= deadline)
                    throw e;
                if (delay == 50)
                    logger.info("Waiting for temporary file access before publishing {}", candidate.getFileName());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Denoising cancelled.");
                }
            }
        }
    }

    static String safeName(String input) {
        if (input == null || input.isBlank())
            return "Image";
        String name = input.replaceFirst("(?i)\\.(?:ome\\.(?:zarr|tiff?)|tiff?|zarr|czi|nd2|lif|vsi|svs|png|jpg|jpeg)$", "")
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip().replaceFirst("[. ]+$", "");
        return name.isBlank() ? "Image" : name.substring(0, Math.min(100, name.length()));
    }

    static boolean usableName(String name) {
        return name != null && !name.isBlank() && !name.equalsIgnoreCase("Image null") && !name.equalsIgnoreCase("null");
    }

    static String sourceName(ImageServer<?> source) {
        String name = ServerTools.getDisplayableImageName(source);
        if (usableName(name))
            return name;
        for (var uri : source.getURIs()) {
            String path = uri.getPath();
            if (path != null && !path.isBlank()) {
                name = path.replaceFirst("/+$", "");
                name = name.substring(name.lastIndexOf('/') + 1);
                if (usableName(name))
                    return name;
            }
        }
        return "Image";
    }

    static void deleteTemporaryFiles(Path root) {
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("Could not clean temporary denoising files under {}", root, e);
        }
    }
}
