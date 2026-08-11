package com.university.controller;

import com.university.enums.DayOfWeekCode;
import com.university.enums.EnrollmentStatus;
import com.university.enums.UserRole;
import com.university.model.Campus;
import com.university.model.Course;
import com.university.model.Enrollment;
import com.university.model.Exam;
import com.university.model.Instructor;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.model.StudentGradeRow;
import com.university.service.AcademicService;
import com.university.service.AttendanceService;
import com.university.service.CourseService;
import com.university.service.ExamService;
import com.university.service.InstructorService;
import com.university.service.RegistrationService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;

import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The screen a student lands on after logging in: their registered courses for the current
 * semester and the exams scheduled for those sections.
 *
 * <p>Exams are read entirely through the student's own enrollments
 * ({@link ExamService#examsForStudent}, which joins {@code exams -> sections -> enrollments}), so
 * a student only ever sees an exam for a section they are actually enrolled in, and a new exam an
 * instructor creates appears here the next time this screen loads — no per-student copy is ever
 * written.</p>
 */
public class StudentDashboardController {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @FXML private Label semesterBannerLabel;

    @FXML private TableView<ClassRow> coursesTable;
    @FXML private TableColumn<ClassRow, String> colCourseCode;
    @FXML private TableColumn<ClassRow, String> colCourseName;
    @FXML private TableColumn<ClassRow, String> colSection;
    @FXML private TableColumn<ClassRow, String> colCampus;
    @FXML private TableColumn<ClassRow, String> colInstructor;
    @FXML private TableColumn<ClassRow, String> colCrn;
    @FXML private TableColumn<ClassRow, String> colSchedule;
    @FXML private TableColumn<ClassRow, String> colRoom;
    @FXML private TableColumn<ClassRow, String> colGrade;
    @FXML private TableColumn<ClassRow, String> colAbsences;
    @FXML private TableColumn<ClassRow, String> colStatus;

    @FXML private Label totalCoursesLabel;
    @FXML private Label totalCreditsLabel;

    @FXML private TableView<ExamRow> examsTable;
    @FXML private TableColumn<ExamRow, String> examColCourseCode;
    @FXML private TableColumn<ExamRow, String> examColCourseName;
    @FXML private TableColumn<ExamRow, String> examColDate;
    @FXML private TableColumn<ExamRow, String> examColTime;
    @FXML private TableColumn<ExamRow, String> examColDuration;
    @FXML private TableColumn<ExamRow, String> examColRoom;

    @FXML private TableView<TodayRow> todaysScheduleTable;
    @FXML private TableColumn<TodayRow, String> todayColTime;
    @FXML private TableColumn<TodayRow, String> todayColCourse;
    @FXML private TableColumn<TodayRow, String> todayColInstructor;
    @FXML private TableColumn<TodayRow, String> todayColRoom;

    @FXML private AIAssistantPanelController aiAssistantController;
    @FXML private Button aiAssistantToggleButton;
    @FXML private VBox   aiAssistantExpandedBox;

    private final RegistrationService registrationService = new RegistrationService();
    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();
    private final InstructorService instructorService = new InstructorService();
    private final AcademicService academicService = new AcademicService();
    private final ExamService examService = new ExamService();
    private final AttendanceService attendanceService = new AttendanceService();

    private final ObservableList<ClassRow> rows = FXCollections.observableArrayList();
    private final ObservableList<ExamRow> examRows = FXCollections.observableArrayList();
    private final ObservableList<TodayRow> todayRows = FXCollections.observableArrayList();
    private final Set<Integer> currentSectionIds = new HashSet<>();

    private List<Course> courses = List.of();
    private List<Instructor> instructors = List.of();
    private List<Campus> campuses = List.of();
    private int studentId;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        studentId = Session.current().requireStudentId();

        colCourseCode.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().courseCode));
        colCourseName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().courseName));
        colSection.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().section));
        colCampus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().campus));
        colInstructor.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().instructor));
        colCrn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().crn));
        colSchedule.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().schedule));
        colRoom.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().room));
        colGrade.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().grade));
        colAbsences.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().totalAbsences)));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status.getLabel()));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("badge-ok", "badge-warn", "badge-neutral");
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                setText(value);
                if ("Enrolled".equals(value) || "Completed".equals(value)) {
                    getStyleClass().add("badge-ok");
                } else if ("Withdrawn".equals(value)) {
                    getStyleClass().add("badge-warn");
                } else {
                    getStyleClass().add("badge-neutral");
                }
            }
        });
        coursesTable.setItems(rows);
        coursesTable.setPlaceholder(new Label("You are not registered in any course this semester."));
        coursesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        examColCourseCode.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().courseCode));
        examColCourseName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().courseName));
        examColDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().date));
        examColTime.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().time));
        examColDuration.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().duration));
        examColRoom.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().room));
        examsTable.setItems(examRows);
        examsTable.setPlaceholder(new Label("No exams have been scheduled yet."));
        examsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        todayColTime.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().time));
        todayColCourse.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().course));
        todayColInstructor.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().instructor));
        todayColRoom.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().room));
        todaysScheduleTable.setItems(todayRows);
        todaysScheduleTable.setPlaceholder(new Label("No classes scheduled for today."));
        todaysScheduleTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        bindHeightToContent(coursesTable, rows);
        bindHeightToContent(examsTable, examRows);
        bindHeightToContent(todaysScheduleTable, todayRows);

        reload();

        aiAssistantController.configure(
                "Hi! I'm the University AI Assistant. Ask me about registration, your classes, "
                        + "deadlines, or your GPA — or use one of the quick questions above.",
                List.of("When does registration open?", "What is my next class?",
                        "What is the Add/Drop deadline?", "Show my GPA"));
    }

    /**
     * Sizes a table to exactly fit its rows instead of stretching to fill the page, so it never
     * gets its own vertical scrollbar and the page below it moves up or down as rows are added
     * or removed. The surrounding page is what scrolls (see student_dashboard.fxml's ScrollPane).
     */
    private static void bindHeightToContent(TableView<?> table, ObservableList<?> items) {
        double rowHeight = 28;
        double headerHeight = 32;
        double emptyStateHeight = 70;
        table.setFixedCellSize(rowHeight);
        var height = Bindings.createDoubleBinding(
                () -> headerHeight + (items.isEmpty() ? emptyStateHeight : items.size() * rowHeight + 2),
                items);
        table.prefHeightProperty().bind(height);
        table.minHeightProperty().bind(height);
        table.maxHeightProperty().bind(height);
    }

    private void reload() {
        courses = courseService.listCourses(false);
        instructors = instructorService.listActive();
        campuses = sectionService.listCampuses();

        loadRegisteredCourses();
        loadExams();
        loadTodaysSchedule();
    }

    private void loadRegisteredCourses() {
        currentSectionIds.clear();

        Semester currentSemester = semesterService.getCurrentSemester();
        if (currentSemester == null) {
            semesterBannerLabel.setText("No current semester is set.");
            rows.clear();
            updateTotals();
            return;
        }
        semesterBannerLabel.setText("Semester: " + currentSemester.getSemesterName());

        try {
            Map<Integer, StudentGradeRow> gradeByEnrollment =
                    academicService.gradeRows(studentId, currentSemester.getSemesterId()).stream()
                            .collect(Collectors.toMap(StudentGradeRow::getEnrollmentId, r -> r, (a, b) -> a));

            List<Enrollment> enrollments =
                    registrationService.myClassesForSemester(studentId, currentSemester.getSemesterId());

            List<ClassRow> built = new ArrayList<>();
            for (Enrollment enrollment : enrollments) {
                Section section = sectionService.findById(enrollment.getSectionId());
                if (section == null) {
                    continue;
                }
                currentSectionIds.add(section.getSectionId());

                Course course = courseOf(section.getCourseId());
                StudentGradeRow gradeRow = gradeByEnrollment.get(enrollment.getEnrollmentId());
                String grade = (gradeRow != null && gradeRow.getLetterGrade() != null)
                        ? gradeRow.getLetterGrade().getLabel() : "--";

                built.add(new ClassRow(
                        course == null ? "—" : course.getCourseCode(),
                        course == null ? "—" : course.getCourseTitle(),
                        section.getSectionNumber(),
                        campusNameOf(section.getCampusId()),
                        instructorNameOf(section.getInstructorId()),
                        String.valueOf(section.getSectionId()),
                        scheduleTextOf(section.getSectionId()),
                        section.getRoom() == null || section.getRoom().isBlank() ? "—" : section.getRoom(),
                        grade,
                        attendanceService.countAbsencesForEnrollment(enrollment.getEnrollmentId()),
                        enrollment.getStatus(),
                        course == null ? 0 : course.getCredits()));
            }
            rows.setAll(built);
        } catch (RuntimeException e) {
            AlertUtil.error("Classes", "Your registered courses could not be loaded.", e);
        }
        updateTotals();
    }

    private void loadExams() {
        try {
            List<Exam> mine = examService.examsForStudent(studentId);
            List<ExamRow> built = mine.stream()
                    .filter(exam -> currentSectionIds.contains(exam.getSectionId()))
                    .map(this::toExamRow)
                    .toList();
            examRows.setAll(built);
        } catch (RuntimeException e) {
            examRows.clear();
        }
    }

    /**
     * The student's real classes meeting today, built from the same registered sections as
     * {@link #loadRegisteredCourses()} — never a hardcoded sample — filtered to whichever meetings
     * fall on today's weekday and sorted by start time.
     */
    private void loadTodaysSchedule() {
        try {
            DayOfWeekCode today = DayOfWeekCode.fromJavaDayOfWeek(LocalDate.now().getDayOfWeek());
            List<TodayRow> built = new ArrayList<>();
            for (int sectionId : currentSectionIds) {
                Section section = sectionService.findById(sectionId);
                if (section == null) {
                    continue;
                }
                Course course = courseOf(section.getCourseId());
                for (SectionSchedule meeting : sectionService.listMeetings(sectionId)) {
                    if (meeting.getDayOfWeek() != today) {
                        continue;
                    }
                    built.add(new TodayRow(
                            meeting.getStartTime(),
                            HM.format(meeting.getStartTime()) + " - " + HM.format(meeting.getEndTime()),
                            course == null ? "—" : course.getCourseCode() + " — " + course.getCourseTitle(),
                            instructorNameOf(section.getInstructorId()),
                            section.getRoom() == null || section.getRoom().isBlank() ? "—" : section.getRoom()));
                }
            }
            built.sort(Comparator.comparing(r -> r.startTime));
            todayRows.setAll(built);
        } catch (RuntimeException e) {
            todayRows.clear();
        }
    }

    private ExamRow toExamRow(Exam exam) {
        Section section = sectionService.findById(exam.getSectionId());
        Course course = section == null ? null : courseOf(section.getCourseId());
        String day = DayOfWeekCode.fromJavaDayOfWeek(exam.getExamDate().getDayOfWeek()).getLabel();
        int endMinutes = exam.getDurationMinutes();
        return new ExamRow(
                course == null ? "—" : course.getCourseCode(),
                course == null ? "—" : course.getCourseTitle(),
                MD.format(exam.getExamDate()),
                day,
                HM.format(exam.getStartTime()),
                durationText(endMinutes),
                exam.getRoom() == null || exam.getRoom().isBlank() ? "—" : exam.getRoom());
    }

    private String durationText(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        return hours > 0 ? String.format("%d:%02d", hours, rest) : rest + " min";
    }

    private void updateTotals() {
        totalCoursesLabel.setText("Total Courses: " + rows.size());
        int totalCredits = rows.stream().mapToInt(r -> r.credits).sum();
        totalCreditsLabel.setText("Total Credits: " + totalCredits);
    }

    // ------------------------------------------------------------------ lookups

    private Course courseOf(int courseId) {
        return courses.stream().filter(c -> c.getCourseId() == courseId).findFirst().orElse(null);
    }

    private String instructorNameOf(Integer instructorId) {
        if (instructorId == null) {
            return "TBA";
        }
        return instructors.stream().filter(i -> i.getInstructorId() == instructorId).findFirst()
                .map(Instructor::getFullName).orElse("TBA");
    }

    private String campusNameOf(int campusId) {
        return campuses.stream().filter(c -> c.getCampusId() == campusId).findFirst()
                .map(Campus::getCampusName).orElse("—");
    }

    private String scheduleTextOf(int sectionId) {
        List<SectionSchedule> meetings = sectionService.listMeetings(sectionId);
        if (meetings.isEmpty()) {
            return "No meetings set";
        }
        return meetings.stream()
                .map(m -> m.getDayOfWeek().name() + " " + HM.format(m.getStartTime()) + "-" + HM.format(m.getEndTime()))
                .collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ actions

    /** Every exam this student has, across every semester — not only the ones on this page. */
    @FXML
    private void handleViewAllExams() {
        List<Exam> all;
        try {
            all = examService.examsForStudent(studentId);
        } catch (RuntimeException e) {
            AlertUtil.error("Exams", "Your exams could not be loaded.", e);
            return;
        }

        TableView<ExamRow> table = new TableView<>();
        TableColumn<ExamRow, String> code = new TableColumn<>("Course Code");
        code.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().courseCode));
        TableColumn<ExamRow, String> name = new TableColumn<>("Course Name");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().courseName));
        name.setPrefWidth(170);
        TableColumn<ExamRow, String> date = new TableColumn<>("Exam Date");
        date.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().date));
        TableColumn<ExamRow, String> day = new TableColumn<>("Day");
        day.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().day));
        TableColumn<ExamRow, String> time = new TableColumn<>("Time");
        time.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().time));
        TableColumn<ExamRow, String> duration = new TableColumn<>("Duration");
        duration.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().duration));
        TableColumn<ExamRow, String> room = new TableColumn<>("Room");
        room.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().room));
        table.getColumns().setAll(List.of(code, name, date, day, time, duration, room));
        table.setItems(FXCollections.observableArrayList(all.stream().map(this::toExamRow).toList()));
        table.setPlaceholder(new Label("You have no exams scheduled."));
        table.setPrefSize(640, 360);
        table.setMaxWidth(Double.MAX_VALUE);
        table.setMaxHeight(Double.MAX_VALUE);
        GridPane.setHgrow(table, Priority.ALWAYS);
        GridPane.setVgrow(table, Priority.ALWAYS);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setResizable(true);
        dialog.initStyle(StageStyle.UNDECORATED);

        Label titleLabel = new Label("All Exams");
        titleLabel.getStyleClass().add("popup-title");
