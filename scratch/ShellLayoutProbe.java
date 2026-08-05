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
import javafx.scene.layout.Region;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the real admin shell with a real mouse and measures the layout in the
 * open state, the hidden state, and after restoring — plus across several admin
 * pages. Not part of the build. Read-only against the database.
 */
public class ShellLayoutProbe extends Application {

    private static final List<String> RESULTS = new ArrayList<>();
    private static int failures = 0;

    private static final double SIDEBAR_WIDTH = 260;

    private Stage stage;
    private Robot robot;

    private final List<Runnable> steps = new ArrayList<>();
    private int stepIndex = 0;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        setUserAgentStylesheet(STYLESHEET_MODENA);

        new AuthService().login(UserRole.ADMIN, "1", "1@iuL");

        SceneManager.getInstance().init(primaryStage);
        SceneManager.getInstance().switchRoot("main_shell.fxml", "Probe — Admin");
        primaryStage.show();
        primaryStage.centerOnScreen();
        primaryStage.toFront();
        primaryStage.requestFocus();

        robot = new Robot();

        steps.add(this::checkOpenState);
        steps.add(this::checkMidAnimation);   // clicks to close, samples partway through
        steps.add(this::checkHiddenState);
        steps.add(this::clickHamburger);
        steps.add(this::checkRestoredState);
        steps.add(() -> navigate("Students"));
        steps.add(() -> checkPageLayout("Students", false));
        steps.add(() -> navigate("Courses"));
        steps.add(() -> checkPageLayout("Courses", false));
        steps.add(() -> navigate("Reports & Analytics"));
        steps.add(() -> checkPageLayout("Reports & Analytics", false));
        steps.add(this::clickHamburger);
        steps.add(() -> checkPageLayout("Reports & Analytics (sidebar hidden)", true));
        steps.add(() -> navigate("Audit Log"));
        steps.add(() -> checkPageLayout("Audit Log (sidebar still hidden)", true));
        steps.add(this::finish);

