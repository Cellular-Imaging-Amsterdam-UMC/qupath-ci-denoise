package qupath.ext.cidenoise;

import qupath.lib.images.servers.AbstractImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;

/** Reports the preceding tile when QuPath's sequential TIFF writer requests the next one. */
final class TiffProgressServer extends AbstractImageServer<BufferedImage> {
    private final ImageServer<BufferedImage> source;
    private final Runnable tileComplete;
    private boolean requested;

    TiffProgressServer(ImageServer<BufferedImage> source, Runnable tileComplete) {
        super(BufferedImage.class);
        this.source = source;
        this.tileComplete = tileComplete;
    }

    @Override
    public BufferedImage readRegion(RegionRequest request) throws IOException {
        if (requested)
            tileComplete.run();
        requested = true;
        return source.readRegion(request.updatePath(source.getPath()));
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return source.getMetadata();
    }

    @Override
    public Collection<URI> getURIs() {
        return source.getURIs();
    }

    @Override
    public String getServerType() {
        return source.getServerType();
    }

    @Override
    protected String createID() {
        return source.getPath();
    }

    @Override
    protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
        return null;
    }
}
