package qupath.ext.cidenoise;

import qupath.lib.io.GsonTools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Runs a persistent PyTorch worker and exchanges one binary tile per request. */
final class PythonTilePredictor implements TilePredictor {
    private static final Duration START_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration TILE_TIMEOUT = Duration.ofMinutes(2);
    private static final String NOISE_SCRIPT = "cuda_worker.py";
    private static final String NOISE_WEIGHTS = "noise2noise-fmd.pt";
    private static final String FLUORESFM_SCRIPT = "fluoresfm_worker.py";

    private final Path directory;
    private final Path stderr;
    private final BooleanSupplier cancelled;
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("cidenoise-cuda-io").factory());
    private final ByteBuffer buffer;
    private final ByteBuffer channelHeader;
    private final String modelName;
    private Process process;
    private String deviceName;

    static PythonTilePredictor noise2Noise(int tile, Path python, BooleanSupplier cancelled) throws IOException {
        return new PythonTilePredictor(tile, python, null, List.of(), cancelled);
    }

    static PythonTilePredictor fluoResFM(int tile, Path python, Path weights, List<String> prompts,
                                        BooleanSupplier cancelled) throws IOException {
        return new PythonTilePredictor(tile, python, weights, prompts, cancelled);
    }

    private PythonTilePredictor(int tile, Path python, Path weights, List<String> prompts,
                                BooleanSupplier cancelled) throws IOException {
        this.cancelled = cancelled;
        boolean fluo = weights != null;
        modelName = fluo ? "FluoResFM" : "Noise2Noise-FMD";
        buffer = ByteBuffer.allocate(tile * tile * Float.BYTES).order(ByteOrder.nativeOrder());
        channelHeader = fluo ? ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN) : null;
        directory = Files.createTempDirectory("cidenoise-cuda-");
        stderr = directory.resolve("stderr.log");
        try {
            var command = new ArrayList<>(List.of(python.toAbsolutePath().toString(), "-I", "-u"));
            if (fluo) {
                copyResource(FLUORESFM_SCRIPT);
                copyResource("fluoresfm_unet.py");
                copyResource("fluoresfm_attention.py");
                Path promptPath = directory.resolve("prompts.json");
                Files.writeString(promptPath, GsonTools.getInstance().toJson(prompts), StandardCharsets.UTF_8);
                command.addAll(List.of(directory.resolve(FLUORESFM_SCRIPT).toString(),
                        weights.toAbsolutePath().toString(), Integer.toString(tile), promptPath.toString()));
            } else {
                copyResource(NOISE_SCRIPT);
                copyResource(NOISE_WEIGHTS);
                command.addAll(List.of(directory.resolve(NOISE_SCRIPT).toString(),
                        directory.resolve(NOISE_WEIGHTS).toString(), Integer.toString(tile)));
            }
            var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectError(stderr.toFile());
            // Conda on Windows keeps additional native dependencies in Library/bin.
            var bin = python.toAbsolutePath().getParent().resolve("Library").resolve("bin");
            if (Files.isDirectory(bin)) {
                var env = builder.environment();
                var pathKey = env.keySet().stream().filter("PATH"::equalsIgnoreCase).findFirst().orElse("PATH");
                env.put(pathKey, bin + File.pathSeparator + env.getOrDefault(pathKey, ""));
            }
            process = builder.start();
            deviceName = "CUDA (" + await(this::readDevice, fluo ? Duration.ofMinutes(5) : START_TIMEOUT) + ")";
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private void copyResource(String name) throws IOException {
        try (var input = PythonTilePredictor.class.getResourceAsStream(name)) {
            if (input == null)
                throw new IOException("Missing bundled model resource " + name);
            Files.copy(input, directory.resolve(name));
        }
    }

    private String readDevice() throws IOException {
        var stream = process.getInputStream();
        var line = new ByteArrayOutputStream();
        for (int b; (b = stream.read()) >= 0 && line.size() < 512; ) {
            if (b == '\n' && line.size() > 0)
                return line.toString(StandardCharsets.UTF_8).strip();
            line.write(b);
        }
        throw new IOException("The CUDA worker did not initialize.");
    }

    @Override
    public String deviceName() {
        return deviceName;
    }

    @Override
    public void predict(float[] pixels, int channel) throws IOException {
        buffer.asFloatBuffer().put(pixels);
        await(() -> {
            var input = process.getOutputStream();
            if (channelHeader != null) {
                channelHeader.putInt(0, channel);
                input.write(channelHeader.array());
            }
            input.write(buffer.array());
            input.flush();
            var output = process.getInputStream();
            if (output.readNBytes(buffer.array(), 0, buffer.capacity()) != buffer.capacity())
                throw new IOException("The CUDA worker returned an incomplete prediction.");
            return null;
        }, TILE_TIMEOUT);
        buffer.asFloatBuffer().get(pixels);
    }

    private <T> T await(Callable<T> operation, Duration timeout) throws IOException {
        var pending = executor.submit(operation);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                CIDenoise.checkCancelled(cancelled);
                if (System.nanoTime() >= deadline)
                    throw new IOException(modelName + " CUDA worker timed out.");
                try {
                    return pending.get(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Between reads, give QuPath a chance to cancel the worker.
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Denoising cancelled.");
        } catch (ExecutionException e) {
            try (var error = Files.newInputStream(stderr)) {
                String details = new String(error.readNBytes(8192), StandardCharsets.UTF_8).strip();
                if (!details.isEmpty())
                    throw new IOException(details, e.getCause());
            }
            throw new IOException(modelName + " CUDA failed: " + e.getCause().getMessage(), e.getCause());
        } finally {
            pending.cancel(true);
        }
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executor.shutdownNow();
        CIDenoise.deleteTemporaryFiles(directory);
    }
}