        runNext();
    }

    private void runNext() {
        if (stepIndex >= steps.size()) {
            return;
        }
        Runnable step = steps.get(stepIndex++);
        PauseTransition pause = new PauseTransition(Duration.millis(700));
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

    // ------------------------------------------------------------------ states

    private void checkOpenState() {
        Region shell = region("#sidebarShell");
        Region panel = region("#sidebarPanel");
        Region topBar = topBar();
        Region content = region("#contentArea");
        Bounds sb = sceneBounds(shell);
        Bounds tb = sceneBounds(topBar);
        Bounds cb = sceneBounds(content);
        double sceneH = stage.getScene().getHeight();
        double sceneW = stage.getScene().getWidth();

        check("OPEN: sidebar starts at the absolute top-left corner (x="
                        + r(sb.getMinX()) + ", y=" + r(sb.getMinY()) + ")",
                sb.getMinX() == 0 && sb.getMinY() == 0);
        check("OPEN: sidebar runs the full window height (" + r(sb.getHeight())
                        + " of " + r(sceneH) + ")",
                Math.abs(sb.getHeight() - sceneH) < 1);
        check("OPEN: sidebar keeps its original 260 width (" + r(sb.getWidth()) + ")",
                Math.abs(sb.getWidth() - SIDEBAR_WIDTH) < 1);

        check("OPEN: top bar does NOT sit above the sidebar — it starts where the sidebar ends (x="
                        + r(tb.getMinX()) + ")",
                Math.abs(tb.getMinX() - sb.getMaxX()) < 1);
        check("OPEN: top bar covers only the content column (width=" + r(tb.getWidth())
                        + ", to window edge=" + r(sceneW - SIDEBAR_WIDTH) + ")",
                Math.abs(tb.getWidth() - (sceneW - SIDEBAR_WIDTH)) < 1);
        check("OPEN: top bar is flush with the top of the window (y=" + r(tb.getMinY()) + ")",
                Math.abs(tb.getMinY()) < 1);

        check("OPEN: content begins directly below the top bar (content y=" + r(cb.getMinY())
                        + ", top bar bottom=" + r(tb.getMaxY()) + ")",
                Math.abs(cb.getMinY() - tb.getMaxY()) < 1);
        check("OPEN: content begins to the right of the sidebar (x=" + r(cb.getMinX()) + ")",
                Math.abs(cb.getMinX() - sb.getMaxX()) < 1);
        check("OPEN: no gap or overlap between sidebar and content column",
                Math.abs(cb.getMaxX() - sceneW) < 1);

        check("OPEN: logo/name/menu panel is visible at full width",
                panel.isVisible() && Math.abs(panel.getWidth() - SIDEBAR_WIDTH) < 1
                        && panel.getTranslateX() == 0);
        check("OPEN: MENU title and nav items are present",
                menuLabels().contains("MENU") && menuLabels().contains("Dashboard"));

        RESULTS.add("   menu = " + menuLabels());
        shoot("shell_1_open");
    }

    private void checkHiddenState() {
        Region shell = region("#sidebarShell");
        Region panel = region("#sidebarPanel");
        Region topBar = topBar();
        Region content = region("#contentArea");
        Bounds tb = sceneBounds(topBar);
        Bounds cb = sceneBounds(content);
        double sceneW = stage.getScene().getWidth();

        check("HIDDEN: sidebar width is exactly 0 (" + r(shell.getWidth()) + ")",
                shell.getWidth() == 0);
        check("HIDDEN: sidebar min/pref/max are all 0",
                shell.getMinWidth() == 0 && shell.getPrefWidth() == 0 && shell.getMaxWidth() == 0);
        check("HIDDEN: sidebar panel (logo, names, MENU, buttons) is not visible",
                !panel.isVisible());

        check("HIDDEN: top bar expanded to the full window width (" + r(tb.getWidth())
                        + " of " + r(sceneW) + ")",
                Math.abs(tb.getWidth() - sceneW) < 1);
        check("HIDDEN: top bar now starts at x=0 (" + r(tb.getMinX()) + ")",
                Math.abs(tb.getMinX()) < 1);

        Bounds hb = sceneBounds(node("#hamburgerButton"));
        check("HIDDEN: hamburger moved left with the bar (x=" + r(hb.getMinX())
                        + ", was ~" + r(SIDEBAR_WIDTH + 18) + ")",
                hb.getMinX() < 40);
        check("HIDDEN: welcome text moved left with the hamburger (x="
                        + r(sceneBounds(node("#welcomeNameLabel")).getMinX()) + ")",
                sceneBounds(node("#welcomeNameLabel")).getMinX() < 200);

        check("HIDDEN: content expanded to the full width (x=" + r(cb.getMinX())
                        + ", width=" + r(cb.getWidth()) + ")",
                Math.abs(cb.getMinX()) < 1 && Math.abs(cb.getWidth() - sceneW) < 1);

        check("HIDDEN: nothing blue left in the old sidebar strip",
                leftStripIsContentBackground());

        shoot("shell_2_hidden");
    }

    private void checkRestoredState() {
        Region shell = region("#sidebarShell");
        Region panel = region("#sidebarPanel");
        Bounds sb = sceneBounds(shell);
        Bounds tb = sceneBounds(topBar());

        check("RESTORED: sidebar is back at 260 (" + r(sb.getWidth()) + ")",
                Math.abs(sb.getWidth() - SIDEBAR_WIDTH) < 1);
        check("RESTORED: panel visible again, fully slid back in (translateX="
                        + r(panel.getTranslateX()) + ")",
                panel.isVisible() && Math.abs(panel.getTranslateX()) < 0.5);
        check("RESTORED: logo, university names, MENU and nav items all back",
                menuLabels().contains("MENU") && menuLabels().contains("Dashboard")
                        && countImages(panel) == 3);
        check("RESTORED: top bar begins exactly after the sidebar again (x=" + r(tb.getMinX()) + ")",
                Math.abs(tb.getMinX() - sb.getMaxX()) < 1);
        check("RESTORED: sidebar full height again", Math.abs(sb.getHeight()
                - stage.getScene().getHeight()) < 1);

        shoot("shell_3_restored");
    }

    /** Same alignment invariants, whichever admin page is loaded. */
    private void checkPageLayout(String page, boolean sidebarHidden) {
        Bounds sb = sceneBounds(region("#sidebarShell"));
        Bounds tb = sceneBounds(topBar());
        Bounds cb = sceneBounds(region("#contentArea"));
        double sceneW = stage.getScene().getWidth();
        double expected = sidebarHidden ? 0 : SIDEBAR_WIDTH;

        check("PAGE " + page + ": content area is populated",
                !((javafx.scene.layout.StackPane) node("#contentArea")).getChildren().isEmpty());
        check("PAGE " + page + ": sidebar width " + r(sb.getWidth()) + " (expected " + expected + ")",
                Math.abs(sb.getWidth() - expected) < 1);
        check("PAGE " + page + ": top bar starts after the sidebar and spans the rest",
                Math.abs(tb.getMinX() - expected) < 1
                        && Math.abs(tb.getWidth() - (sceneW - expected)) < 1);
        check("PAGE " + page + ": content sits below the top bar, right of the sidebar, no gap",
                Math.abs(cb.getMinY() - tb.getMaxY()) < 1
                        && Math.abs(cb.getMinX() - expected) < 1
                        && Math.abs(cb.getMaxX() - sceneW) < 1);
    }

    /**
     * Requirement 33: partway through the slide, the logo and menu must not be painting
     * over the content. Clicks, then samples 130ms in — roughly half of the 270ms run.
     */
    private void checkMidAnimation() {
        StringBuilder trace = new StringBuilder("   width trace:");
        javafx.animation.Timeline tracer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.millis(20),
                        e -> trace.append(' ').append(r(region("#sidebarShell").getWidth()))));
        tracer.setCycleCount(25);
        tracer.setOnFinished(e -> RESULTS.add(trace.toString()));
        tracer.play();

        clickHamburger();
        PauseTransition sample = new PauseTransition(Duration.millis(130));
        sample.setOnFinished(e -> {
            Region shell = region("#sidebarShell");
            Region panel = region("#sidebarPanel");
            double w = shell.getWidth();
            check("MID-ANIM: sidebar is genuinely partway, not snapped (width=" + r(w) + ")",
                    w > 5 && w < SIDEBAR_WIDTH - 5);
            check("MID-ANIM: panel still full 260 wide — it slides, never reflows narrow "
                            + "(width=" + r(panel.getWidth()) + ", translateX="
                            + r(panel.getTranslateX()) + ")",
                    Math.abs(panel.getWidth() - SIDEBAR_WIDTH) < 1 && panel.getTranslateX() < 0);
            check("MID-ANIM: nothing from the sidebar paints right of the shell edge",
                    noSidebarPixelsRightOf(w));
            shoot("shell_4_mid_animation");
        });
        sample.play();
    }

    /** Scans the content-padding band just right of the shrinking shell for navy residue. */
    private boolean noSidebarPixelsRightOf(double shellWidth) {
        WritableImage image = stage.getScene().snapshot(null);
        PixelReader reader = image.getPixelReader();
        int y = (int) (sceneBounds(topBar()).getMaxY() + 8);
        for (int x = (int) shellWidth + 3; x < shellWidth + 200; x += 6) {
            javafx.scene.paint.Color c = reader.getColor(x, y);
            double brightness = (c.getRed() + c.getGreen() + c.getBlue()) / 3.0;
            if (brightness < 0.85) {
                RESULTS.add("   spill at x=" + x + " y=" + y + " -> " + c);
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ actions

    private void clickHamburger() {
        clickCentre(node("#hamburgerButton"));
    }

    private void navigate(String label) {
        Node target = null;
        for (Node n : region("#sidebar").lookupAll(".nav-button")) {
            if (n instanceof javafx.scene.control.Button b && label.equals(b.getText())) {
                target = b;
            }
        }
        if (target == null) {
            // sidebar hidden — navigate the way the button would, without the click
            for (javafx.scene.Node n : stage.getScene().getRoot().lookupAll(".nav-button")) {
                if (n instanceof javafx.scene.control.Button b && label.equals(b.getText())) {
                    b.fire();
                    return;
                }
            }
            fail("no nav button labelled " + label);
            return;
        }
        clickCentre(target);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Samples the strip the sidebar used to occupy; it must be the light content
     * background (#f4f6fb), with no navy left anywhere in it.
     *
     * <p>Sampled inside .content-page's 22px padding, just below the top bar, because
     * that band is guaranteed to be empty background on every page — sampling mid-page
     * hits chart and table pixels instead.</p>
     */
    private boolean leftStripIsContentBackground() {
        WritableImage image = stage.getScene().snapshot(null);
        PixelReader reader = image.getPixelReader();
        int y = (int) (sceneBounds(topBar()).getMaxY() + 8);
        for (int x = 2; x < 250; x += 8) {
            javafx.scene.paint.Color c = reader.getColor(x, y);
            double brightness = (c.getRed() + c.getGreen() + c.getBlue()) / 3.0;
            boolean navyish = brightness < 0.5 && c.getBlue() > c.getRed();
            if (navyish || brightness < 0.85) {
                RESULTS.add("   non-background pixel at x=" + x + " y=" + y + " -> " + c);
                return false;
            }
        }
        return true;
    }

    private List<String> menuLabels() {
        List<String> labels = new ArrayList<>();
        for (Node n : region("#sidebarPanel").lookupAll(".label")) {
            if (n instanceof javafx.scene.control.Label l && !l.getText().isBlank()) {
                labels.add(l.getText());
            }
        }
        for (Node n : region("#sidebarPanel").lookupAll(".nav-button")) {
            if (n instanceof javafx.scene.control.Button b) {
                labels.add(b.getText());
            }
        }
        return labels;
    }

    private int countImages(Parent p) {
        return p.lookupAll(".image-view").size();
    }

    private Node node(String selector) {
        return stage.getScene().getRoot().lookup(selector);
    }

    private Region region(String selector) {
        return (Region) node(selector);
    }

    private Region topBar() {
        return (Region) stage.getScene().getRoot().lookup(".top-bar");
    }

    private Bounds sceneBounds(Node n) {
        return n.localToScene(n.getBoundsInLocal());
    }

    private void clickCentre(Node node) {
        Bounds b = node.localToScreen(node.getBoundsInLocal());
        robot.mouseMove(b.getMinX() + b.getWidth() / 2, b.getMinY() + b.getHeight() / 2);
        robot.mousePress(MouseButton.PRIMARY);
        robot.mouseRelease(MouseButton.PRIMARY);
    }

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

    private void finish() {
        System.out.println();
        System.out.println("============== ADMIN SHELL LAYOUT PROBE ==============");
        RESULTS.forEach(System.out::println);
        System.out.println("======================================================");
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
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

    private static double r(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
