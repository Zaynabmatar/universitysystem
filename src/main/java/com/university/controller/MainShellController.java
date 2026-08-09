package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.AuthService;
import com.university.service.NotificationService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;

/**
 * The sidebar shell. The menu is built entirely from the logged-in user's role
 * (project_details.md Section 2), so a student can never even see an admin menu item.
 *
 * Menu items exist for EVERY screen in Section 7 from this phase onward. Screens whose FXML
 * has not been created yet show a "will be available in a later phase" placeholder — see
 * SceneManager.navigateTo(). Later phases only need to add the FXML file.
 */
public class MainShellController {

    @FXML private VBox sidebar;
    @FXML private StackPane sidebarShell;
    @FXML private VBox sidebarPanel;
    @FXML private Button hamburgerButton;
    @FXML private StackPane contentArea;
    @FXML private Label welcomeNameLabel;
    @FXML private Label semesterLabel;
    @FXML private Label studentIdLabel;
    @FXML private Button notificationsButton;
    @FXML private Label unreadBadge;
    @FXML private MenuButton avatarMenu;
    @FXML private Label menuNameLabel;
    @FXML private Label menuIdLabel;
    @FXML private MenuItem instructorDirectoryMenuItem;

    private static final double SIDEBAR_WIDTH = 260;
    private static final double SIDEBAR_ANIMATION_MILLIS = 270;

    private final AuthService authService = new AuthService();
    private final NotificationService notificationService = new NotificationService();
    private Button activeButton;
    private Timeline bellRefresh;
    private Timeline sidebarAnimation;
    private boolean sidebarCollapsed = false;

    /** One sidebar entry: the text the user sees, the FXML it opens, and an optional small icon. */
    private record MenuEntry(String label, String fxml, String iconKey) {
        private MenuEntry(String label, String fxml) {
            this(label, fxml, null);
        }
    }

    private static final List<MenuEntry> ADMIN_MENU = List.of(
            new MenuEntry("Dashboard",              "admin_dashboard.fxml",     "dashboard"),
            new MenuEntry("Students",               "admin_students.fxml",      "students"),
            new MenuEntry("Instructors",            "admin_instructors.fxml",   "instructor"),
            new MenuEntry("Departments & Programs", "admin_programs.fxml",      "programs"),
            new MenuEntry("Courses",                "admin_courses.fxml",       "courses"),
            new MenuEntry("Prerequisites",          "admin_prerequisites.fxml", "prerequisites"),
            new MenuEntry("Semesters",              "admin_semesters.fxml",     "calendar"),
            new MenuEntry("Sections",               "admin_sections.fxml",      "sections"),
            new MenuEntry("Reports & Analytics",    "admin_reports.fxml",       "reports"),
            new MenuEntry("Audit Log",              "admin_audit_log.fxml",     "auditlog")
    );

    private static final List<MenuEntry> INSTRUCTOR_MENU = List.of(
            new MenuEntry("Dashboard",       "instructor_dashboard.fxml",   "dashboard"),
            new MenuEntry("My Sections",     "instructor_sections.fxml",    "courses"),
            new MenuEntry("Enter Grades",    "instructor_grades.fxml",      "grades"),
            new MenuEntry("My Timetable",    "instructor_timetable.fxml",   "calendar")
    );

    /**
     * The Student menu (reference: reference image supplied 2026-08-07). "Classes" is the
     * repurposed former Dashboard screen; My Grades and My Timetable intentionally have no entry
     * here any more (their pages still exist, just unreachable from the sidebar or Classes page).
     */
    private static final List<MenuEntry> STUDENT_MENU = List.of(
            new MenuEntry("Classes",               "student_dashboard.fxml",      "classes"),
            new MenuEntry("Payments",              "student_payments.fxml",       "payments"),
            new MenuEntry("Registration",          "student_registration.fxml",   "registration"),
            new MenuEntry("Transcript",            "student_transcript.fxml",     "transcript"),
            new MenuEntry("Plan of Study",         "student_progress.fxml",       "plan"),
            new MenuEntry("Course Recommendation", "student_recommendation.fxml", "recommendation")
    );

