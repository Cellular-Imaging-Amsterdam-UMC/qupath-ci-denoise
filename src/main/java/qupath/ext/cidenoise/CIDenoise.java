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
    private static final int EXPORT_TILE = 512;
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
        var plans = regions.stream().map(region -> plan(source, region, options)).toList();
        var work = new RunProgress(plans.stream().mapToLong(RegionPlan::work).sum(), progress);
        Files.createDirectories(directory);
        var results = new java.util.ArrayList<Path>();
        work.advance(0, "Loading Noise2Noise-FMD…");
        try (var model = openModel(options.tileSize(), options.device(), options.pythonExecutable(), cancelled)) {
            logger.info("Noise2Noise-FMD inference on {}", model.deviceName());
            for (int index = 0; index < regions.size(); index++) {
                checkCancelled(cancelled);
                var region = regions.get(index);
                var plan = plans.get(index);
                String basename = safeName(region.name) + (options.maxZ ? "_denoise_maxZ" : "_denoise");
                var scratch = Files.createTempDirectory(directory, ".cidenoise-");
                try {
                    Path output;
                    try (var pixels = new DenoisedImageServer(scratch.resolve("pixels"), source, region,
                            options.maxZ, basename, cancelled)) {
                        var processor = new PlaneProcessor(source, region, options, model, pixels, cancelled);
                        for (int t = 0; t < source.nTimepoints(); t++) {
                            for (int z = 0; z < source.nZSlices(); z++) {
                                for (int c = 0; c < source.nChannels(); c++) {
                                    String plane = String.format("%s (%d/%d)  •  T %d/%d  •  Z %d/%d  •  Channel %d/%d  •  %s%n",
                                            region.name, index + 1, regions.size(),
                                            t + 1, source.nTimepoints(), z + 1, source.nZSlices(), c + 1, source.nChannels(),
                                            model.deviceName());
                                    processor.process(z, t, c, (phase, count, total, advance) -> work.advance(advance,
                                            plane + phase + (total == 0 ? "" : " tile " + count + " / " + total)));
                                }
                            }
                        }
                        output = publish(pixels, scratch, directory, basename, options.format(), cancelled, plan, work);
                    }
                    results.add(output);
                    logger.info("Wrote {}", output);
                    work.advance(1, "Saved " + output.getFileName());
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

    private record RegionPlan(double[] scales, long exportTiles, long work) {}

    private static RegionPlan plan(ImageServer<?> source, Region region, Options options) {
        double[] scales = java.util.stream.IntStream.range(0, 16)
                .mapToDouble(level -> Math.scalb(1.0, level))
                .takeWhile(scale -> scale == 1 || Math.min(region.width(), region.height()) / (scale / 2) > EXPORT_TILE)
                .toArray();
        long selectedChannels = java.util.stream.IntStream.range(0, source.nChannels())
                .filter(channel -> PlaneProcessor.denoisesChannel(options, channel)).count();
        long processing = (PlaneProcessor.scanTiles(region, options.tileSize()) * source.nChannels()
                + PlaneProcessor.modelTiles(region, options.tileSize(), options.overlap()) * selectedChannels)
                * source.nTimepoints() * source.nZSlices();
        long exportTiles = Arrays.stream(scales)
                .mapToLong(scale -> (long) Math.ceilDiv((int) (region.width() / scale), EXPORT_TILE)
                        * Math.ceilDiv((int) (region.height() / scale), EXPORT_TILE))
                .sum() * source.nTimepoints() * (options.maxZ() ? 1 : source.nZSlices());
        if (options.format() == Format.OME_TIFF)
            exportTiles *= source.nChannels();
        return new RegionPlan(scales, exportTiles, processing + exportTiles + 1);
    }

    private static final class RunProgress {
        private final long total;
        private final Progress callback;
        private long done;

        private RunProgress(long total, Progress callback) {
            this.total = total;
            this.callback = callback;
        }

        synchronized void advance(long amount, String message) {
            report(done + amount, message);
        }

        synchronized void report(long completed, String message) {
            if (completed >= done) {
                done = completed;
                callback.update(done, total, message);
            }
        }

        synchronized long completed() {
            return done;
        }
    }

    private static final class ExportProgress {
        private final long start;
        private final long total;
        private final String heading;
        private final RunProgress work;
        private long completed;
        private boolean finished;

        private ExportProgress(long start, long total, String file, RunProgress work) {
            this.start = start;
            this.total = total;
            this.heading = "Writing " + file;
            this.work = work;
            work.report(start, heading + "\nTile 0 / " + total);
        }

        synchronized void tile() {
            if (!finished && completed < total) {
                completed++;
                work.report(start + completed, heading + "\nTile " + completed + " / " + total);
            }
        }

        synchronized void finish() {
            finished = true;
            work.report(start + total, heading + "\nTile " + total + " / " + total + " · Finalizing…");
        }
    }

    private static void write(DenoisedImageServer pixels, Path target, Format format, BooleanSupplier cancelled,
                              double[] scales, ExportProgress progress) throws Exception {
        checkCancelled(cancelled);
        // QuPath's writers publish channel metadata, physical pixel sizes and T/Z dimensions.
        if (format == Format.OME_ZARR) {
            new PyramidalOMEZarrWriter.Builder(pixels)
                    .tileSize(EXPORT_TILE).downsamples(scales).parallelize(2)
                    .onProgress(fraction -> progress.tile())
                    .build(target.toString()).writeImage();
        } else {
            var pyramid = scales.length == 1 ? pixels : ImageServers.pyramidalizeTiled(pixels, EXPORT_TILE, EXPORT_TILE, scales);
            var counted = new TiffProgressServer(pyramid, progress::tile);
            new OMEPyramidWriter.Builder(counted)
                    .channelsPlanar().allZSlices().allTimePoints()
                    .losslessCompression().tileSize(EXPORT_TILE).downsamples(scales)
                    .build().writeSeries(target.toString());
        }
        checkCancelled(cancelled);
        progress.finish();
    }

    private static Path publish(DenoisedImageServer pixels, Path scratch, Path directory, String stem, Format format,
                                BooleanSupplier cancelled, RegionPlan plan, RunProgress progress) throws Exception {
        long initial = progress.completed();
        for (int number = 1; ; number++) {
            checkCancelled(cancelled);
            String filename = stem + (number == 1 ? "" : "_" + number) + format.suffix;
            var candidate = directory.resolve(filename);
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))
                continue;
            // The OME-TIFF XML contains a file reference. Stage under the exact name that will be published.
            var staged = scratch.resolve(filename);
            write(pixels, staged, format, cancelled, plan.scales(),
                    new ExportProgress(initial, plan.exportTiles(), filename, progress));
            try {
                return moveCompletedExport(staged, candidate, cancelled);
            } catch (IOException e) {
                // Another run can publish the same name after the existence check.
                if (!(e instanceof FileAlreadyExistsException) && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))
                    throw e;
                deleteTemporaryFiles(staged);
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
