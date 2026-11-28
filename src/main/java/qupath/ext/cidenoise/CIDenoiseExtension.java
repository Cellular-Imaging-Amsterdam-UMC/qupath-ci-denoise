package qupath.ext.cidenoise;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.Commands;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CIDenoiseExtension implements QuPathExtension {
    static final String NAME = "CI Denoise";
    static final String RUN_LABEL = "Run Denoising";
    private static final String DOCS = "https://github.com/Cellular-Imaging-Amsterdam-UMC/qupath-ci-denoise#readme";
    private static final Logger logger = LoggerFactory.getLogger(CIDenoiseExtension.class);
    private final SimpleBooleanProperty busy = new SimpleBooleanProperty();
    private boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed)
            return;
        installed = true;
        var run = new MenuItem(RUN_LABEL);
        run.disableProperty().bind(busy.or(qupath.imageDataProperty().isNull()));
        run.setOnAction(event -> {
            var data = qupath.getImageData();
            if (data == null)
                return;
            var project = qupath.getProject();
            var dialog = new CIDenoiseDialog(qupath, data, defaultDirectory(data.getServer(), project));
            dialog.show().ifPresent(request -> run(qupath, project, data, request));
        });
        var validate = new MenuItem("Validate Noise2Noise model");
        validate.disableProperty().bind(busy);
        validate.setOnAction(event -> validate(qupath));
        var script = new MenuItem("Open example Groovy script");
        script.setOnAction(event -> showScript(qupath));
        var docs = new MenuItem("Open documentation");
        docs.setOnAction(event -> QuPathGUI.openInBrowser(DOCS));
        var menu = qupath.getMenu("Extensions>" + NAME, true);
        menu.setGraphic(CIDenoiseIcons.view(16));
        menu.getItems().addAll(run, validate, new SeparatorMenuItem(), script, docs);
    }

    private void run(QuPathGUI qupath, Project<BufferedImage> project,
                     ImageData<BufferedImage> data, CIDenoiseDialog.Request request) {
        var sourceType = data.getImageType();
        var cancelled = new AtomicBoolean();
        var task = new Task<List<Path>>() {
            private long lastUpdate;

            @Override
            protected List<Path> call() throws Exception {
                return CIDenoise.run(data.getServer(), request.regions(), request.directory(), request.options(),
                        cancelled::get, (done, total, message) -> {
                            long now = System.nanoTime();
                            if (done == 0 || done == total || now - lastUpdate >= TimeUnit.MILLISECONDS.toNanos(25)) {
                                lastUpdate = now;
                                updateMessage(message);
                                updateProgress(done, total);
                            }
                        });
            }
        };

        var progress = new Dialog<ButtonType>();
        progress.setTitle(NAME);
        progress.initOwner(qupath.getStage());
        progress.initModality(javafx.stage.Modality.NONE);
        var pane = progress.getDialogPane();
        CIDenoiseDialog.style(pane);
        String context = request.options().strength() == 0 ? "Copying original pixels"
                : "Running " + request.options().model() + "  •  " + request.options().strength() + "% strength";
        pane.setHeader(CIDenoiseDialog.header("CI Denoise", context));
        pane.getButtonTypes().add(ButtonType.CANCEL);
        var status = new Label();
        status.setWrapText(true);
        status.setMinHeight(56);
        status.textProperty().bind(task.messageProperty());
        var bar = new ProgressBar();
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.progressProperty().bind(task.progressProperty());
        var body = new VBox(8, status, bar);
        body.getStyleClass().add("denoise-progress");
        body.setPrefWidth(560);
        pane.setContent(body);
        progress.setOnShown(event -> {
            if (pane.getScene().getWindow() instanceof Stage stage)
                CIDenoiseIcons.apply(stage);
        });

        Runnable requestCancel = () -> {
            cancelled.set(true);
            pane.lookupButton(ButtonType.CANCEL).setDisable(true);
            status.textProperty().unbind();
            status.setText("Cancelling…");
        };
        pane.lookupButton(ButtonType.CANCEL).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            requestCancel.run();
        });
        progress.setOnCloseRequest(event -> {
            event.consume();
            requestCancel.run();
        });

        Runnable finish = () -> {
            progress.setOnCloseRequest(null);
            progress.close();
        };
        task.setOnSucceeded(event -> {
            finish.run();
            try {
                presentResults(qupath, project, sourceType, request.directory(), task.getValue());
            } finally {
                busy.set(false);
            }
        });
        task.setOnFailed(event -> {
            finish.run();
            busy.set(false);
            var error = task.getException();
            if (cancelled.get() || error instanceof java.util.concurrent.CancellationException) {
                Dialogs.showInfoNotification(NAME, "Denoising cancelled. Completed exports are in " + request.directory());
            } else {
                logger.error("CI Denoise failed", error);
                Dialogs.showErrorMessage(NAME, error);
            }
        });
        busy.set(true);
        progress.show();
        qupath.getThreadPoolManager().getSingleThreadExecutor(this).submit(task);
    }

    private void presentResults(QuPathGUI qupath, Project<BufferedImage> project,
                                ImageData.ImageType sourceType, Path directory, List<Path> outputs) {
        var saved = "Saved " + outputs.size() + (outputs.size() == 1 ? " image to " : " images to ") + directory;
        if (qupath.getProject() != project) {
            Dialogs.showWarningNotification(NAME, saved + ". The active project changed during processing.");
            return;
        }

        var warnings = new ArrayList<String>();
        ProjectImageEntry<BufferedImage> first = null;
        if (project != null) {
            try {
                first = addToProject(project, outputs, sourceType);
                qupath.refreshProject();
            } catch (Exception e) {
                logger.warn("Could not add CI Denoise results to the project", e);
                warnings.add("Could not add the exports to the project.");
            }
        }
        try {
            if (first != null) {
                if (!qupath.openImageEntry(first))
                    warnings.add("Opening the result was cancelled.");
            } else if (project == null) {
                var viewer = qupath.getViewer();
                if (viewer == null) {
                    warnings.add("No active viewer to open the result.");
                } else {
                    var current = viewer.getImageData();
                    if (current == null || !current.isChanged() || Commands.promptToSaveImageData(qupath, current, true)) {
                        var result = openOutput(outputs.getFirst(), sourceType);
                        try {
                            viewer.setImageData(result);
                        } catch (Exception e) {
                            try {
                                result.getServer().close();
                            } catch (Exception closeError) {
                                e.addSuppressed(closeError);
                            }
                            throw e;
                        }
                    } else {
                        warnings.add("Opening the result was cancelled.");
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not open the CI Denoise result", e);
            warnings.add("Could not open the first export in the viewer.");
        }
        if (warnings.isEmpty())
            Dialogs.showInfoNotification(NAME, saved);
        else
            Dialogs.showWarningNotification(NAME, saved + ". " + String.join(" ", warnings));
    }

    private static ImageData<BufferedImage> openOutput(Path output, ImageData.ImageType sourceType)
            throws IOException {
        var server = ImageServers.buildServer(output.toUri());
        ServerTools.setImageName(server, output.getFileName().toString());
        // QuPath only supports brightfield stain models for RGB images.
        var outputType = switch (sourceType) {
            case UNSET -> ImageData.ImageType.FLUORESCENCE;
            case BRIGHTFIELD_H_DAB, BRIGHTFIELD_H_E, BRIGHTFIELD_OTHER ->
                    server.isRGB() ? sourceType : ImageData.ImageType.OTHER;
            default -> sourceType;
        };
        var data = new ImageData<>(server, outputType);
        data.setChanged(false);
        return data;
    }

    private static ProjectImageEntry<BufferedImage> addToProject(Project<BufferedImage> project, List<Path> outputs,
                                                                ImageData.ImageType sourceType) throws Exception {
        var newEntries = new java.util.ArrayList<ProjectImageEntry<BufferedImage>>();
        try {
            for (Path output : outputs) {
                var data = openOutput(output, sourceType);
                try (var server = data.getServer()) {
                    var entry = project.addImage(server.getBuilder());
                    newEntries.add(entry);
                    entry.setImageName(output.getFileName().toString());
                    entry.saveImageData(data);
                }
            }
            project.syncChanges();
            return newEntries.getFirst();
        } catch (Exception e) {
            project.removeAllImages(newEntries, false);
            throw e;
        }
    }

    private void validate(QuPathGUI qupath) {
        var task = new Task<String>() {
            @Override
            protected String call() throws Exception {
                return CIDenoise.validateModel(CIDenoise.Device.AUTO, null);
            }
        };
        task.setOnSucceeded(event -> {
            busy.set(false);
            Dialogs.showInfoNotification(NAME, "Noise2Noise-FMD passed on " + task.getValue() + ".");
        });
        task.setOnFailed(event -> {
            busy.set(false);
            logger.error("Noise2Noise-FMD validation failed", task.getException());
            Dialogs.showErrorMessage(NAME, task.getException());
        });
        busy.set(true);
        qupath.getThreadPoolManager().getSingleThreadExecutor(this).submit(task);
    }

    private static Path defaultDirectory(ImageServer<BufferedImage> source, Project<BufferedImage> project) {
        var base = project == null ? null : Projects.getBaseDirectory(project);
        if (base != null)
            return base.toPath().resolve("cidenoise");
        for (var uri : source.getURIs()) {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                try {
                    var parent = Path.of(uri).getParent();
                    if (parent != null)
                        return parent.resolve("cidenoise");
                } catch (IllegalArgumentException ignored) {
                    // Some image servers expose file URIs with extra series information.
                }
            }
        }
        return Path.of(System.getProperty("user.home"), "cidenoise");
    }

    private static void showScript(QuPathGUI qupath) {
        var editor = qupath.getScriptEditor();
        if (editor == null)
            return;
        editor.showScript("CI Denoise example.groovy", """
                import qupath.ext.cidenoise.CIDenoise
                import java.nio.file.Path

                def source = getCurrentServer()
                def region = CIDenoise.Region.fullImage(source)
                // For a selected area annotation:
                // region = CIDenoise.Region.annotation(source, getSelectedROI(), 'Selected annotation')

                // An empty list denoises all channels. Use [0] for the first channel only.
                // Set maxZ to true to project each restored Z stack after denoising.
                def maxZ = false
                def options = new CIDenoise.Options(CIDenoise.Format.OME_ZARR, maxZ, 512, 128, [],
                        CIDenoise.Device.AUTO, null)
                def base = PROJECT_BASE_DIR ?: System.getProperty('user.home')
                def output = CIDenoise.run(source, region, Path.of(base, 'cidenoise'), options)
                println output
                """);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Restore fluorescence microscopy images using Noise2Noise-FMD or FluoResFM.";
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("v0.7.0");
    }
}