    @FXML
    private void initialize() {
        if (!Session.isActive()) {
            // Should be impossible, but never show a shell to nobody.
            SceneManager.getInstance().switchRoot("role_selection.fxml", "University Registration System — Select Role");
            return;
        }
        Session session = Session.current();

        SceneManager.getInstance().registerContentArea(contentArea);
        clipSidebarShell();

        welcomeNameLabel.setText(session.getDisplayName());

        if (session.getStudent() != null) {
            studentIdLabel.setText("ID: " + session.getUser().getUserId());
            studentIdLabel.setVisible(true);
            studentIdLabel.setManaged(true);
        }

        // Unlike studentIdLabel above, the dropdown header shows the id for every role.
        menuNameLabel.setText(session.getDisplayName());
        menuIdLabel.setText("ID: " + session.getUser().getUserId());

        // Instructors already see their colleagues via other screens; the directory entry
        // in the account dropdown is student/admin-only.
        if (session.getRole() == UserRole.INSTRUCTOR) {
            avatarMenu.getItems().remove(instructorDirectoryMenuItem);
        }

        List<MenuEntry> menu = menuFor(session.getRole());
        buildSidebar(menu, session.getRole());

        // Role-based routing: land on the dashboard for this role.
        MenuEntry home = menu.get(0);
        SceneManager.getInstance().navigateTo(home.fxml(), home.label());

        initNotificationBell();
    }

    /**
     * Clips the sidebar to its own bounds. The panel inside stays 260px wide while the
     * shell shrinks, so without this the logo and menu buttons would paint straight over
     * the dashboard for the length of the animation.
     */
    private void clipSidebarShell() {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(sidebarShell.widthProperty());
        clip.heightProperty().bind(sidebarShell.heightProperty());
        sidebarShell.setClip(clip);
    }

    /** The bell is visible for all three roles — admins and instructors get notifications too. */
    private void initNotificationBell() {
        refreshBell();
        // Polls so a promotion made in another window (or by another user) shows up without
        // the student having to navigate away and back.
        bellRefresh = new Timeline(new KeyFrame(Duration.seconds(30), e -> refreshBell()));
        bellRefresh.setCycleCount(Animation.INDEFINITE);
        bellRefresh.play();
    }

    /** Public so any screen can call it right after an action that creates a notification. */
    public void refreshBell() {
        try {
            int unread = notificationService.unreadCount(Session.current().getUser().getUserId());
            boolean show = unread > 0;
            unreadBadge.setText(unread > 99 ? "99+" : String.valueOf(unread));
            unreadBadge.setVisible(show);
            unreadBadge.setManaged(show);
        } catch (RuntimeException e) {
            unreadBadge.setVisible(false);
            unreadBadge.setManaged(false);
        }
    }

    private List<MenuEntry> menuFor(UserRole role) {
        return switch (role) {
            case ADMIN      -> ADMIN_MENU;
            case INSTRUCTOR -> INSTRUCTOR_MENU;
            case STUDENT    -> STUDENT_MENU;
        };
    }

    /** The student sidebar drops the "MENU" heading; admin and instructor keep it unchanged. */
    private void buildSidebar(List<MenuEntry> menu, UserRole role) {
        sidebar.getChildren().clear();

        if (role != UserRole.STUDENT) {
            Label header = new Label("MENU");
            header.getStyleClass().add("sidebar-header");
            sidebar.getChildren().add(header);
        }

        for (MenuEntry entry : menu) {
            Button b = new Button(entry.label());
            b.getStyleClass().add("nav-button");
            b.setMaxWidth(Double.MAX_VALUE);
            if (entry.iconKey() != null) {
                b.setGraphic(navIcon(entry.iconKey()));
                b.setGraphicTextGap(10);
            }
            b.setOnAction(e -> {
                setActive(b);
                SceneManager.getInstance().navigateTo(entry.fxml(), entry.label());
            });
            sidebar.getChildren().add(b);
            if (activeButton == null) {
                setActive(b);   // first item starts highlighted
            }
        }
        // Log out lives only in the avatar dropdown now (#handleLogout) — not here.
    }

