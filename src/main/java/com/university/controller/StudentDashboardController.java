package com.university.controller;

import com.university.enums.DayOfWeekCode;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.Enrollment;
import com.university.model.Notification;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.service.AcademicService;
import com.university.service.CourseService;
import com.university.service.NotificationService;
import com.university.service.RegistrationService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.Session;
import com.university.service.TranscriptService;
import com.university.service.TranscriptService.DegreeProgress;
import com.university.util.AlertUtil;
import com.university.util.GradeCalculator;
import com.university.util.SceneManager;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The screen a student lands on after logging in. It answers four questions in one glance:
 * how am I doing, how far along am I, what is next, and is anything waiting for me?
 *
 * <p>The landing itself is Phase 05's routing — {@code MainShellController} navigates to the
 * first item of the student menu, which is this screen. No second router exists.</p>
 */
public class StudentDashboardController {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM HH:mm");
    private static final int MAX_NEXT_CLASSES = 5;
    private static final int MAX_NOTIFICATIONS = 5;

    @FXML private Label       welcomeLabel;
    @FXML private Label       contextLabel;
    @FXML private Label       gpaLabel;
    @FXML private Label       standingLabel;
    @FXML private Label       creditsLabel;
    @FXML private Label       unreadLabel;
    @FXML private Label       progressPercentLabel;
    @FXML private ProgressBar miniProgressBar;
    @FXML private ListView<String>       nextClassesList;
    @FXML private ListView<Notification> notificationsList;

    private final TranscriptService transcriptService = new TranscriptService();
    private final AcademicService academicService = new AcademicService();
    private final NotificationService notificationService = new NotificationService();
    private final RegistrationService registrationService = new RegistrationService();
    private final SemesterService semesterService = new SemesterService();
    private final SectionService sectionService = new SectionService();
    private final CourseService courseService = new CourseService();

    private int studentId;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        studentId = Session.current().requireStudentId();

