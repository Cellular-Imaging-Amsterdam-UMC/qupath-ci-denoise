package qupath.ext.cidenoise;

import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import qupath.lib.common.GeneralTools;

import java.util.List;

final class CIDenoiseIcons {
    private static final List<Image> ICONS = loadIcons();

    private CIDenoiseIcons() {}

    private static List<Image> loadIcons() {
        var source = new Image(CIDenoiseIcons.class.getResource("icon.png").toExternalForm());
        return List.of(render(source, 16), render(source, 32), render(source, 48), render(source, 64));
    }

    private static Image render(Image source, int size) {
        // Trim the transparent inset so the badge fills the same area as the other CI icons.
        double inset = source.getWidth() * 0.065;
        var canvas = new Canvas(size, size);
        canvas.getGraphicsContext2D().drawImage(source, inset, inset, source.getWidth() - 2 * inset,
                source.getHeight() - 2 * inset, 0, 0, size, size);
        var snapshot = new SnapshotParameters();
        snapshot.setFill(Color.TRANSPARENT);
        return canvas.snapshot(snapshot, null);
    }

    static ImageView view(int size) {
        return new ImageView(size == 16 ? ICONS.getFirst() : ICONS.get(1));
    }

    static void apply(Stage stage) {
        stage.getIcons().setAll(ICONS);
        if (GeneralTools.isWindows()) {
            Platform.runLater(() -> {
                if (stage.isShowing())
                    WindowsCaptionIcon.apply(stage.getTitle(), ICONS.getFirst(), ICONS.get(1));
            });
        }
    }
}
