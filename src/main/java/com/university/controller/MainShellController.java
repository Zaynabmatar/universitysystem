package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.AuthService;
import com.university.service.NotificationService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
    @FXML private StackPane contentArea;
    @FXML private Label userLabel;
    @FXML private Label semesterLabel;
    @FXML private Button notificationsButton;
    @FXML private Label unreadBadge;
    @FXML private Button logoutButton;

    private final AuthService authService = new AuthService();
    private final NotificationService notificationService = new NotificationService();
    private Button activeButton;
    private Timeline bellRefresh;

    /** One sidebar entry: the text the user sees and the FXML it opens. */
    private record MenuEntry(String label, String fxml) { }

    private static final List<MenuEntry> ADMIN_MENU = List.of(
            new MenuEntry("Dashboard",              "admin_dashboard.fxml"),
            new MenuEntry("Students",               "admin_students.fxml"),
            new MenuEntry("Instructors",            "admin_instructors.fxml"),
            new MenuEntry("Departments & Programs", "admin_programs.fxml"),
            new MenuEntry("Courses",                "admin_courses.fxml"),
            new MenuEntry("Prerequisites",          "admin_prerequisites.fxml"),
            new MenuEntry("Semesters",              "admin_semesters.fxml"),
            new MenuEntry("Sections",               "admin_sections.fxml"),
            new MenuEntry("Reports & Analytics",    "admin_reports.fxml"),
            new MenuEntry("Audit Log",              "admin_audit_log.fxml")
    );

    private static final List<MenuEntry> INSTRUCTOR_MENU = List.of(
            new MenuEntry("Dashboard",       "instructor_dashboard.fxml"),
            new MenuEntry("My Sections",     "instructor_sections.fxml"),
            new MenuEntry("Enter Grades",    "instructor_grades.fxml"),
            new MenuEntry("My Timetable",    "instructor_timetable.fxml")
    );

    private static final List<MenuEntry> STUDENT_MENU = List.of(
            new MenuEntry("Dashboard",              "student_dashboard.fxml"),
            new MenuEntry("Register for Courses",   "student_registration.fxml"),
            new MenuEntry("My Timetable",           "student_timetable.fxml"),
            new MenuEntry("My Grades",              "student_grades.fxml"),
            new MenuEntry("Transcript",             "student_transcript.fxml"),
            new MenuEntry("Degree Progress",        "student_progress.fxml"),
            new MenuEntry("Course Recommendation",  "student_recommendation.fxml")
    );

    @FXML
    private void initialize() {
        if (!Session.isActive()) {
            // Should be impossible, but never show a shell to nobody.
            SceneManager.getInstance().switchRoot("login.fxml", "University Registration System — Login");
            return;
        }
        Session session = Session.current();

        SceneManager.getInstance().registerContentArea(contentArea);

        userLabel.setText(session.getDisplayName() + "  (" + session.getRole().name() + ")");

        List<MenuEntry> menu = menuFor(session.getRole());
        buildSidebar(menu);

        // Role-based routing: land on the dashboard for this role.
        MenuEntry home = menu.get(0);
        SceneManager.getInstance().navigateTo(home.fxml(), home.label());

        initNotificationBell();
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

    private void buildSidebar(List<MenuEntry> menu) {
        sidebar.getChildren().clear();

        Label header = new Label("MENU");
        header.getStyleClass().add("sidebar-header");
        sidebar.getChildren().add(header);

        for (MenuEntry entry : menu) {
            Button b = new Button(entry.label());
            b.getStyleClass().add("nav-button");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> {
                setActive(b);
                SceneManager.getInstance().navigateTo(entry.fxml(), entry.label());
            });
            sidebar.getChildren().add(b);
            if (activeButton == null) {
                setActive(b);   // first item starts highlighted
            }
        }
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
    private void handleLogout() {
        if (!AlertUtil.confirm("Log out", "Are you sure you want to log out?")) {
            return;
        }
        if (bellRefresh != null) {
            bellRefresh.stop();
        }
        authService.logout();
        SceneManager.getInstance().switchRoot("login.fxml", "University Registration System — Login");
    }
}