        welcomeLabel.setText("Welcome, " + Session.current().getDisplayName() + ".");
        configureNotificationCells();
        load();
    }

    private void load() {
        loadHeaderAndProgress();
        loadNextClasses();
        loadNotifications();
    }

    /** The four KPI cards and the mini progress bar — the same figures the other two screens show. */
    private void loadHeaderAndProgress() {
        try {
            DegreeProgress progress = transcriptService.getDegreeProgress(studentId);
            Student student = academicService.academicRecordOf(studentId);

            contextLabel.setText(student.getStudentNumber() + "  •  " + progress.programName);
            gpaLabel.setText(GradeCalculator.formatGpa(progress.cumulativeGpa));
            creditsLabel.setText(progress.creditsCompleted + " / " + progress.creditsRequired);

            String standing = student.getAcademicStanding() == null
                    ? "—" : student.getAcademicStanding().name();
            standingLabel.setText(standing);
            standingLabel.getStyleClass().removeAll("standing-good", "standing-bad");
            standingLabel.getStyleClass().add(
                    "PROBATION".equals(standing) || "SUSPENDED".equals(standing)
                            ? "standing-bad" : "standing-good");

            progressPercentLabel.setText(progress.percentText());
            miniProgressBar.setProgress(progress.progressFraction());
        } catch (RuntimeException e) {
            AlertUtil.error("Dashboard", "Your academic summary could not be loaded.", e);
        }
    }

    /**
     * The next five meetings, starting from today and wrapping around the week.
     *
     * <p>Built from the same services the timetable screen uses, so the dashboard can never
     * show a class the timetable does not.</p>
     */
    private void loadNextClasses() {
        try {
            nextClassesList.getItems().clear();

            Semester semester = semesterService.getCurrentSemester();
            if (semester == null) {
                nextClassesList.setPlaceholder(new Label("No semester is open yet."));
                return;
            }

            List<Enrollment> mine =
                    registrationService.currentRegistrations(studentId, semester.getSemesterId());
            List<Section> sections = mine.stream()
                    .map(enrollment -> sectionService.findById(enrollment.getSectionId()))
                    .filter(Objects::nonNull)
                    .toList();

            if (sections.isEmpty()) {
                nextClassesList.setPlaceholder(
                        new Label("No classes yet — register to see your timetable."));
                return;
            }

            Map<Integer, Course> coursesById = courseService.listCourses(false).stream()
                    .collect(Collectors.toMap(Course::getCourseId, course -> course, (a, b) -> a));

            List<Meeting> meetings = new ArrayList<>();
            for (Section section : sections) {
                Course course = coursesById.get(section.getCourseId());
                for (SectionSchedule schedule : sectionService.listMeetings(section.getSectionId())) {
                    meetings.add(new Meeting(section, course, schedule));
                }
            }

            int todayOrder = DayOfWeekCode.fromJavaDayOfWeek(LocalDate.now().getDayOfWeek()).ordinal();
            meetings.sort((left, right) -> {
                int a = wrappedOrder(left, todayOrder);
                int b = wrappedOrder(right, todayOrder);
                return a != b ? Integer.compare(a, b)
                              : left.schedule.getStartTime().compareTo(right.schedule.getStartTime());
            });

            meetings.stream()
                    .limit(MAX_NEXT_CLASSES)
                    .map(Meeting::describe)
                    .forEach(nextClassesList.getItems()::add);

        } catch (RuntimeException e) {
            nextClassesList.setPlaceholder(new Label("Your classes could not be loaded."));
        }
    }

    /** Days from today, so today's classes come first and the week wraps around. */
    private int wrappedOrder(Meeting meeting, int todayOrder) {
        int day = meeting.schedule.getDayOfWeek().ordinal();
        return (day - todayOrder + DayOfWeekCode.values().length) % DayOfWeekCode.values().length;
    }

    /** One weekly meeting, with everything the dashboard line needs. */
    private static final class Meeting {
        private final Section section;
        private final Course course;
        private final SectionSchedule schedule;

        private Meeting(Section section, Course course, SectionSchedule schedule) {
            this.section = section;
            this.course = course;
            this.schedule = schedule;
        }

        /** {@code MON 09:00–10:30    CS301-01    Database Systems    Room B-204} */
        private String describe() {
            String code = course == null ? "—" : course.getCourseCode();
            String title = course == null ? "" : course.getCourseTitle();
            String room = section.getRoom() == null || section.getRoom().isBlank()
                    ? "" : "    Room " + section.getRoom();
            return schedule.getDayOfWeek().name() + " "
                 + schedule.getStartTime().format(HM) + "–" + schedule.getEndTime().format(HM)
                 + "    " + code + "-" + section.getSectionNumber()
                 + "    " + title + room;
        }
    }

    private void loadNotifications() {
        try {
            int userId = Session.current().getUser().getUserId();
            unreadLabel.setText(String.valueOf(notificationService.unreadCount(userId)));

            List<Notification> latest = notificationService.latest(userId, MAX_NOTIFICATIONS);
            notificationsList.getItems().setAll(latest);
            if (latest.isEmpty()) {
                notificationsList.setPlaceholder(new Label("You have no notifications yet."));
            }
        } catch (RuntimeException e) {
            unreadLabel.setText("0");
            notificationsList.setPlaceholder(new Label("Your notifications could not be loaded."));
        }
    }

    /** Unread notifications are visually distinct — the same convention as the bell popup. */
    private void configureNotificationCells() {
        notificationsList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Notification item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("notif-title-unread", "notif-title-read");
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String when = item.getCreatedAt() == null ? "" : "  ·  " + item.getCreatedAt().format(STAMP);
                setText(item.getTitle() + when);
                getStyleClass().add(item.isRead() ? "notif-title-read" : "notif-title-unread");
            }
        });
    }

    /* =================== quick actions — all through the Phase 05 SceneManager ============ */

    @FXML
    private void handleOpenRegistration() {
        SceneManager.getInstance().navigateTo("student_registration.fxml", "Register for Courses");
    }

    @FXML
    private void handleOpenRecommendations() {
        SceneManager.getInstance().navigateTo("student_recommendation.fxml", "Course Recommendation");
    }

    @FXML
    private void handleOpenTimetable() {
        SceneManager.getInstance().navigateTo("student_timetable.fxml", "My Timetable");
    }

    @FXML
    private void handleOpenGrades() {
        SceneManager.getInstance().navigateTo("student_grades.fxml", "My Grades");
    }

    @FXML
    private void handleOpenTranscript() {
        SceneManager.getInstance().navigateTo("student_transcript.fxml", "Transcript");
    }

    @FXML
    private void handleOpenProgress() {
        SceneManager.getInstance().navigateTo("student_progress.fxml", "Degree Progress");
    }

    /** Opens the same modal the top-bar bell opens, so there is one notifications window. */
    @FXML
    private void handleOpenNotifications() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource(SceneManager.FXML_DIR + "notifications.fxml"));
            Parent root = loader.load();

            Stage popup = new Stage();
            popup.initOwner(notificationsList.getScene().getWindow());
            popup.initModality(Modality.WINDOW_MODAL);
            popup.setTitle("Notifications");
            Scene scene = new Scene(root);
            SceneManager.getInstance().applyStylesheet(scene);
            popup.setScene(scene);
            popup.showAndWait();

            loadNotifications();   // the unread count may have changed while it was open
        } catch (Exception e) {
            AlertUtil.error("Notifications", "The notifications window could not be opened.", e);
        }
    }
}
