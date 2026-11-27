package qupath.ext.cidenoise;

import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferFloat;
import java.awt.image.Raster;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Disk-backed float32 pixels shared by the OME writers. */
final class DenoisedImageServer extends AbstractTileableImageServer {
    private final Path path;
    private final ImageServerMetadata metadata;
    private final ColorModel color;
    private final FileChannel file;
    private final BooleanSupplier cancelled;

    DenoisedImageServer(Path path, ImageServer<BufferedImage> source, CIDenoise.Region crop, boolean maxZ,
                        String name, BooleanSupplier cancelled) throws IOException {
        this.path = path;
        this.cancelled = cancelled;
        var original = source.getMetadata();
        metadata = new ImageServerMetadata.Builder(original)
                .name(name).width(crop.width()).height(crop.height())
                .levelsFromDownsamples(1).preferredTileSize(512, 512)
                .sizeZ(maxZ ? 1 : source.nZSlices())
                .rgb(false).pixelType(PixelType.FLOAT32).build();
        color = ColorModelFactory.createColorModel(PixelType.FLOAT32, metadata.getChannels());
        file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private long offset(int x, int y, int z, int t, int c) {
        long plane = ((long)t * nZSlices() + z) * nChannels() + c;
        return ((plane * getHeight() + y) * getWidth() + x) * Float.BYTES;
    }

    void writeRow(float[] samples, ByteBuffer buffer, int y, int z, int t, int c, boolean combineMax)
            throws IOException {
        long position = offset(0, y, z, t, c);
        if (combineMax) {
            read(buffer, position);
            var previous = buffer.asFloatBuffer();
            for (int x = 0; x < samples.length; x++)
                samples[x] = Math.max(samples[x], previous.get());
        }
        buffer.clear();
        buffer.asFloatBuffer().put(samples);
        while (buffer.hasRemaining())
            position += file.write(buffer, position);
    }

    private void read(ByteBuffer buffer, long position) throws IOException {
        buffer.clear();
        while (buffer.hasRemaining()) {
            int count = file.read(buffer, position);
            if (count < 0)
                throw new EOFException("Denoising scratch data is incomplete.");
            position += count;
        }
        buffer.flip();
    }

    @Override
    protected BufferedImage readTile(TileRequest tile) throws IOException {
        CIDenoise.checkCancelled(cancelled);
        int w = tile.getTileWidth();
        int h = tile.getTileHeight();
        int count = nChannels();
        float[][] samples = new float[count][w * h];
        var buffer = ByteBuffer.allocate(w * Float.BYTES);
        for (int c = 0; c < count; c++) {
            for (int y = 0; y < h; y++) {
                read(buffer, offset(tile.getTileX(), tile.getTileY() + y, tile.getZ(), tile.getT(), c));
                buffer.asFloatBuffer().get(samples[c], y * w, w);
            }
        }
        var layout = new BandedSampleModel(DataBuffer.TYPE_FLOAT, w, h, count);
        var raster = Raster.createWritableRaster(layout, new DataBufferFloat(samples, w * h), null);
        return new BufferedImage(color, raster, false, null);
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return metadata;
    }

    @Override
    protected String createID() {
        return path.toString();
    }

    @Override
    public Collection<URI> getURIs() {
        return List.of();
    }

    @Override
    protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
        return null;
    }

    @Override
    public String getServerType() {
        return "CI Denoise scratch image";
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