    /**
     * A small (16x16) hand-built glyph for one student menu item — plain shapes rather than an
     * image asset or an SVG path string, so no new binary is added and there is no path syntax to
     * get wrong. Every shape uses the same translucent white so it reads correctly against the
     * navy sidebar in both the resting and the ".active" state.
     */
    static Node navIcon(String key) {
        Color ink = Color.rgb(255, 255, 255, 0.85);
        Group shape = new Group();
        switch (key) {
            case "classes" -> {
                Polygon cap = new Polygon(8, 2, 15, 6, 8, 10, 1, 6);
                cap.setFill(ink);
                Rectangle band = new Rectangle(5, 9, 6, 3);
                band.setArcWidth(2);
                band.setArcHeight(2);
                band.setFill(ink);
                Line tassel = new Line(13.2, 7, 13.2, 13);
                tassel.setStroke(ink);
                tassel.setStrokeWidth(1.3);
                Circle knot = new Circle(13.2, 13, 1.1, ink);
                shape.getChildren().addAll(cap, band, tassel, knot);
            }
            case "payments" -> {
                Rectangle card = new Rectangle(1, 3, 14, 10);
                card.setArcWidth(3);
                card.setArcHeight(3);
                card.setFill(ink);
                Rectangle stripe = new Rectangle(1, 6, 14, 2);
                stripe.setFill(Color.rgb(8, 39, 102));
                shape.getChildren().addAll(card, stripe);
            }
            case "registration" -> {
                Rectangle page = outlinedRect(3, 1, 10, 14, ink);
                Polyline check = new Polyline(5.3, 8, 7.3, 10.3, 11, 5.3);
                check.setStroke(ink);
                check.setStrokeWidth(1.5);
                shape.getChildren().addAll(page, check);
            }
            case "transcript" -> {
                Rectangle page = outlinedRect(3, 1, 10, 14, ink);
                shape.getChildren().addAll(page, line(5, 5, 11, 5, ink), line(5, 8, 11, 8, ink),
                        line(5, 11, 9, 11, ink));
            }
            case "plan" -> {
                Rectangle cover = outlinedRect(2, 3, 12, 10, ink);
                shape.getChildren().addAll(cover, line(8, 3, 8, 13, ink));
            }
            case "online" -> {
                Circle globe = new Circle(8, 8, 6.2);
                globe.setFill(Color.TRANSPARENT);
                globe.setStroke(ink);
                globe.setStrokeWidth(1.2);
                Ellipse meridian = new Ellipse(8, 8, 2.6, 6.2);
                meridian.setFill(Color.TRANSPARENT);
                meridian.setStroke(ink);
                meridian.setStrokeWidth(1.0);
                shape.getChildren().addAll(globe, meridian, line(1.8, 8, 14.2, 8, ink));
            }
            case "calendar" -> {
                Rectangle body = outlinedRect(1.5, 3, 13, 11, ink);
                shape.getChildren().addAll(body, line(1.5, 6, 14.5, 6, ink),
                        line(4.5, 1.2, 4.5, 4.2, ink), line(11.5, 1.2, 11.5, 4.2, ink));
            }
            case "recommendation" -> {
                Polygon star = new Polygon(
                        8, 1.5,   9.47, 5.98,  14.18, 5.99,
                        10.38, 8.77,  11.82, 13.26,  8, 10.5,
                        4.18, 13.26,  5.62, 8.77,  1.82, 5.99,  6.53, 5.98);
                star.setFill(ink);
                shape.getChildren().add(star);
            }
            case "dashboard" -> {
                shape.getChildren().addAll(square(1, 1, 6, ink), square(9, 1, 6, ink),
                        square(1, 9, 6, ink), square(9, 9, 6, ink));
            }
            case "students" -> {
                Polygon body1 = new Polygon(2, 14, 2.6, 9.6, 8.4, 9.6, 9, 14);
                body1.setFill(ink);
                Circle head1 = new Circle(5.5, 5.2, 2.2, ink);
                Polygon body2 = new Polygon(7.6, 14.5, 8.1, 10.4, 13.4, 10.4, 13.9, 14.5);
                body2.setFill(ink);
                Circle head2 = new Circle(10.7, 6.3, 2, ink);
                shape.getChildren().addAll(body2, head2, body1, head1);
            }
            case "instructor" -> {
                Polygon body = new Polygon(3, 14, 3.8, 8.3, 12.2, 8.3, 13, 14);
                body.setFill(ink);
                Circle head = new Circle(8, 4.5, 2.6, ink);
                shape.getChildren().addAll(body, head);
            }
            case "library", "programs" -> {
                Polygon roof = new Polygon(8, 1, 14.5, 5, 1.5, 5);
                roof.setFill(ink);
                shape.getChildren().addAll(roof, line(1.5, 14, 14.5, 14, ink),
                        line(3.5, 6, 3.5, 13, ink), line(8, 6, 8, 13, ink), line(12.5, 6, 12.5, 13, ink));
            }
            case "courses" -> {
                Polyline leftPage = new Polyline(1.5, 3.2, 8, 4.6, 8, 13, 1.5, 11.6, 1.5, 3.2);
                leftPage.setStroke(ink);
                leftPage.setStrokeWidth(1.2);
                Polyline rightPage = new Polyline(14.5, 3.2, 8, 4.6, 8, 13, 14.5, 11.6, 14.5, 3.2);
                rightPage.setStroke(ink);
                rightPage.setStrokeWidth(1.2);
                shape.getChildren().addAll(leftPage, rightPage);
            }
            case "prerequisites" -> {
                Circle c1 = new Circle(3, 3.2, 1.8, ink);
                Circle c2 = new Circle(13, 3.2, 1.8, ink);
                Circle c3 = new Circle(8, 13, 1.8, ink);
                shape.getChildren().addAll(line(3, 3.2, 8, 13, ink), line(13, 3.2, 8, 13, ink), c1, c2, c3);
            }
            case "sections" -> {
                shape.getChildren().addAll(bar(1, 2, 14, ink), bar(1, 6.9, 14, ink), bar(1, 11.8, 9, ink));
            }
            case "reports" -> {
                shape.getChildren().addAll(square2(2, 9, 3, 5, ink), square2(6.5, 5, 3, 9, ink),
                        square2(11, 2, 3, 12, ink));
            }
            case "auditlog" -> {
                Polygon shield = new Polygon(8, 1, 14, 3.5, 14, 8, 8, 15, 2, 8, 2, 3.5);
                shield.setFill(Color.TRANSPARENT);
                shield.setStroke(ink);
                shield.setStrokeWidth(1.3);
                Polyline check = new Polyline(5, 8, 7, 10, 11, 5.5);
                check.setStroke(ink);
                check.setStrokeWidth(1.4);
                shape.getChildren().addAll(shield, check);
            }
            case "grades" -> {
                Rectangle board = outlinedRect(2, 2, 12, 12, ink);
                Rectangle tab = new Rectangle(6, 0.5, 4, 2);
                tab.setArcWidth(1);
                tab.setArcHeight(1);
                tab.setFill(ink);
                Polyline check = new Polyline(4.5, 8, 7, 10.5, 11.5, 5.5);
                check.setStroke(ink);
                check.setStrokeWidth(1.4);
                shape.getChildren().addAll(board, tab, check);
            }
            default -> { }
        }
        StackPane holder = new StackPane(shape);
        holder.setPrefSize(16, 16);
        holder.setMinSize(16, 16);
        holder.setMaxSize(16, 16);
        return holder;
    }

