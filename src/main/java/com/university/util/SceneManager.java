package com.university.util;

import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * The single place that loads FXML and moves the user between screens.
 *
 * Two kinds of navigation:
 *   switchRoot(...)  â€” replaces the whole window content (login  <->  main shell)
 *   navigateTo(...)  â€” replaces only the content area inside the main shell (sidebar navigation)
 */
public final class SceneManager {

    private static final SceneManager INSTANCE = new SceneManager();

    public static final String FXML_DIR = "/fxml/";
    public static final String CSS_PATH = "/css/app.css";
    public static final double MIN_WIDTH  = 1200;   // project_details.md Section 13 â€” Responsiveness
    public static final double MIN_HEIGHT = 780;

    private Stage primaryStage;
    private Pane contentArea;
    private String currentViewFxml;
    private String currentViewTitle;

    private SceneManager() { }

    public static SceneManager getInstance() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- setup

    /** Called once, from App.start(). */
    public void init(Stage stage) {
        this.primaryStage = Objects.requireNonNull(stage, "primaryStage");
        this.primaryStage.setMinWidth(MIN_WIDTH);
        this.primaryStage.setMinHeight(MIN_HEIGHT);
    }

    public Stage getPrimaryStage() {
        if (primaryStage == null) {
            throw new IllegalStateException("SceneManager.init(stage) was never called.");
        }
        return primaryStage;
    }

    /** Called by MainShellController so that any screen can navigate without knowing the shell. */
    public void registerContentArea(Pane pane) {
        this.contentArea = pane;
    }

    /** The FXML file currently shown in the content area, e.g. "admin_students.fxml". */
    public String getCurrentViewFxml() {
        return currentViewFxml;
    }

    /** The title passed alongside the current view â€” what a "back" action should restore. */
    public String getCurrentViewTitle() {
        return currentViewTitle;
    }

    /** true when src/main/resources/fxml/&lt;name&gt; exists on the classpath. */
    public boolean viewExists(String fxmlFileName) {
        return SceneManager.class.getResource(FXML_DIR + fxmlFileName) != null;
    }

    // ---------------------------------------------------------------- navigation

    /**
     * Replaces the entire window content. Used exactly twice: login -> shell, and shell -> login.
     *
     * @return the controller of the freshly loaded FXML, so the caller can pass data into it
     */
    public <T> T switchRoot(String fxmlFileName, String windowTitle) {
        try {
            // Forget the outgoing shell BEFORE loading the new root. Loading it
            // runs MainShellController.initialize(), which registers the new
            // content area â€” clearing afterwards would throw that away, and
            // every sidebar click would then fail with "No content area
            // registered".
            this.contentArea = null;
            this.currentViewFxml = null;
            this.currentViewTitle = null;

            FXMLLoader loader = new FXMLLoader(requireResource(fxmlFileName));
            Parent root = loader.load();

            Scene scene = getPrimaryStage().getScene();
            if (scene == null) {
                scene = new Scene(root, MIN_WIDTH, MIN_HEIGHT);
                applyStylesheet(scene);
                getPrimaryStage().setScene(scene);
            } else {
                scene.setRoot(root);
                applyStylesheet(scene);
            }

            getPrimaryStage().setTitle(windowTitle);
            return loader.getController();

        } catch (IOException e) {
            AlertUtil.error("Cannot open screen",
                    "The screen '" + fxmlFileName + "' could not be opened.", e);
            throw new IllegalStateException("Failed to load " + fxmlFileName, e);
        }
    }

    /**
     * Loads a screen into the shell's content area.
     *
     * If the FXML does not exist yet (because it belongs to a phase that has not been built),
     * a friendly placeholder is shown instead of crashing. This is deliberate: the sidebar shows
     * every menu item from day one, and each later phase simply adds its FXML file.
     *
     * @return the controller, or null when the placeholder was shown
     */
    public <T> T navigateTo(String fxmlFileName, String title) {
        return navigateTo(fxmlFileName, title, true);
    }

    public <T> T navigateToInstant(String fxmlFileName, String title) {
        return navigateTo(fxmlFileName, title, false);
    }

    private <T> T navigateTo(String fxmlFileName, String title, boolean animate) {
        if (contentArea == null) {
            throw new IllegalStateException("No content area registered. Is the main shell loaded?");
        }

        if (!viewExists(fxmlFileName)) {
            contentArea.getChildren().setAll(placeholder(title, fxmlFileName));
            currentViewFxml = null;
            currentViewTitle = null;
            return null;
        }

        try {
            FXMLLoader loader = new FXMLLoader(requireResource(fxmlFileName));
            Parent view = loader.load();
            contentArea.getChildren().setAll(view);
            if (animate) AnimationUtility.playPageEnter(view);
            currentViewFxml = fxmlFileName;
            currentViewTitle = title;
            return loader.getController();

        } catch (IOException e) {
            contentArea.getChildren().setAll(placeholder(title, fxmlFileName));
            currentViewFxml = null;
            currentViewTitle = null;
            AlertUtil.error("Cannot open screen",
                    "The screen '" + title + "' could not be opened.", e);
            return null;
        }
    }

    /** Reloads the current content view â€” useful after a dialog changed the data. */
    public void reloadCurrentView(String title) {
        if (currentViewFxml != null) {
            navigateTo(currentViewFxml, title);
        }
    }

    public void applyStylesheet(Scene scene) {
        URL css = SceneManager.class.getResource(CSS_PATH);
        if (css != null && !scene.getStylesheets().contains(css.toExternalForm())) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }

    // ---------------------------------------------------------------- helpers

    private URL requireResource(String fxmlFileName) throws IOException {
        URL url = SceneManager.class.getResource(FXML_DIR + fxmlFileName);
        if (url == null) {
            throw new IOException("FXML not found on the classpath: " + FXML_DIR + fxmlFileName);
        }
        return url;
    }

    private Parent placeholder(String title, String fxmlFileName) {
        Label heading = new Label(title);
        heading.getStyleClass().add("page-title");

        Label note = new Label("This screen will be available in a later phase.");
        note.getStyleClass().add("muted-text");

        Label file = new Label("(" + fxmlFileName + ")");
        file.getStyleClass().add("muted-text");

        VBox box = new VBox(10, heading, note, file);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("content-page");
        return box;
    }
}



