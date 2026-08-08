/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.avif.javafx;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToolBar;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.glavo.avif.AvifDecodeException;
import org.glavo.avif.AvifFrame;
import org.glavo.avif.AvifImage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnknownNullability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Simple JavaFX viewer for local AVIF files.
///
/// The application is intentionally lightweight and uses the public decoding API directly.
/// Static images are displayed immediately and animated AVIF files are played back in an
/// [ImageView] according to the frame duration information exposed by [AvifImage].
@NotNullByDefault
public final class AvifViewerApp extends Application {

    /// Creates an uninitialized viewer application for the JavaFX launcher.
    public AvifViewerApp() {
    }

    /// Displays the current decoded frame.
    private final ImageView imageView = new ImageView();
    /// Displays the current file, image properties, and loading status.
    private final Label statusLabel = new Label("Open or drop an AVIF file to start.");
    /// Selects local AVIF input files.
    private final FileChooser fileChooser = createFileChooser();
    /// Hosts the image and receives pointer gestures used for panning.
    private final StackPane imagePane = new StackPane(imageView);
    /// Covers the image while a background decode is active.
    private final StackPane loadingOverlay = createLoadingOverlay();

    /// The primary application stage after [#start(Stage)] initializes it.
    private @UnknownNullability Stage stage;
    /// The currently displayed file path, or `null` when no image is loaded.
    private @Nullable Path currentPath;
    /// The currently displayed JavaFX image, or `null` when no image is loaded.
    private @Nullable AvifFXImage currentImage;
    /// The image viewport after [#start(Stage)] initializes it.
    private @Nullable ScrollPane scrollPane;
    /// The active background decode, or `null` when no load is pending.
    private @Nullable Task<AvifImage> loadTask;
    /// Monotonically increasing identifier used to discard stale load completions.
    private long loadRequestId;
    /// The pointer position at which the active pan gesture started, or `null` outside a gesture.
    private @Nullable Point2D dragAnchor;
    /// The horizontal scroll value captured at the start of a pan gesture.
    private double dragStartHValue;
    /// The vertical scroll value captured at the start of a pan gesture.
    private double dragStartVValue;

    /// Launches the viewer application.
    ///
    /// @param args optional command line arguments; the first argument may point to an AVIF file
    public static void main(String[] args) {
        launch(args);
    }

    /// Builds and shows the primary viewer window.
    ///
    /// @param primaryStage the primary JavaFX stage supplied by the runtime
    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;

        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        Button openButton = new Button("Open");
        openButton.setOnAction(event -> openFileChooser());

        ToolBar toolBar = new ToolBar(openButton, statusLabel);
        ScrollPane scrollPane = new ScrollPane(imagePane);
        scrollPane.setFitToHeight(true);
        scrollPane.setFitToWidth(true);
        scrollPane.setPannable(false);
        this.scrollPane = scrollPane;
        installDragPanHandlers();

        StackPane contentPane = new StackPane(scrollPane, loadingOverlay);
        BorderPane root = new BorderPane(contentPane);
        root.setTop(toolBar);
        BorderPane.setMargin(contentPane, new Insets(8));

        Scene scene = new Scene(root, 960, 720);
        installFileDropHandlers(scene);
        scene.setOnKeyPressed(event -> {
            if (Objects.requireNonNull(event.getCode()) == KeyCode.O) {
                openFileChooser();
            }
        });

        primaryStage.setTitle("AVIF Viewer");
        primaryStage.setScene(scene);
        primaryStage.show();