    private static Rectangle outlinedRect(double x, double y, double w, double h, Color ink) {
        Rectangle r = new Rectangle(x, y, w, h);
        r.setArcWidth(2);
        r.setArcHeight(2);
        r.setFill(Color.TRANSPARENT);
        r.setStroke(ink);
        r.setStrokeWidth(1.3);
        return r;
    }

    private static Line line(double x1, double y1, double x2, double y2, Color ink) {
        Line l = new Line(x1, y1, x2, y2);
        l.setStroke(ink);
        l.setStrokeWidth(1.1);
        return l;
    }

    private static Rectangle square(double x, double y, double side, Color ink) {
        return square2(x, y, side, side, ink);
    }

    private static Rectangle square2(double x, double y, double w, double h, Color ink) {
        Rectangle r = new Rectangle(x, y, w, h);
        r.setArcWidth(1.5);
        r.setArcHeight(1.5);
        r.setFill(ink);
        return r;
    }

    private static Rectangle bar(double x, double y, double w, Color ink) {
        Rectangle r = new Rectangle(x, y, w, 2.2);
        r.setArcWidth(1);
        r.setArcHeight(1);
        r.setFill(ink);
        return r;
    }

    private void setActive(Button b) {
        if (activeButton != null) {
            activeButton.getStyleClass().remove("active");
        }
        activeButton = b;
        if (!b.getStyleClass().contains("active")) {
            b.getStyleClass().add("active");
        }
    }

