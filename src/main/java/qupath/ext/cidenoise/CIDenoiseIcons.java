package qupath.ext.cidenoise;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import qupath.lib.common.GeneralTools;

import java.util.List;

final class CIDenoiseIcons {
    private static final List<Image> ICONS = List.of(load(16), load(32), load(48), load(64));

    private CIDenoiseIcons() {}

    private static Image load(int size) {
        return new Image(CIDenoiseIcons.class.getResource("icon.png").toExternalForm(), size, size, true, true);
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