        List<String> arguments = getParameters().getRaw();
        if (!arguments.isEmpty()) {
            load(Path.of(arguments.get(0)));
        }
    }

    /// Stops any active playback when the application exits.
    @Override
    public void stop() {
        cancelLoadTask();
        stopPlayback();
    }

    /// Installs AVIF file drag-and-drop handling on the scene.
    ///
    /// @param scene the primary application scene
    private void installFileDropHandlers(Scene scene) {
        scene.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles() && findDroppedAvifFile(event.getDragboard().getFiles()) != null) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        scene.setOnDragDropped(event -> {
            Path path = findDroppedAvifFile(event.getDragboard().getFiles());
            if (path != null) {
                load(path);
                event.setDropCompleted(true);
            } else {
                event.setDropCompleted(false);
            }
            event.consume();
        });
    }

    /// Installs pointer handlers that pan images larger than the viewport.
    private void installDragPanHandlers() {
        imagePane.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY || scrollPane == null || imageView.getImage() == null) {
                return;
            }
            if (!canPanImage()) {
                return;
            }

            dragAnchor = new Point2D(event.getSceneX(), event.getSceneY());
            dragStartHValue = scrollPane.getHvalue();
            dragStartVValue = scrollPane.getVvalue();
            imagePane.setStyle("-fx-cursor: closed-hand;");
            event.consume();
        });
        imagePane.setOnMouseDragged(event -> {
            if (dragAnchor == null || scrollPane == null) {
                return;
            }

            double contentWidth = imagePane.getLayoutBounds().getWidth();
            double contentHeight = imagePane.getLayoutBounds().getHeight();
            double viewportWidth = scrollPane.getViewportBounds().getWidth();
            double viewportHeight = scrollPane.getViewportBounds().getHeight();

            double dx = event.getSceneX() - dragAnchor.getX();
            double dy = event.getSceneY() - dragAnchor.getY();

            if (contentWidth > viewportWidth) {
                double delta = dx / (contentWidth - viewportWidth);
                scrollPane.setHvalue(clamp(dragStartHValue - delta));
            }
            if (contentHeight > viewportHeight) {
                double delta = dy / (contentHeight - viewportHeight);
                scrollPane.setVvalue(clamp(dragStartVValue - delta));
            }

            event.consume();
        });
        imagePane.setOnMouseReleased(event -> finishDragPan());
        imagePane.setOnMouseExited(event -> {
            if (!event.isPrimaryButtonDown()) {
                finishDragPan();
            }
        });
    }

    /// Opens the file chooser and starts loading the selected file.
    private void openFileChooser() {
        Path initialDirectory = currentDirectory();
        if (initialDirectory != null) {
            fileChooser.setInitialDirectory(initialDirectory.toFile());
        }

        var selectedFile = fileChooser.showOpenDialog(stage);
        if (selectedFile != null) {
            load(selectedFile.toPath());
        }
    }

    /// Starts an asynchronous decode and supersedes any earlier load request.
    ///
    /// @param path the AVIF file to load
    private void load(Path path) {
        stopPlayback();
        cancelLoadTask();

        long startNanos = System.nanoTime();
        long requestId = ++loadRequestId;
        Task<AvifImage> task = new Task<>() {
            @Override
            protected AvifImage call() throws Exception {
                return decodeImage(path);
            }
        };

        loadTask = task;
        setLoading(true);
        statusLabel.setText("Loading " + path.getFileName() + "...");
        stage.setTitle("AVIF Viewer - Loading " + path.getFileName());

        task.setOnSucceeded(event -> {
            if (!isCurrentLoad(requestId, task)) {
                return;
            }

            loadTask = null;
            setLoading(false);
            applyLoadedImage(path, task.getValue(), startNanos);
        });
        task.setOnFailed(event -> {
            if (!isCurrentLoad(requestId, task)) {
                return;
            }

            loadTask = null;
            setLoading(false);
            handleLoadFailure(path, task.getException());
        });
        task.setOnCancelled(event -> {
            if (!isCurrentLoad(requestId, task)) {
                return;
            }

            loadTask = null;
            setLoading(false);
        });

        Thread worker = new Thread(task, "avif-viewer-load-" + requestId);
        worker.setDaemon(true);
        worker.start();
    }

    /// Decodes every frame and retains the immutable sequence playback metadata.
    ///
    /// @param path the AVIF file to decode
    /// @return the decoded viewer input
    /// @throws IOException if the file cannot be opened or decoded
    private static AvifImage decodeImage(Path path) throws IOException {
        return AvifImage.read(path);
    }

    /// Stops active animation and clears the current file state.
    private void stopPlayback() {
        if (currentImage != null) {
            var animation = currentImage.getAnimation();
            if (animation != null) {
                animation.stop();
            }
            currentImage = null;
        }
        currentPath = null;
    }

    /// Returns the directory containing the current file when it is still accessible.
    ///
    /// @return the current file directory, or `null`
    private @Nullable Path currentDirectory() {
        if (currentPath != null) {
            Path parent = currentPath.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return parent;
            }
        }
        return null;
    }

    /// Returns whether the displayed image exceeds either viewport dimension.
    ///
    /// @return whether pointer panning can move the image
    private boolean canPanImage() {
        if (scrollPane == null) {
            return false;
        }
        return imagePane.getLayoutBounds().getWidth() > scrollPane.getViewportBounds().getWidth()
                || imagePane.getLayoutBounds().getHeight() > scrollPane.getViewportBounds().getHeight();
    }

    /// Ends the active pan gesture and restores the default pointer style.
    private void finishDragPan() {
        dragAnchor = null;
        imagePane.setStyle("");
    }

    /// Installs one successfully decoded image and starts sequence playback when applicable.
    ///
    /// @param path the decoded file path
    /// @param loadedImage the fully decoded AVIF content
    /// @param startNanos the load start time returned by `System.nanoTime()`
    private void applyLoadedImage(Path path, AvifImage loadedImage, long startNanos) {
        long elapsedMillis = elapsedMillis(startNanos);

        @Unmodifiable List<AvifFrame> frames = loadedImage.frames();
        boolean animated = frames.size() > 1;
        AvifFXImage fxImage = new AvifFXImage(loadedImage, false);

        currentPath = path;
        currentImage = fxImage;

        imageView.setImage(fxImage);
        imageView.setFitWidth(fxImage.getWidth());
        imageView.setFitHeight(fxImage.getHeight());

        AvifFrame firstFrame = frames.get(0);
        statusLabel.setText(buildStatusText(path, firstFrame, frames.size(), animated, elapsedMillis));
        stage.setTitle("AVIF Viewer - " + path.getFileName());

        if (animated) {
            var timeline = fxImage.getAnimation();
            if (timeline != null) {
                timeline.play();
            }
        }
    }

    /// Clears failed-load state and presents a diagnostic alert.
    ///
    /// @param path the file that failed to load
    /// @param error the background task failure
    private void handleLoadFailure(Path path, Throwable error) {
        IOException exception = error instanceof IOException ioException
                ? ioException
                : new IOException("Failed to decode AVIF image", error);

        currentPath = null;
        currentImage = null;
        imageView.setImage(null);
        imageView.setFitWidth(0);
        imageView.setFitHeight(0);
        statusLabel.setText("Failed to open " + path.getFileName() + ": " + errorMessage(exception));
        stage.setTitle("AVIF Viewer");
        showLoadError(path, exception);
    }

    /// Cancels the active decode task, if any, and hides the loading overlay.
    private void cancelLoadTask() {
        @Nullable Task<AvifImage> task = loadTask;
        loadTask = null;
        if (task != null) {
            task.cancel();
        }
        setLoading(false);
    }

    /// Returns whether a background task still represents the latest load request.
    ///
    /// @param requestId the request identifier captured when the task was created
    /// @param task the background decode task
    /// @return whether the task may update the viewer
    private boolean isCurrentLoad(long requestId, Task<AvifImage> task) {
        return loadRequestId == requestId && loadTask == task;
    }

    /// Shows or hides the loading overlay.
    ///
    /// @param loading whether a background decode is active
    private void setLoading(boolean loading) {
        loadingOverlay.setManaged(loading);
        loadingOverlay.setVisible(loading);
    }

    /// Shows a load failure owned by the primary stage when it is available.
    ///
    /// @param path the file that failed to load
    /// @param ex the decoded I/O failure
    private void showLoadError(Path path, IOException ex) {
        if (stage == null) {
            return;
        }

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setHeaderText("Failed to open AVIF image");
        alert.setContentText(path.getFileName() + ": " + ex.getMessage());
        alert.show();
    }

    /// Returns the first regular dropped `.avif` file.
    ///
    /// @param files the files supplied by the dragboard
    /// @return the selected path, or `null` when no AVIF file is present
    private static @Nullable Path findDroppedAvifFile(List<java.io.File> files) {
        for (java.io.File file : files) {
            Path path = file.toPath();
            if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".avif")) {
                return path;
            }
        }
        return null;
    }

    /// Restricts a scroll value to the inclusive range from zero to one.
    ///
    /// @param value the source value
    /// @return the restricted value
    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /// Returns a non-empty diagnostic message for a failure.
    ///
    /// @param error the failure to describe
    /// @return the failure message or simple class name
    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    /// Returns elapsed whole milliseconds since a `System.nanoTime()` sample.
    ///
    /// @param startNanos the starting monotonic-clock sample
    /// @return the non-rounded elapsed milliseconds
    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /// Builds the compact status text for a successfully decoded image.
    ///
    /// @param path the decoded file
    /// @param frame the first presented frame
    /// @param frameCount the number of presented frames
    /// @param animated whether the image contains multiple frames
    /// @param loadMillis the decode time in milliseconds
    /// @return the status label text
    private String buildStatusText(Path path, AvifFrame frame, int frameCount, boolean animated, long loadMillis) {
        StringBuilder text = new StringBuilder();
        text.append(path.getFileName())
                .append(" | ")
                .append(frame.width())
                .append("x")
                .append(frame.height());

        if (animated) {
            text.append(" | animated | frames=").append(frameCount);
        } else {
            text.append(" | still");
        }

        text.append(" | depth=").append(frame.bitDepth())
                .append(" | format=").append(frame.chromaFormat())
                .append(" | load=").append(loadMillis).append("ms");
        return text.toString();
    }

    /// Creates the AVIF-focused file chooser.
    ///
    /// @return the configured chooser
    private static FileChooser createFileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open AVIF Image");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("AVIF Images", "*.avif"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        return chooser;
    }

    /// Creates the initially hidden loading overlay.
    ///
    /// @return the configured overlay
    private static StackPane createLoadingOverlay() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMaxSize(96, 96);

        StackPane overlay = new StackPane(indicator);
        overlay.setManaged(false);
        overlay.setVisible(false);
        overlay.setStyle("-fx-background-color: rgba(255, 255, 255, 0.72);");
        return overlay;
    }

}