    /**
     * Two states only — full 260px sidebar, or nothing at all. There is no narrow
     * icon-rail in between.
     *
     * <p>Two properties move together: the shell's width (which is what the
     * BorderPane measures, so the top bar and content reclaim the space) and the
     * panel's translateX (so the logo and menu slide out bodily instead of
     * reflowing into a narrow layout). The shell's clip, installed in
     * {@link #initialize()}, keeps the sliding panel from ever painting over the
     * content area.</p>
     */
    @FXML
    private void handleToggleSidebar() {
        if (sidebarAnimation != null) {
            sidebarAnimation.stop();
        }
        boolean opening = sidebarCollapsed;
        double targetWidth = opening ? SIDEBAR_WIDTH : 0;
        double targetShift = opening ? 0 : -SIDEBAR_WIDTH;

        if (opening) {
            sidebarPanel.setVisible(true);   // before the slide, so it is seen coming in
        }

        sidebarAnimation = new Timeline(new KeyFrame(Duration.millis(SIDEBAR_ANIMATION_MILLIS),
                new KeyValue(sidebarShell.prefWidthProperty(), targetWidth, Interpolator.EASE_BOTH),
                new KeyValue(sidebarShell.minWidthProperty(), targetWidth, Interpolator.EASE_BOTH),
                new KeyValue(sidebarShell.maxWidthProperty(), targetWidth, Interpolator.EASE_BOTH),
                new KeyValue(sidebarPanel.translateXProperty(), targetShift, Interpolator.EASE_BOTH)));

        // Width 0 plus the clip already hides everything; dropping visibility as well
        // guarantees no stray pixel, focus target or hit area survives in the closed state.
        sidebarAnimation.setOnFinished(e -> sidebarPanel.setVisible(opening));

        sidebarAnimation.play();
        sidebarCollapsed = !sidebarCollapsed;
    }

    @FXML
    private void handleNotifications() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(SceneManager.FXML_DIR + "notifications.fxml"));
            Parent root = loader.load();
            NotificationsController controller = loader.getController();
            controller.setOnCloseCallback(this::refreshBell);

            Stage popup = new Stage();
            popup.initOwner(notificationsButton.getScene().getWindow());
            popup.initModality(Modality.WINDOW_MODAL);
            popup.setTitle("Notifications");
            Scene scene = new Scene(root);
            SceneManager.getInstance().applyStylesheet(scene);
            popup.setScene(scene);
            popup.showAndWait();

            refreshBell();
        } catch (Exception e) {
            AlertUtil.error("Notifications", "The notifications window could not be opened.", e);
        }
    }

    @FXML
    private void handleOpenChangePassword() {
        openAccountPage("change_password.fxml", "Change Password");
    }

    @FXML
    private void handleOpenMyEmail() {
        openAccountPage("my_email.fxml", "My Email");
    }

    @FXML
    private void handleOpenChangeAddress() {
        openAccountPage("change_address.fxml", "Change Address");
    }

    @FXML
    private void handleOpenInstructorDirectory() {
        openAccountPage("instructor_directory.fxml", "List of Instructors");
    }

    /**
     * Opens one of the four account-settings pages in the content area, and
     * tells it where its own back arrow should return to — SceneManager keeps
     * no navigation history, so the page currently on screen is captured here,
     * right before it is replaced.
     */
    private void openAccountPage(String fxml, String title) {
        String returnFxml = SceneManager.getInstance().getCurrentViewFxml();
        String returnTitle = SceneManager.getInstance().getCurrentViewTitle();
        Object controller = SceneManager.getInstance().navigateTo(fxml, title);
        if (controller instanceof ReturnNavigable navigable) {
            navigable.setReturnTarget(returnFxml, returnTitle);
        }
    }

    @FXML
    private void handleLogout() {
        if (!AlertUtil.confirm("Log out", "Are you sure you want to log out?")) {
            return;
        }
        if (bellRefresh != null) {
            bellRefresh.stop();
        }
        authService.logout();
        SceneManager.getInstance().switchRoot("role_selection.fxml", "University Registration System — Select Role");
    }
}
