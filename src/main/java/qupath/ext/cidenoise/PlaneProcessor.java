package qupath.ext.cidenoise;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Read, normalize and blend one channel/plane at a time. */
final class PlaneProcessor {
    private final ImageServer<BufferedImage> source;
    private final CIDenoise.Region crop;
    private final CIDenoise.Options options;
    private final Noise2Noise model;
    private final DenoisedImageServer output;
    private final BooleanSupplier cancelled;
    private final int tile;
    private final List<Integer> columns;
    private final List<Integer> rows;
    private final float[] ramp;
    private final float[] horizontalWeights;
    private final float[] verticalWeights;
    private final float[] patch;
    private final float[][] stripe;
    private final float[] outputRow;
    private final ByteBuffer rowBuffer;

    PlaneProcessor(ImageServer<BufferedImage> source, CIDenoise.Region crop, CIDenoise.Options options,
                   Noise2Noise model, DenoisedImageServer output, BooleanSupplier cancelled) {
        this.source = source;
        this.crop = crop;
        this.options = options;
        this.model = model;
        this.output = output;
        this.cancelled = cancelled;
        tile = options.tileSize();
        columns = starts(crop.width(), tile, options.overlap());
        rows = starts(crop.height(), tile, options.overlap());
        ramp = feather(tile, options.overlap());
        horizontalWeights = weights(columns, crop.width(), ramp);
        verticalWeights = weights(rows, crop.height(), ramp);
        patch = new float[tile * tile];
        stripe = new float[Math.min(tile, crop.height())][crop.width()];
        outputRow = new float[crop.width()];
        rowBuffer = ByteBuffer.allocate(Math.multiplyExact(crop.width(), Float.BYTES));
    }

    void process(int z, int t, int c) throws IOException {
        CIDenoise.checkCancelled(cancelled);
        if (!options.channels().isEmpty() && !options.channels().contains(c)) {
            copyPlane(z, t, c);
            return;
        }
        var range = range(z, t, c);
        if (range.minimum == range.maximum) {
            for (int y = 0; y < crop.height(); y++) {
                Arrays.fill(outputRow, range.minimum);
                writeRow(y, z, t, c);
            }
            return;
        }

        float scale = range.maximum > 0 ? range.maximum : 1;
        float low = Math.max(range.maximum, 0) * 0.5f;
        for (int band = 0; band < rows.size(); band++) {
            int y = rows.get(band);
            int height = Math.min(tile, crop.height() - y);
            for (int x : columns) {
                int width = Math.min(tile, crop.width() - x);
                var samples = read(x, y, width, height, z, t, c);
                for (int j = 0; j < tile; j++) {
                    int from = reflected(j, height) * width;
                    for (int i = 0; i < tile; i++)
                        patch[j * tile + i] = (samples[from + reflected(i, width)] - low) / scale;
                }
                model.predict(patch);
                CIDenoise.checkCancelled(cancelled);
                for (int j = 0; j < height; j++) {
                    var row = stripe[(y + j) % stripe.length];
                    for (int i = 0; i < width; i++) {
                        float value = patch[j * tile + i];
                        if (!Float.isFinite(value))
                            throw new IOException("Noise2Noise-FMD returned non-finite values.");
                        row[x + i] += value * (ramp[j] * ramp[i]);
                    }
                }
            }

            // Later tile rows cannot contribute to these scanlines.
            int end = band + 1 < rows.size() ? rows.get(band + 1) : crop.height();
            for (int j = y; j < end; j++) {
                var row = stripe[j % stripe.length];
                for (int x = 0; x < crop.width(); x++) {
                    outputRow[x] = (row[x] / (horizontalWeights[x] * verticalWeights[j])) * scale + low;
                    row[x] = 0;
                }
                writeRow(j, z, t, c);
            }
        }
    }

    private Range range(int z, int t, int c) throws IOException {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int y = 0; y < crop.height(); y += tile) {
            int height = Math.min(tile, crop.height() - y);
            for (int x = 0; x < crop.width(); x += tile) {
                var values = read(x, y, Math.min(tile, crop.width() - x), height, z, t, c);
                for (float value : values) {
                    if (!Float.isFinite(value))
                        throw new IOException("Input contains non-finite values in channel " + (c + 1)
                                + ", Z " + (z + 1) + ", T " + (t + 1) + ".");
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
        }
        return new Range(min, max);
    }

    private void copyPlane(int z, int t, int c) throws IOException {
        for (int y = 0; y < crop.height(); y += tile) {
            int height = Math.min(tile, crop.height() - y);
            for (int x = 0; x < crop.width(); x += tile) {
                int width = Math.min(tile, crop.width() - x);
                var samples = read(x, y, width, height, z, t, c);
                for (int j = 0; j < height; j++)
                    System.arraycopy(samples, j * width, stripe[j], x, width);
            }
            for (int j = 0; j < height; j++) {
                System.arraycopy(stripe[j], 0, outputRow, 0, crop.width());
                writeRow(y + j, z, t, c);
                Arrays.fill(stripe[j], 0);
            }
        }
    }

    private float[] read(int x, int y, int width, int height, int z, int t, int c) throws IOException {
        CIDenoise.checkCancelled(cancelled);
        var request = RegionRequest.createInstance(source.getPath(), 1, crop.x() + x, crop.y() + y,
                width, height, z, t);
        var image = source.readRegion(request);
        if (image == null || image.getWidth() != width || image.getHeight() != height
                || image.getRaster().getNumBands() <= c)
            throw new IOException("Could not read the requested source pixels at " + request);
        return image.getRaster().getSamples(0, 0, width, height, c, (float[])null);
    }

    private void writeRow(int y, int z, int t, int c) throws IOException {
        CIDenoise.checkCancelled(cancelled);
        output.writeRow(outputRow, rowBuffer, y, options.maxZ() ? 0 : z, t, c, options.maxZ() && z > 0);
    }

    private static int reflected(int index, int length) {
        if (index < length)
            return index;
        if (length == 1)
            return 0;
        int period = 2 * length - 2;
        int remainder = index % period;
        return Math.min(remainder, period - remainder);
    }

    private static List<Integer> starts(int length, int tile, int overlap) {
        int last = Math.max(0, length - tile);
        var positions = new ArrayList<Integer>();
        for (int position = 0; position < last; position += tile - overlap)
            positions.add(position);
        positions.add(last);
        return positions;
    }

    private static float[] feather(int tile, int overlap) {
        var weights = new float[tile];
        Arrays.fill(weights, 1);
        for (int i = 0; i < overlap; i++) {
            float start = 1f / (overlap + 1);
            float value = overlap == 1 ? start : start + (1 - start) * i / (overlap - 1);
            weights[i] *= value;
            weights[tile - i - 1] *= value;
        }
        return weights;
    }

    private static float[] weights(List<Integer> positions, int length, float[] ramp) {
        var weights = new float[length];
        for (int start : positions) {
            for (int i = 0; i < Math.min(ramp.length, length - start); i++)
                weights[start + i] += ramp[i];
        }
        return weights;
    }

    private record Range(float minimum, float maximum) {}
}
