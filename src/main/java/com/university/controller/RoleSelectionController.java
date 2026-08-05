package com.university.controller;

import com.university.enums.UserRole;
import com.university.util.SceneManager;
import javafx.fxml.FXML;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * The very first screen: pick Admin, Instructor or Student before signing in.
 *
 * <p>The screen is nothing but the firstpicture.png artwork, shown with
 * "cover" behavior: it always fills the whole window with no letterboxing,
 * preserving its aspect ratio and cropping only the minimum needed off the
 * outer edges when the window's aspect ratio differs from the image's. Three
 * invisible {@link Region}s sit on top of the ImageView and are repositioned
 * on every resize to track the ADMIN / INSTRUCTOR / STUDENT boxes already
 * drawn into that image, so nothing is drawn over it.</p>
 *
 * <p>The window itself is left fully in charge of its own size (standard
 * Minimize / Maximize / Close all behave normally — nothing here fights the
 * OS for control of the stage).</p>
 */
public class RoleSelectionController {

    @FXML private StackPane root;
    @FXML private ImageView roleImageView;
    @FXML private Pane hitOverlay;
    @FXML private Region instructorHit;
    @FXML private Region adminHit;
    @FXML private Region studentHit;

    // Fractions {xMin, yMin, xMax, yMax} of the 1280x720 firstpicture.png
    // covered by each box, measured directly from the artwork. Keep these in
    // sync if the image is ever replaced with a different layout.
    private static final double[] INSTRUCTOR_RECT = {0.3336, 0.6986, 0.4203, 0.8306};
    private static final double[] ADMIN_RECT       = {0.4539, 0.6986, 0.5406, 0.8306};
    private static final double[] STUDENT_RECT     = {0.5742, 0.6986, 0.6609, 0.8306};

    private double imageWidth;
    private double imageHeight;

    @FXML
    private void initialize() {
        Image image = roleImageView.getImage();
        imageWidth = image.getWidth();
        imageHeight = image.getHeight();

        roleImageView.fitWidthProperty().bind(root.widthProperty());
        roleImageView.fitHeightProperty().bind(root.heightProperty());

        root.widthProperty().addListener((obs, old, val) -> updateLayout());
        root.heightProperty().addListener((obs, old, val) -> updateLayout());
        updateLayout();
    }

    /**
     * Recomputes the ImageView's viewport so the artwork covers the whole
     * root (cropping only the outer edges of whichever axis overflows) and
     * repositions the hit areas to match the newly visible crop region.
     */
    private void updateLayout() {
        double containerWidth = root.getWidth();
        double containerHeight = root.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return;
        }

        double scale = Math.max(containerWidth / imageWidth, containerHeight / imageHeight);
        double viewportWidth = containerWidth / scale;
        double viewportHeight = containerHeight / scale;
        double viewportX = (imageWidth - viewportWidth) / 2.0;
        double viewportY = (imageHeight - viewportHeight) / 2.0;
        roleImageView.setViewport(new Rectangle2D(viewportX, viewportY, viewportWidth, viewportHeight));

        place(instructorHit, viewportX, viewportY, viewportWidth, viewportHeight, containerWidth, containerHeight, INSTRUCTOR_RECT);
        place(adminHit, viewportX, viewportY, viewportWidth, viewportHeight, containerWidth, containerHeight, ADMIN_RECT);
        place(studentHit, viewportX, viewportY, viewportWidth, viewportHeight, containerWidth, containerHeight, STUDENT_RECT);
    }

    /**
     * Maps a hit box's fractional coordinates (in full-image space) into
     * on-screen pixels, accounting for the crop applied by the viewport so
     * the transparent hit areas stay locked onto the artwork's buttons.
     */
    private void place(Region hit, double viewportX, double viewportY, double viewportWidth, double viewportHeight,
                        double containerWidth, double containerHeight, double[] rect) {
        double relX0 = (rect[0] * imageWidth - viewportX) / viewportWidth;
        double relY0 = (rect[1] * imageHeight - viewportY) / viewportHeight;
        double relX1 = (rect[2] * imageWidth - viewportX) / viewportWidth;
        double relY1 = (rect[3] * imageHeight - viewportY) / viewportHeight;

        hit.setLayoutX(relX0 * containerWidth);
        hit.setLayoutY(relY0 * containerHeight);
        hit.setPrefWidth((relX1 - relX0) * containerWidth);
        hit.setPrefHeight((relY1 - relY0) * containerHeight);
    }

    @FXML
    private void handleAdminClick() {
        openLogin(UserRole.ADMIN);
    }

    @FXML
    private void handleInstructorClick() {
        openLogin(UserRole.INSTRUCTOR);
    }

    @FXML
    private void handleStudentClick() {
        openLogin(UserRole.STUDENT);
    }

    private void openLogin(UserRole role) {
        LoginController controller = SceneManager.getInstance()
                .switchRoot("login.fxml", "University Registration System — Login");
        controller.setRole(role);
    }
}
