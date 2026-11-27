package qupath.ext.cidenoise;

import java.io.IOException;

interface TilePredictor extends AutoCloseable {
    String deviceName();
    void predict(float[] pixels) throws IOException;
    @Override
    void close();
}
