import com.university.enums.UserRole;
import com.university.service.AuthService;
import com.university.util.SceneManager;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Circle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the real login screen with a real mouse (javafx.scene.robot.Robot) and
 * checks the back arrow: default look, hover halo + 1.05 scale, pressed shrink,
 * and that a genuine click lands back on role selection. Also re-checks that
 * admin user_id 1 still signs in.
 *
 * <p>Not part of the build. Read-only against the database.</p>
 */
public class BackArrowProbe extends Application {

    private static final List<String> RESULTS = new ArrayList<>();
    private static int failures = 0;

    private Stage stage;
    private Robot robot;
    private Node backButton;
    private Node backGraphic;
    private Circle backHoverCircle;

    private final List<Runnable> steps = new ArrayList<>();
    private int stepIndex = 0;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        setUserAgentStylesheet(STYLESHEET_MODENA);

        SceneManager.getInstance().init(primaryStage);
        SceneManager.getInstance().switchRoot("role_selection.fxml", "Probe — Select Role");
        primaryStage.show();
        primaryStage.centerOnScreen();
        primaryStage.toFront();

        robot = new Robot();

        steps.add(this::stepFocusWindow);
        steps.add(this::stepGoToLogin);
        steps.add(this::stepCheckDefault);
        steps.add(this::stepHover);
        steps.add(this::stepCheckHover);
        steps.add(this::stepPress);
        steps.add(this::stepCheckPressed);
        steps.add(this::stepRelease);
        steps.add(this::stepCheckNavigatedBack);
        steps.add(this::stepCheckAdminLogin);
        steps.add(this::finish);

