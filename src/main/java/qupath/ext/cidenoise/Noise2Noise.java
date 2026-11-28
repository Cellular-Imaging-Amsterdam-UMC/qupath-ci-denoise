package qupath.ext.cidenoise;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_dnn.Net;

import java.io.IOException;
import java.nio.FloatBuffer;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_dnn.DNN_BACKEND_OPENCV;
import static org.bytedeco.opencv.global.opencv_dnn.DNN_TARGET_CPU;
import static org.bytedeco.opencv.global.opencv_dnn.readNetFromONNX;

/** One reusable CPU network and input blob per processing run. */
final class Noise2Noise implements TilePredictor {
    private final Net net;
    private final Mat input;
    private final int tile;

    Noise2Noise(int tile) throws IOException {
        this.tile = tile;
        try (var stream = Noise2Noise.class.getResourceAsStream("noise2noise-fmd.onnx")) {
            if (stream == null)
                throw new IOException("The bundled Noise2Noise-FMD model is missing.");
            net = readNetFromONNX(stream.readAllBytes());
        }
        try {
            if (net.empty())
                throw new IOException("Could not load the Noise2Noise-FMD model.");
            net.setPreferableBackend(DNN_BACKEND_OPENCV);
            net.setPreferableTarget(DNN_TARGET_CPU);
            input = new Mat(new int[]{1, 1, tile, tile}, CV_32F);
        } catch (IOException | RuntimeException | Error e) {
            net.close();
            throw e;
        }
    }

    @Override
    public String deviceName() {
        return "CPU";
    }

    @Override
    public void predict(float[] pixels, int channel) throws IOException {
        input.<FloatBuffer>createBuffer().put(pixels);
        net.setInput(input);
        try (var output = net.forward()) {
            if (output.dims() != 4 || output.size(0) != 1 || output.size(1) != 1
                    || output.size(2) != tile || output.size(3) != tile || output.depth() != CV_32F)
                throw new IOException("Noise2Noise-FMD returned an unexpected tensor shape.");
            output.<FloatBuffer>createBuffer().get(pixels);
        }
    }

    @Override
    public void close() {
        input.close();
        net.close();
    }
}