Button maximizeButton = new Button("□");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox windowHeader = new HBox(8, titleLabel, headerSpacer, maximizeButton);
        windowHeader.setStyle("-fx-padding: 10 12 10 12; -fx-alignment: CENTER_LEFT;");

        VBox dialogContent = new VBox(8, windowHeader,
                new Label("Every exam scheduled for a course you are enrolled or were enrolled in."),
                table);
        VBox.setVgrow(table, Priority.ALWAYS);

        dialog.getDialogPane().setContent(dialogContent);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        var css = getClass().getResource("/css/app.css");
        if (css != null) {
            dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        }

        dialog.setOnShown(e -> {
            Stage stage = (Stage) dialog.getDialogPane().getScene().getWindow();
maximizeButton.setOnAction(a -> stage.setMaximized(!stage.isMaximized()));
        });

        dialog.showAndWait();
    }

    /** Opens the student's existing read-only weekly timetable screen. */
    @FXML
    private void handleViewMyTimetable() {
        SceneManager.getInstance().navigateTo("student_timetable.fxml", "My Timetable");
    }

    // ------------------------------------------------------------------ row types

    private static final class ClassRow {
        final String courseCode;
        final String courseName;
        final String section;
        final String campus;
        final String instructor;
        final String crn;
        final String schedule;
        final String room;
        final String grade;
        final int totalAbsences;
        final EnrollmentStatus status;
        final int credits;

        ClassRow(String courseCode, String courseName, String section, String campus, String instructor,
                 String crn, String schedule, String room, String grade, int totalAbsences,
                 EnrollmentStatus status, int credits) {
            this.courseCode = courseCode;
            this.courseName = courseName;
            this.section = section;
            this.campus = campus;
            this.instructor = instructor;
            this.crn = crn;
            this.schedule = schedule;
            this.room = room;
            this.grade = grade;
            this.totalAbsences = totalAbsences;
            this.status = status;
            this.credits = credits;
        }
    }

    private static final class ExamRow {
        final String courseCode;
        final String courseName;
        final String date;
        final String day;
        final String time;
        final String duration;
        final String room;

        ExamRow(String courseCode, String courseName, String date, String day, String time,
                 String duration, String room) {
            this.courseCode = courseCode;
            this.courseName = courseName;
            this.date = date;
            this.day = day;
            this.time = time;
            this.duration = duration;
            this.room = room;
        }
    }

    private static final class TodayRow {
        final LocalTime startTime;
        final String time;
        final String course;
        final String instructor;
        final String room;

        TodayRow(LocalTime startTime, String time, String course, String instructor, String room) {
            this.startTime = startTime;
            this.time = time;
            this.course = course;
            this.instructor = instructor;
            this.room = room;
        }
    }

    /**
     * The AI panel starts collapsed to a single top-right button and reserves no layout space;
     * this toggles it open — a vertical panel overlaying the dashboard, anchored below that
     * button — or closed. The dashboard beneath never resizes or reflows either way.
     */
    @FXML
    private void handleToggleAiAssistant() {
        boolean expand = !aiAssistantExpandedBox.isVisible();
        aiAssistantExpandedBox.setVisible(expand);
        aiAssistantExpandedBox.setManaged(expand);
        aiAssistantToggleButton.setVisible(!expand);
        aiAssistantToggleButton.setManaged(!expand);
    }
}