        runNext();
    }

    private void runNext() {
        if (stepIndex >= steps.size()) {
            return;
        }
        Runnable step = steps.get(stepIndex++);
        PauseTransition pause = new PauseTransition(Duration.millis(600));
        pause.setOnFinished(e -> {
            try {
                step.run();
            } catch (Throwable t) {
                fail("step " + (stepIndex - 1) + " threw " + t);
                t.printStackTrace(System.out);
            }
            runNext();
        });
        pause.play();
    }

    // ------------------------------------------------------------------ steps

    /** Robot events are ignored until the window is the foreground window. */
    private void stepFocusWindow() {
        stage.toFront();
        stage.requestFocus();
        RESULTS.add("   display output scale = " + round(Screen.getPrimary().getOutputScaleX()));
    }

    /**
     * Opens login exactly the way RoleSelectionController.openLogin does
     * (SceneManager.switchRoot + setRole). The role screen's hot-spots are not
     * what is under test here — the back arrow is — so this skips straight there.
     */
    private void stepGoToLogin() {
        check("role selection is the starting screen",
                stage.getScene().getRoot().getStyleClass().contains("role-selection-root"));
        com.university.controller.LoginController controller = SceneManager.getInstance()
                .switchRoot("login.fxml", "University Registration System — Login");
        controller.setRole(UserRole.ADMIN);
    }

    private void stepCheckDefault() {
        Parent root = stage.getScene().getRoot();
        check("login screen is now showing",
                root.getStyleClass().contains("login-root"));

        check("old white rectangular Back button is gone",
                root.lookup(".back-button") == null);

        backButton = root.lookup("#backButton");
        backGraphic = root.lookup("#backGraphic");
        backHoverCircle = (Circle) root.lookup("#backHoverCircle");
        check("arrow button, graphic and halo all exist",
                backButton != null && backGraphic != null && backHoverCircle != null);

        Bounds b = backButton.localToScene(backButton.getBoundsInLocal());
        check("arrow sits in the top-left corner (x=" + round(b.getMinX())
                        + ", y=" + round(b.getMinY()) + ")",
                b.getMinX() > 0 && b.getMinX() < 120 && b.getMinY() > 0 && b.getMinY() < 120);
        check("arrow clears the window edges by >= 20px on both sides",
                b.getMinX() >= 20 && b.getMinY() >= 20);

        check("halo is invisible by default (opacity=" + round(backHoverCircle.getOpacity()) + ")",
                backHoverCircle.getOpacity() < 0.01);
        check("arrow is at rest scale by default (scale=" + round(backGraphic.getScaleX()) + ")",
                Math.abs(backGraphic.getScaleX() - 1.0) < 0.01);

        Node icon = stage.getScene().getRoot().lookup(".back-arrow-icon");
        check("arrow icon paints white",
                icon instanceof javafx.scene.shape.SVGPath
                        && javafx.scene.paint.Color.WHITE.equals(
                                ((javafx.scene.shape.SVGPath) icon).getFill()));

        shoot("back_arrow_1_default");
    }

    private void stepHover() {
        Bounds b = backButton.localToScreen(backButton.getBoundsInLocal());
        RESULTS.add("   stage x=" + round(stage.getX()) + " y=" + round(stage.getY())
                + " w=" + round(stage.getWidth()) + " h=" + round(stage.getHeight())
                + " focused=" + stage.isFocused());
        RESULTS.add("   arrow on screen x=" + round(b.getMinX()) + " y=" + round(b.getMinY())
                + " w=" + round(b.getWidth()) + " h=" + round(b.getHeight()));
        moveTo(backButton);
        RESULTS.add("   robot mouse now at x=" + round(robot.getMouseX())
                + " y=" + round(robot.getMouseY()));
    }

    private void stepCheckHover() {
        check("button reports hovered", backButton.isHover());
        double opacity = backHoverCircle.getOpacity();
        double scale = backGraphic.getScaleX();
        check("hover fades in a semi-transparent halo (opacity=" + round(opacity) + ")",
                opacity > 0.9);
        check("halo fill is semi-transparent, not solid white",
                backHoverCircle.getFill() instanceof javafx.scene.paint.Color
                        && ((javafx.scene.paint.Color) backHoverCircle.getFill()).getOpacity() < 0.3);
        check("hover enlarges the arrow ~5% (scale=" + round(scale) + ")",
                Math.abs(scale - 1.05) < 0.01);
        shoot("back_arrow_2_hover");
    }

    private void stepPress() {
        robot.mousePress(MouseButton.PRIMARY);
    }

    private void stepCheckPressed() {
        double scale = backGraphic.getScaleX();
        check("press shrinks the arrow (scale=" + round(scale) + ")",
                scale < 0.99 && scale > 0.85);
        check("halo stays visible while pressed", backHoverCircle.getOpacity() > 0.9);
        shoot("back_arrow_3_pressed");
    }

    private void stepRelease() {
        robot.mouseRelease(MouseButton.PRIMARY);
    }

    private void stepCheckNavigatedBack() {
        Parent root = stage.getScene().getRoot();
        check("real click navigated back to Role Selection",
                root.getStyleClass().contains("role-selection-root"));
        check("window title switched back",
                stage.getTitle() != null && stage.getTitle().contains("Select Role"));
        shoot("back_arrow_4_after_click");
    }

    private void stepCheckAdminLogin() {
        try {
            var user = new AuthService().login(UserRole.ADMIN, "1", "1@iuL");
            check("admin user_id 1 still signs in (role=" + user.getRole() + ")", user != null);
        } catch (Throwable t) {
            fail("admin user_id 1 login FAILED: " + t);
        }
    }

    private void finish() {
        System.out.println();
        System.out.println("================ BACK ARROW PROBE ================");
        RESULTS.forEach(System.out::println);
        System.out.println("==================================================");
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ helpers

    /** Robot and localToScreen agree on logical coordinates — verified with RobotDiag. */
    private void moveTo(Node node) {
        Bounds b = node.localToScreen(node.getBoundsInLocal());
        robot.mouseMove(b.getMinX() + b.getWidth() / 2, b.getMinY() + b.getHeight() / 2);
    }

    private void clickCentre(Node node) {
        moveTo(node);
        robot.mousePress(MouseButton.PRIMARY);
        robot.mouseRelease(MouseButton.PRIMARY);
    }

    /**
     * Scene snapshot to PNG. Copies pixels by hand rather than using
     * SwingFXUtils, because javafx-swing is not a dependency of this project and
     * a probe must not make me add one.
     */
    private void shoot(String name) {
        try {
            WritableImage image = stage.getScene().snapshot(null);
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();
            PixelReader reader = image.getPixelReader();
            BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    buffered.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            File out = new File("scratch/" + name + ".png");
            ImageIO.write(buffered, "png", out);
            RESULTS.add("   saved " + out.getPath());
        } catch (Exception e) {
            RESULTS.add("   snapshot " + name + " failed: " + e);
        }
    }

    private void check(String label, boolean ok) {
        if (!ok) {
            failures++;
        }
        RESULTS.add((ok ? "PASS  " : "FAIL  ") + label);
    }

    private void fail(String label) {
        failures++;
        RESULTS.add("FAIL  " + label);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
