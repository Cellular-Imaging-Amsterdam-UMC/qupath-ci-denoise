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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CIDenoiseExtension implements QuPathExtension {
    static final String NAME = "CI Denoise";
    private static final String DOCS = "https://github.com/Cellular-Imaging-Amsterdam-UMC/qupath-ci-denoise#readme";
    private static final Logger logger = LoggerFactory.getLogger(CIDenoiseExtension.class);
    private final SimpleBooleanProperty busy = new SimpleBooleanProperty();
    private boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed)
            return;
        installed = true;
        var run = new MenuItem("Run denoising…");
        run.disableProperty().bind(busy.or(qupath.imageDataProperty().isNull()));
        run.setOnAction(event -> {
            var data = qupath.getImageData();
            if (data == null)
                return;
            var project = qupath.getProject();
            var viewer = qupath.getViewer();
            var dialog = new CIDenoiseDialog(qupath, data, defaultDirectory(data.getServer(), project));
            dialog.show().ifPresent(request -> run(qupath, project, viewer, data, request));
        });
        var validate = new MenuItem("Validate Noise2Noise model");
        validate.disableProperty().bind(busy);
        validate.setOnAction(event -> validate(qupath));
        var script = new MenuItem("Open example Groovy script");
        script.setOnAction(event -> showScript(qupath));
        var docs = new MenuItem("Open documentation");
        docs.setOnAction(event -> QuPathGUI.openInBrowser(DOCS));
        qupath.getMenu("Extensions>" + NAME, true).getItems().addAll(run, validate, new SeparatorMenuItem(), script, docs);
    }

    private void run(QuPathGUI qupath, Project<BufferedImage> project, qupath.lib.gui.viewer.QuPathViewer viewer,
                     qupath.lib.images.ImageData<BufferedImage> data, CIDenoiseDialog.Request request) {
        var cancelled = new AtomicBoolean();
        var task = new Task<List<Path>>() {
            @Override
            protected List<Path> call() throws Exception {
                return CIDenoise.run(data.getServer(), request.regions(), request.directory(), request.options(),
                        cancelled::get, (done, total, message) -> {
                            updateMessage(message);
                            updateProgress(done, total);
                        });
            }
        };

        var progress = new Dialog<ButtonType>();
        progress.setTitle(NAME);
        progress.initOwner(qupath.getStage());
        progress.initModality(javafx.stage.Modality.NONE);
        var pane = progress.getDialogPane();
        CIDenoiseDialog.style(pane);
        pane.setHeader(CIDenoiseDialog.header("CI Denoise", "Running Noise2Noise-FMD"));
        pane.getButtonTypes().add(ButtonType.CANCEL);
        var status = new Label();
        status.setWrapText(true);
        status.textProperty().bind(task.messageProperty());
        var bar = new ProgressBar();
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.progressProperty().bind(task.progressProperty());
        var body = new VBox(8, status, bar);
        body.getStyleClass().add("denoise-body");
        body.setPrefWidth(560);
        pane.setContent(body);

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
            busy.set(false);
        };
        task.setOnSucceeded(event -> {
            finish.run();
            var outputs = task.getValue();
            boolean originalViewerUnchanged = qupath.getViewer() == viewer && qupath.getImageData() == data;
            try {
                if (qupath.getProject() == project) {
                    var first = request.add() ? addToProject(project, outputs) : null;
                    if (request.open() && originalViewerUnchanged) {
                        boolean opened = first == null ? qupath.openImage(viewer, outputs.getFirst().toUri().toString())
                                : qupath.openImageEntry(first);
                        if (!opened)
                            throw new IllegalStateException("QuPath could not open the exported image.");
                    }
                } else if (request.add()) {
                    Dialogs.showWarningNotification(NAME, "The active project changed. Results were saved in " + request.directory());
                    return;
                }
                Dialogs.showInfoNotification(NAME, "Saved " + outputs.size() + (outputs.size() == 1 ? " image to " : " images to ") + request.directory());
            } catch (Exception e) {
                logger.warn("Denoising outputs were written, but could not be imported or opened", e);
                Dialogs.showWarningNotification(NAME, "Results were saved in " + request.directory() + ". " + e.getMessage());
            }
        });
        task.setOnFailed(event -> {
            finish.run();
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
        qupath.createSingleThreadExecutor(this).submit(task);
    }

    private static ProjectImageEntry<BufferedImage> addToProject(Project<BufferedImage> project, List<Path> outputs)
            throws Exception {
        var newEntries = new java.util.ArrayList<ProjectImageEntry<BufferedImage>>();
        try {
            for (Path output : outputs) {
                try (var server = ImageServerProvider.buildServer(output.toUri().toString(), BufferedImage.class)) {
                    newEntries.add(project.addImage(server.getBuilder()));
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
        var task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                CIDenoise.validateModel();
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            busy.set(false);
            Dialogs.showInfoNotification(NAME, "Noise2Noise-FMD passed the CPU inference check.");
        });
        task.setOnFailed(event -> {
            busy.set(false);
            logger.error("Noise2Noise-FMD validation failed", task.getException());
            Dialogs.showErrorMessage(NAME, task.getException());
        });
        busy.set(true);
        qupath.createSingleThreadExecutor(this).submit(task);
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
                def options = new CIDenoise.Options(CIDenoise.Format.OME_ZARR, maxZ, 512, 128, [])
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
        return "Denoise fluorescence microscopy images with the bundled Noise2Noise-FMD model.";
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("v0.7.0");
    }
}
