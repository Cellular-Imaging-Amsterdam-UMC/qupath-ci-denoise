package qupath.ext.cidenoise;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.util.Duration;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CIDenoiseDialog {
    private static final ButtonType RUN = new ButtonType(CIDenoiseExtension.RUN_LABEL, ButtonBar.ButtonData.OK_DONE);
    private static final Preferences PREFS = new Preferences();

    private final QuPathGUI qupath;
    private final ImageServer<BufferedImage> source;
    private final String sourceName;
    private final List<? extends PathObject> annotations;
    private final List<? extends PathObject> selected;
    private final ComboBox<RegionMode> mode = new ComboBox<>();
    private final ComboBox<PathClass> annotationClass = new ComboBox<>();
    private final ComboBox<CIDenoise.Format> format = new ComboBox<>();
    private final CheckBox projection = new CheckBox("Create max-Z projection");
    private final CheckBox open = new CheckBox("Open result after processing");
    private final CheckBox add = new CheckBox("Add results to the project");
    private final TextField output;
    private final Spinner<Integer> tile;
    private final Spinner<Integer> overlap;
    private final ComboBox<CIDenoise.Device> device = new ComboBox<>();
    private final TextField python = new TextField();
    private final List<CheckBox> channels = new ArrayList<>();

    enum RegionMode {
        FULL("Full image"), SELECTED("Selected annotations"), CLASS("Annotations by class");

        private final String label;

        RegionMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    record Request(List<CIDenoise.Region> regions, Path directory, CIDenoise.Options options,
                   boolean open, boolean add) {}

    CIDenoiseDialog(QuPathGUI qupath, ImageData<BufferedImage> data, Path directory) {
        this.qupath = qupath;
        source = data.getServer();
        String displayed = qupath.getDisplayedImageName(data);
        sourceName = CIDenoise.usableName(displayed) ? displayed : CIDenoise.sourceName(source);
        annotations = data.getHierarchy().getAnnotationObjects().stream().filter(CIDenoiseDialog::isArea).toList();
        selected = data.getHierarchy().getSelectionModel().getSelectedObjects().stream()
                .filter(CIDenoiseDialog::isArea).toList();
        mode.getItems().add(RegionMode.FULL);
        if (!selected.isEmpty())
            mode.getItems().add(RegionMode.SELECTED);
        annotationClass.getItems().setAll(annotations.stream().map(PathObject::getPathClass)
                .filter(java.util.Objects::nonNull).distinct().sorted(Comparator.comparing(PathClass::toString)).toList());
        if (!annotationClass.getItems().isEmpty()) {
            mode.getItems().add(RegionMode.CLASS);
            annotationClass.getSelectionModel().selectFirst();
        }
        mode.setValue(selected.isEmpty() ? RegionMode.FULL : RegionMode.SELECTED);
        annotationClass.visibleProperty().bind(mode.valueProperty().isEqualTo(RegionMode.CLASS));
        annotationClass.managedProperty().bind(annotationClass.visibleProperty());

        output = new TextField(directory.toString());
        format.getItems().setAll(CIDenoise.Format.values());
        format.setValue(PREFS.format.get());
        projection.setDisable(source.nZSlices() == 1);
        projection.setSelected(!projection.isDisabled() && PREFS.projection.get());
        open.setSelected(PREFS.open.get());
        open.setTooltip(new javafx.scene.control.Tooltip("Open the first result in the current QuPath viewer."));
        add.setDisable(qupath.getProject() == null);
        add.setSelected(!add.isDisabled() && PREFS.add.get());
        if (qupath.getProject() != null) {
            add.disableProperty().bind(open.selectedProperty());
            add.setTooltip(new javafx.scene.control.Tooltip("QuPath adds images opened in a project to its image list."));
            if (open.isSelected())
                add.setSelected(true);
            open.selectedProperty().addListener((obs, before, selected) -> {
                if (selected)
                    add.setSelected(true);
            });
        }
        tile = new Spinner<>(64, 512, PREFS.tile.get(), 32);
        overlap = new Spinner<>(0, tile.getValue() - 1, Math.min(PREFS.overlap.get(), tile.getValue() - 1), 16);
        tile.valueProperty().addListener((obs, oldValue, size) -> {
            var values = (javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory)overlap.getValueFactory();
            if (values.getValue() >= size)
                values.setValue(size / 4);
            values.setMax(size - 1);
        });
        tile.setPrefWidth(90);
        overlap.setPrefWidth(90);
        device.getItems().setAll(CIDenoise.Device.values());
        device.setValue(PREFS.device.get());
        python.setText(PREFS.python.get());
        python.setPromptText(CIDenoise.bundledPython() == null ? "Path to Python with CUDA-enabled PyTorch"
                : "Use the Standalone Full Python runtime");
        python.disableProperty().bind(device.valueProperty().isEqualTo(CIDenoise.Device.CPU));
        for (int c = 0; c < source.nChannels(); c++) {
            var box = new CheckBox((c + 1) + "  " + source.getChannel(c).getName());
            box.setSelected(true);
            channels.add(box);
        }
    }

    java.util.Optional<Request> show() {
        var dialog = new Dialog<Request>();
        dialog.initOwner(qupath.getStage());
        dialog.setTitle(CIDenoiseExtension.NAME);
        dialog.setResizable(true);
        var pane = dialog.getDialogPane();
        style(pane);
        pane.getButtonTypes().addAll(RUN, ButtonType.CANCEL);
        pane.setHeader(header("CI Denoise", sourceName + "  •  Noise2Noise-FMD"));

        var tiles = new TilePane(14, 8);
        tiles.setPrefColumns(3);
        tiles.getChildren().setAll(channels);
        var browse = new Button("Browse…");
        browse.setOnAction(event -> chooseOutput());
        HBox.setHgrow(output, Priority.ALWAYS);
        var folder = new HBox(8, output, browse);
        var formats = new HBox(12, format, projection);
        formats.setAlignment(Pos.CENTER_LEFT);
        projection.setTooltip(new javafx.scene.control.Tooltip("Denoise Z slices individually, then project their maximum per channel and time point."));

        var advanced = new TitledPane("Advanced settings", advanced());
        advanced.setAnimated(false);
        advanced.setExpanded(false);
        var body = new VBox(9,
                card("Image region", new HBox(8, mode, annotationClass)),
                card("Channels", tiles),
                card("Output", formats, folder, new HBox(12, open, add)),
                advanced);
        body.getStyleClass().add("denoise-body");
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(600);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("denoise-scroll");
        pane.setContent(scroll);
        var refit = new PauseTransition(Duration.millis(100));
        refit.setOnFinished(event -> fitSettingsWindow(scroll));
        advanced.expandedProperty().addListener((obs, wasExpanded, expanded) -> refit.playFromStart());
        dialog.setOnShowing(event -> {
            if (pane.getScene().getWindow() instanceof Stage stage) {
                stage.setMinWidth(530);
                stage.setMinHeight(290);
            }
        });
        dialog.setOnShown(event -> {
            if (pane.getScene().getWindow() instanceof Stage stage)
                CIDenoiseIcons.apply(stage);
            refit.playFromStart();
        });

        var run = (Button)pane.lookupButton(RUN);
        run.getStyleClass().add("denoise-primary");
        run.setMinWidth(Region.USE_PREF_SIZE);
        ButtonBar.setButtonUniformSize(run, false);
        run.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                pane.setUserData(request());
            } catch (IllegalArgumentException e) {
                Dialogs.showErrorMessage(CIDenoiseExtension.NAME, e.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == RUN ? (Request)pane.getUserData() : null);
        var result = dialog.showAndWait();
        result.ifPresent(request -> {
            PREFS.format.set(request.options().format());
            if (!projection.isDisabled())
                PREFS.projection.set(request.options().maxZ());
            PREFS.tile.set(request.options().tileSize());
            PREFS.overlap.set(request.options().overlap());
            PREFS.device.set(request.options().device());
            PREFS.python.set(python.getText().strip());
            PREFS.open.set(request.open());
            if (!add.isDisabled())
                PREFS.add.set(request.add());
        });
        return result;
    }

    private static void fitSettingsWindow(ScrollPane scroll) {
        var scene = scroll.getScene();
        if (scene == null || !(scene.getWindow() instanceof Stage stage) || !stage.isShowing() || stage.isMaximized())
            return;
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        var viewport = scroll.getViewportBounds();
        if (viewport.getWidth() <= 0 || viewport.getHeight() <= 0)
            return;
        var bounds = Screen.getScreensForRectangle(stage.getX() + stage.getWidth() / 2,
                stage.getY() + stage.getHeight() / 2, 1, 1).stream().findFirst()
                .orElse(Screen.getPrimary()).getVisualBounds();
        double chrome = stage.getHeight() - viewport.getHeight();
        double desired = Math.ceil(scroll.getContent().prefHeight(viewport.getWidth()) + chrome + 3);
        double height = Math.clamp(desired, stage.getMinHeight(), Math.max(stage.getMinHeight(), bounds.getHeight()));
        stage.setHeight(height);
        stage.setY(Math.clamp(stage.getY(), bounds.getMinY(), Math.max(bounds.getMinY(), bounds.getMaxY() - height)));
    }

    private Node advanced() {
        var grid = new GridPane();
        grid.setHgap(9);
        grid.setVgap(8);
        grid.addRow(0, new Label("Tile size (px)"), tile, new Label("Overlap (px)"), overlap);
        grid.addRow(1, new Label("Device"), device);
        grid.add(new Label("Python executable"), 0, 2);
        grid.add(python, 1, 2, 3, 1);
        GridPane.setHgrow(python, Priority.ALWAYS);
        device.setTooltip(new javafx.scene.control.Tooltip("Auto uses NVIDIA CUDA when available and falls back to CPU."));
        return grid;
    }

    private Request request() {
        var crops = new ArrayList<CIDenoise.Region>();
        if (mode.getValue() == RegionMode.FULL) {
            crops.add(new CIDenoise.Region(0, 0, source.getWidth(), source.getHeight(), sourceName));
        } else {
            var objects = mode.getValue() == RegionMode.SELECTED ? selected : annotations.stream()
                    .filter(object -> annotationClass.getValue().equals(object.getPathClass())).toList();
            String image = CIDenoise.safeName(sourceName);
            for (var object : objects) {
                String label = object.getName();
                if (label == null || label.isBlank())
                    label = object.getPathClass() == null ? "ROI" : object.getPathClass().toString();
                // Put the unique ROI index first so long source names cannot truncate it.
                String name = String.format("%03d_%s_%s", crops.size() + 1, image, label);
                crops.add(CIDenoise.Region.annotation(source, object.getROI(), name));
            }
        }
        var included = new ArrayList<Integer>();
        for (int c = 0; c < channels.size(); c++) {
            if (channels.get(c).isSelected())
                included.add(c);
        }
        if (included.isEmpty())
            throw new IllegalArgumentException("Select at least one channel to denoise.");
        if (output.getText().isBlank())
            throw new IllegalArgumentException("Choose an output folder.");
        var directory = Path.of(output.getText().strip()).toAbsolutePath().normalize();
        if (Files.exists(directory) && !Files.isDirectory(directory))
            throw new IllegalArgumentException("The output path must be a folder.");
        var pythonExecutable = device.getValue() == CIDenoise.Device.CPU || python.getText().isBlank()
                ? null : Path.of(python.getText().strip()).toAbsolutePath();
        var settings = new CIDenoise.Options(format.getValue(), projection.isSelected(),
                tile.getValue(), overlap.getValue(), included, device.getValue(), pythonExecutable);
        return new Request(List.copyOf(crops), directory, settings, open.isSelected(), add.isSelected());
    }

    private void chooseOutput() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Choose output folder");
        try {
            Path initial = Path.of(output.getText()).toAbsolutePath();
            while (initial != null && !Files.isDirectory(initial))
                initial = initial.getParent();
            if (initial != null)
                chooser.setInitialDirectory(initial.toFile());
        } catch (InvalidPathException ignored) {
            // The chooser can still open with its default starting location.
        }
        var folder = chooser.showDialog(qupath.getStage());
        if (folder != null)
            output.setText(folder.getAbsolutePath());
    }

    private static boolean isArea(PathObject object) {
        return object.isAnnotation() && object.getROI() != null
                && object.getROI().isArea() && !object.getROI().isEmpty();
    }

    static void style(DialogPane pane) {
        pane.getStyleClass().add("ci-denoise");
        pane.getStylesheets().add(CIDenoiseDialog.class.getResource("workspace.css").toExternalForm());
    }

    static HBox header(String title, String context) {
        var label = new Label(title);
        label.getStyleClass().add("denoise-title");
        var subtitle = new Label(context);
        subtitle.setWrapText(true);
        subtitle.getStyleClass().add("denoise-context");
        var box = new VBox(4, label, subtitle);
        HBox.setHgrow(box, Priority.ALWAYS);
        var header = new HBox(10, CIDenoiseIcons.view(32), box);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("denoise-header");
        return header;
    }

    private static VBox card(String title, Node... content) {
        var heading = new Label(title);
        heading.getStyleClass().add("denoise-card-heading");
        var box = new VBox(7, heading);
        box.getChildren().addAll(content);
        box.getStyleClass().add("denoise-card");
        return box;
    }

    private static final class Preferences {
        private final javafx.beans.property.ObjectProperty<CIDenoise.Format> format =
                PathPrefs.createPersistentPreference("ciDenoiseFormat", CIDenoise.Format.OME_ZARR, CIDenoise.Format.class);
        private final javafx.beans.property.BooleanProperty projection = PathPrefs.createPersistentPreference("ciDenoiseMaxZ", false);
        private final javafx.beans.property.BooleanProperty open = PathPrefs.createPersistentPreference("ciDenoiseOpenResult", true);
        private final javafx.beans.property.BooleanProperty add = PathPrefs.createPersistentPreference("ciDenoiseAddToProject", true);
        private final javafx.beans.property.IntegerProperty tile = PathPrefs.createPersistentPreference("ciDenoiseTile", CIDenoise.DEFAULT_TILE);
        private final javafx.beans.property.IntegerProperty overlap = PathPrefs.createPersistentPreference("ciDenoiseOverlap", CIDenoise.DEFAULT_OVERLAP);
        private final javafx.beans.property.ObjectProperty<CIDenoise.Device> device =
                PathPrefs.createPersistentPreference("ciDenoiseDevice", CIDenoise.Device.AUTO, CIDenoise.Device.class);
        private final javafx.beans.property.StringProperty python = PathPrefs.createPersistentPreference("ciDenoisePython", "");
    }
}
