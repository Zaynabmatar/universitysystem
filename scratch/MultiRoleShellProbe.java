import com.university.enums.UserRole;
import com.university.service.AuthService;
import com.university.util.SceneManager;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs in as an admin, an instructor and a student in turn, and measures the shared
 * shell (sidebar + top bar) each time: geometry, colours, logo placement, hamburger and
 * welcome-text position, menu item spacing and the selected-item style. Every measured
 * value must be identical across the three roles; only the menu labels and the welcome
 * name may differ. Not part of the build. Read-only against the database.
 */
public class MultiRoleShellProbe extends Application {

    private static final List<String> RESULTS = new ArrayList<>();
    private static int failures = 0;

    private record Account(String role, UserRole userRole, String id) { }

    private static final List<Account> ACCOUNTS = List.of(
            new Account("ADMIN",      UserRole.ADMIN,      "1"),
            new Account("INSTRUCTOR", UserRole.INSTRUCTOR, "2"),
            new Account("STUDENT",    UserRole.STUDENT,    "4"));

    /** role -> (metric -> value). Compared across roles at the end. */
    private final Map<String, Map<String, String>> measured = new LinkedHashMap<>();
    private final Map<String, List<String>> menus = new LinkedHashMap<>();

    private Stage stage;
    private final List<Runnable> steps = new ArrayList<>();
    private int stepIndex = 0;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        setUserAgentStylesheet(STYLESHEET_MODENA);

        SceneManager.getInstance().init(primaryStage);
        primaryStage.show();
        primaryStage.setWidth(1200);
        primaryStage.setHeight(780);
        primaryStage.centerOnScreen();

        for (Account a : ACCOUNTS) {
            steps.add(() -> signIn(a));
            steps.add(() -> measure(a.role()));
        }
        steps.add(this::compareRoles);
        steps.add(this::finish);

        runNext();
    }

    private void runNext() {
        if (stepIndex >= steps.size()) {
            return;
        }
        Runnable step = steps.get(stepIndex++);
        PauseTransition pause = new PauseTransition(Duration.millis(900));
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

    private void signIn(Account a) {
        AuthService auth = new AuthService();
        try {
            auth.logout();
        } catch (RuntimeException ignored) {
            // no session yet on the first pass
        }
        auth.login(a.userRole(), a.id(), a.id() + "@iuL");
        SceneManager.getInstance().switchRoot("main_shell.fxml", "Probe — " + a.role());
        stage.setWidth(1200);
        stage.setHeight(780);
    }

    // ------------------------------------------------------------------ measuring

    private void measure(String role) {
        Map<String, String> m = new LinkedHashMap<>();

        Bounds sidebar = sceneBounds(region("#sidebarShell"));
        Bounds topBar = sceneBounds(topBar());
        Bounds content = sceneBounds(region("#contentArea"));
        double sceneW = stage.getScene().getWidth();
        double sceneH = stage.getScene().getHeight();

        m.put("sidebar.x", r(sidebar.getMinX()));
        m.put("sidebar.y", r(sidebar.getMinY()));
        m.put("sidebar.width", r(sidebar.getWidth()));
        m.put("sidebar.height", r(sidebar.getHeight()));
        m.put("sidebar.fullWindowHeight", String.valueOf(Math.abs(sidebar.getHeight() - sceneH) < 1));

        m.put("topBar.x", r(topBar.getMinX()));
        m.put("topBar.y", r(topBar.getMinY()));
        m.put("topBar.height", r(topBar.getHeight()));
        m.put("topBar.width", r(topBar.getWidth()));
        m.put("topBar.startsAtSidebarEdge", String.valueOf(Math.abs(topBar.getMinX() - sidebar.getMaxX()) < 1));
        m.put("content.x", r(content.getMinX()));
        m.put("content.y", r(content.getMinY()));

        // ---- colours, sampled from the live rendering
        WritableImage shot = stage.getScene().snapshot(null);
        PixelReader px = shot.getPixelReader();
        m.put("color.sidebar", hex(px.getColor(30, (int) sidebar.getHeight() - 120)));
        m.put("color.topBar", hex(px.getColor((int) topBar.getMinX() + 400, (int) (topBar.getHeight() / 2))));
        m.put("color.sidebarTopBarMatch", String.valueOf(
                hex(px.getColor(30, 20)).equals(hex(px.getColor((int) topBar.getMinX() + 400, 20)))));

        // ---- logo block: three images (crest, Arabic name, English name)
        List<ImageView> images = new ArrayList<>();
        for (Node n : region("#sidebarPanel").lookupAll(".image-view")) {
            if (n instanceof ImageView iv) {
                images.add(iv);
            }
        }
        images.sort((a, b) -> Double.compare(sceneBounds(a).getMinY(), sceneBounds(b).getMinY()));
        m.put("logo.count", String.valueOf(images.size()));
        for (int i = 0; i < images.size(); i++) {
            Bounds b = sceneBounds(images.get(i));
            m.put("logo[" + i + "].x", r(b.getMinX()));
            m.put("logo[" + i + "].y", r(b.getMinY()));
            m.put("logo[" + i + "].width", r(b.getWidth()));
            m.put("logo[" + i + "].height", r(b.getHeight()));
        }

        // ---- hamburger and welcome text
        Node hamburger = node("#hamburgerButton");
        Bounds hb = sceneBounds(hamburger);
        Node glyph = hamburger.lookup(".ikon");
        Bounds gb = sceneBounds(glyph);
        Label welcomeWord = welcomeWordLabel();
        Bounds wb = sceneBounds(welcomeWord);
        Bounds nb = sceneBounds(node("#welcomeNameLabel"));

        m.put("hamburger.x", r(hb.getMinX()));
        m.put("hamburger.y", r(hb.getMinY()));
        m.put("hamburger.width", r(hb.getWidth()));
        m.put("hamburger.height", r(hb.getHeight()));
        m.put("hamburgerGlyph.width", r(gb.getWidth()));
        m.put("hamburgerGlyph.height", r(gb.getHeight()));
        m.put("welcome.x", r(wb.getMinX()));
        m.put("welcome.y", r(wb.getMinY()));
        m.put("welcome.fontSize", r(welcomeWord.getFont().getSize()));
        m.put("welcome.fontName", welcomeWord.getFont().getName());
        m.put("welcome.textFill", welcomeWord.getTextFill().toString());
        // The number this change is about: button box edge -> "Welcome" text edge.
        m.put("gap.buttonBoxToText", r(wb.getMinX() - hb.getMaxX()));
        // What the eye actually sees: the last hamburger line -> the "W".
        m.put("gap.glyphToText", r(wb.getMinX() - gb.getMaxX()));
        m.put("welcomeName.x", r(nb.getMinX()));
        m.put("welcomeName.text", ((Label) node("#welcomeNameLabel")).getText());

        // ---- vertical centring inside the bar (alignment, not just position)
        m.put("hamburger.centreOffset", r((hb.getMinY() + hb.getMaxY()) / 2 - topBar.getHeight() / 2));
        m.put("welcome.centreOffset", r((wb.getMinY() + wb.getMaxY()) / 2 - topBar.getHeight() / 2));

        // ---- right-hand cluster
        Bounds bell = sceneBounds(node("#notificationsButton"));
        Bounds avatar = sceneBounds(node("#avatarMenu"));
        m.put("bell.rightInset", r(sceneW - bell.getMaxX()));
        m.put("avatar.rightInset", r(sceneW - avatar.getMaxX()));
        m.put("gap.bellToAvatar", r(avatar.getMinX() - bell.getMaxX()));

        // ---- menu items: geometry is shared, labels are role-specific
        List<Button> navButtons = new ArrayList<>();
        for (Node n : region("#sidebar").lookupAll(".nav-button")) {
            if (n instanceof Button b) {
                navButtons.add(b);
            }
        }
        navButtons.sort((a, b) -> Double.compare(sceneBounds(a).getMinY(), sceneBounds(b).getMinY()));

        List<String> labels = new ArrayList<>();
        navButtons.forEach(b -> labels.add(b.getText()));
        menus.put(role, labels);

        Button first = navButtons.get(0);
        Bounds fb = sceneBounds(first);
        Bounds sb2 = sceneBounds(navButtons.get(1));
        m.put("menu.firstItemX", r(fb.getMinX()));
        m.put("menu.firstItemY", r(fb.getMinY()));
        m.put("menu.itemWidth", r(fb.getWidth()));
        m.put("menu.itemHeight", r(fb.getHeight()));
        m.put("menu.itemSpacing", r(sb2.getMinY() - fb.getMaxY()));
        m.put("menu.fontSize", r(first.getFont().getSize()));
        m.put("menu.headerY", r(sceneBounds(menuHeader()).getMinY()));

        // ---- selected-item style: the first entry starts active for every role
        m.put("selected.isFirstItem", String.valueOf(first.getStyleClass().contains("active")));
        m.put("selected.text", first.getText());
        Bounds ab = sceneBounds(first);
        // Sample inside the highlight pill (inset 10px each side) but past the end of the
        // label text — sampling near minX lands on antialiased glyph pixels, which differ
        // per role simply because the words differ.
        m.put("selected.fillColor", hex(px.getColor((int) ab.getMaxX() - 20, (int) ((ab.getMinY() + ab.getMaxY()) / 2))));
        m.put("selected.fontWeight", first.getFont().getStyle());
        // An unselected sibling, to prove the active style is a real difference and not flat.
        Bounds ub = sceneBounds(navButtons.get(1));
        m.put("unselected.fillColor", hex(px.getColor((int) ub.getMaxX() - 20, (int) ((ub.getMinY() + ub.getMaxY()) / 2))));

        measured.put(role, m);
        shoot("role_shell_" + role.toLowerCase());
        RESULTS.add("MEASURED " + role + " — menu " + labels);
    }

    // ------------------------------------------------------------------ comparison

    private void compareRoles() {
        Map<String, String> admin = measured.get("ADMIN");
        if (admin == null) {
            fail("admin shell was never measured");
            return;
        }

        RESULTS.add("");
        RESULTS.add("---- hamburger / welcome gap (the requested adjustment) ----");
        for (String role : measured.keySet()) {
            RESULTS.add("   " + role + ": button-box gap = " + measured.get(role).get("gap.buttonBoxToText")
                    + "px, visible glyph-to-text gap = " + measured.get(role).get("gap.glyphToText") + "px");
        }
        double gap = Double.parseDouble(admin.get("gap.buttonBoxToText"));
        check("gap between hamburger button and welcome text is small and positive ("
                + gap + "px)", gap > 0 && gap <= 4);
        RESULTS.add("   top bar height = " + admin.get("topBar.height")
                + "px, hamburger height = " + admin.get("hamburger.height")
                + "px, welcome font = " + admin.get("welcome.fontSize") + "px");

        RESULTS.add("");
        RESULTS.add("---- every shared metric, across the three roles ----");
        for (String key : admin.keySet()) {
            if (key.equals("welcomeName.text") || key.equals("selected.text")) {
                continue;   // legitimately role-specific
            }
            StringBuilder line = new StringBuilder();
            boolean same = true;
            for (String role : measured.keySet()) {
                String v = measured.get(role).get(key);
                line.append(role.charAt(0)).append('=').append(v).append("  ");
                if (!admin.get(key).equals(v)) {
                    same = false;
                }
            }
            check(pad(key) + line, same);
        }

        RESULTS.add("");
        RESULTS.add("---- role-specific by design ----");
        for (String role : measured.keySet()) {
            RESULTS.add("   " + role + " welcome name = \"" + measured.get(role).get("welcomeName.text")
                    + "\", menu = " + menus.get(role));
        }
        check("the three roles do have different menus",
                !menus.get("ADMIN").equals(menus.get("INSTRUCTOR"))
                        && !menus.get("INSTRUCTOR").equals(menus.get("STUDENT")));
    }

    // ------------------------------------------------------------------ helpers

    private Label welcomeWordLabel() {
        for (Node n : topBar().lookupAll(".welcome-label")) {
            if (n instanceof Label l && "Welcome".equals(l.getText())) {
                return l;
            }
        }
        throw new IllegalStateException("no \"Welcome\" label in the top bar");
    }

    private Node menuHeader() {
        return region("#sidebar").lookup(".sidebar-header");
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

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X",
                Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
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
        System.out.println("========== SHARED SHELL — ADMIN / INSTRUCTOR / STUDENT ==========");
        RESULTS.forEach(System.out::println);
        System.out.println("================================================================");
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String pad(String s) {
        return s.length() >= 30 ? s + " " : s + " ".repeat(30 - s.length());
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

    private static String r(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }
}
