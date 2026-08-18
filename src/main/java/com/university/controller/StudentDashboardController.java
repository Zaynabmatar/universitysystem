package com.university.controller;

import com.university.controller.dialog.InstructorEvaluationDialog;
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
import com.university.service.EvaluationService;
import com.university.service.ExamService;
import com.university.service.InstructorService;
import com.university.service.RegistrationService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.Async;
import com.university.util.SceneManager;

import javafx.application.Platform;
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
import java.time.LocalDateTime;
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
    @FXML private TableColumn<ClassRow, String> colEvaluation;

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
    private final EvaluationService evaluationService = new EvaluationService();
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
        colEvaluation.setCellValueFactory(c -> {
            int enrollmentId = c.getValue().enrollmentId;

            if (evaluationService.hasSubmitted(enrollmentId)) {
                return new SimpleStringProperty("★");
            }

            if (evaluationService.isAvailable(enrollmentId)) {
                return new SimpleStringProperty("☆");
            }

            return new SimpleStringProperty("");
        });

        colEvaluation.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);

                setText(null);
                setGraphic(null);
                setOnMouseClicked(null);
                setStyle("-fx-alignment: CENTER;");

                if (empty || value == null || value.isBlank()) {
                    return;
                }

                Label star = new Label(value);
                star.setStyle(
                        "-fx-font-size: 20px;"
                        + "-fx-font-weight: bold;"
                        + "-fx-text-fill: #4F46E5;"
                        + "-fx-cursor: hand;"
                );

                if ("★".equals(value)) {
                    star.setStyle(
                            "-fx-font-size: 20px;"
                            + "-fx-font-weight: bold;"
                            + "-fx-text-fill: #4F46E5;"
                    );
                } else if ("☆".equals(value)) {
                    star.setOnMouseClicked(event -> {
                        ClassRow row = getTableRow() == null ? null : getTableRow().getItem();
                        if (row != null) {
                            openEvaluation(row);
                        }
                    });
                }

                setGraphic(star);
            }
        });
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

    /** Everything {@link #reload()} needs from the database, fetched together off the FX thread. */
    private record StudentData(Semester currentSemester, List<ClassRow> classRows, List<ExamRow> examRows,
                                List<TodayRow> todayRows, Set<Integer> sectionIds) {
    }

    private void reload() {
        Async.run(this::fetchStudentData, this::applyReload,
                error -> AlertUtil.error("Dashboard", "Your dashboard could not be loaded.", error));
    }

    /**
     * Runs entirely off the FX thread. Sections and their weekly meetings are each fetched once
     * for the whole semester ({@link SectionService#listForStudent} /
     * {@link SectionService#listMeetingsForStudent}) and reused for the registered-courses table,
     * the exams table and today's schedule, instead of one query per section per table.
     */
    private StudentData fetchStudentData() {
        courses = courseService.listCourses(false);
        instructors = instructorService.listActive();
        campuses = sectionService.listCampuses();

        Semester currentSemester = semesterService.getCurrentSemester();
        if (currentSemester == null) {
            return new StudentData(null, List.of(), List.of(), List.of(), Set.of());
        }

        Map<Integer, StudentGradeRow> gradeByEnrollment =
                academicService.gradeRows(studentId, currentSemester.getSemesterId()).stream()
                        .collect(Collectors.toMap(StudentGradeRow::getEnrollmentId, r -> r, (a, b) -> a));

        List<Enrollment> enrollments =
                registrationService.myClassesForSemester(studentId, currentSemester.getSemesterId());

        Map<Integer, Section> sectionsById = sectionService.listForStudent(studentId, currentSemester.getSemesterId())
                .stream().collect(Collectors.toMap(Section::getSectionId, s -> s, (a, b) -> a));
        Map<Integer, List<SectionSchedule>> meetingsBySection =
                sectionService.listMeetingsForStudent(studentId, currentSemester.getSemesterId());
        Set<Integer> sectionIds = new HashSet<>(sectionsById.keySet());

        List<ClassRow> classRows = new ArrayList<>();
        for (Enrollment enrollment : enrollments) {
            Section section = sectionsById.get(enrollment.getSectionId());
            if (section == null) {
                continue;
            }

            Course course = courseOf(section.getCourseId());
            StudentGradeRow gradeRow = gradeByEnrollment.get(enrollment.getEnrollmentId());

            boolean evaluationSubmitted =
                    evaluationService.hasSubmitted(enrollment.getEnrollmentId());

            String grade = (evaluationSubmitted
                    && gradeRow != null
                    && gradeRow.getLetterGrade() != null)
                    ? gradeRow.getLetterGrade().getLabel()
                    : "--";

            classRows.add(new ClassRow(
                    enrollment.getEnrollmentId(),
                    course == null ? "—" : course.getCourseCode(),
                    course == null ? "—" : course.getCourseTitle(),
                    section.getSectionNumber(),
                    campusNameOf(section.getCampusId()),
                    instructorNameOf(section.getInstructorId()),
                    String.valueOf(section.getSectionId()),
                    scheduleTextOf(meetingsBySection, section.getSectionId()),
                    section.getRoom() == null || section.getRoom().isBlank() ? "—" : section.getRoom(),
                    grade,
                    attendanceService.countAbsencesForEnrollment(enrollment.getEnrollmentId()),
                    enrollment.getStatus(),
                    course == null ? 0 : course.getCredits()));
        }

        // The main dashboard table is "what's coming up" -- an exam whose date/time has already
        // passed belongs only in "View All Exams" (handleViewAllExams, built unfiltered), never
        // here, and never deleted from dbo.exams either.
        //
        // Queried by student+semester directly (examsForStudentInSemester -> ExamDAO#findByStudentAndSemester)
        // rather than filtered against sectionIds: sectionIds comes from listForStudent, which only
        // returns sections where the enrollment status is ENROLLED, so a section the student finished
        // (status COMPLETED) has an exam that "View All Exams" (status <> DROPPED) shows but this table
        // silently dropped.
        LocalDateTime now = LocalDateTime.now();
        List<Exam> mine = examService.examsForStudentInSemester(studentId, currentSemester.getSemesterId());
        List<ExamRow> examRowsBuilt = mine.stream()
                .filter(exam -> !LocalDateTime.of(exam.getExamDate(), exam.getStartTime()).isBefore(now))
                .map(exam -> toExamRow(exam, sectionsById))
                .toList();

        DayOfWeekCode today = DayOfWeekCode.fromJavaDayOfWeek(LocalDate.now().getDayOfWeek());
        List<TodayRow> todayRowsBuilt = new ArrayList<>();
        for (int sectionId : sectionIds) {
            Section section = sectionsById.get(sectionId);
            Course course = courseOf(section.getCourseId());
            for (SectionSchedule meeting : meetingsBySection.getOrDefault(sectionId, List.of())) {
                if (meeting.getDayOfWeek() != today) {
                    continue;
                }
                todayRowsBuilt.add(new TodayRow(
                        meeting.getStartTime(),
                        HM.format(meeting.getStartTime()) + " - " + HM.format(meeting.getEndTime()),
                        course == null ? "—" : course.getCourseCode() + " — " + course.getCourseTitle(),
                        instructorNameOf(section.getInstructorId()),
                        section.getRoom() == null || section.getRoom().isBlank() ? "—" : section.getRoom()));
            }
        }
        todayRowsBuilt.sort(Comparator.comparing(r -> r.startTime));

        return new StudentData(currentSemester, classRows, examRowsBuilt, todayRowsBuilt, sectionIds);
    }

    private void applyReload(StudentData data) {
        currentSectionIds.clear();
        currentSectionIds.addAll(data.sectionIds());

        if (data.currentSemester() == null) {
            semesterBannerLabel.setText("No current semester is set.");
            rows.clear();
            examRows.clear();
            todayRows.clear();
            updateTotals();
            return;
        }
        semesterBannerLabel.setText("Semester: " + data.currentSemester().getSemesterName());
        rows.setAll(data.classRows());
        examRows.setAll(data.examRows());
        todayRows.setAll(data.todayRows());
        updateTotals();
    }

    private ExamRow toExamRow(Exam exam, Map<Integer, Section> sectionsById) {
        Section section = sectionsById.get(exam.getSectionId());
        if (section == null) {
            section = sectionService.findById(exam.getSectionId());
        }
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

    private String scheduleTextOf(Map<Integer, List<SectionSchedule>> meetingsBySection, int sectionId) {
        List<SectionSchedule> meetings = meetingsBySection.getOrDefault(sectionId, List.of());
        if (meetings.isEmpty()) {
            return "No meetings set";
        }
        return meetings.stream()
                .map(m -> m.getDayOfWeek().name() + " " + HM.format(m.getStartTime()) + "-" + HM.format(m.getEndTime()))
                .collect(Collectors.joining(", "));
    }

    private void openEvaluation(ClassRow row) {
        if (row == null) {
            return;
        }

        try {
            if (!evaluationService.isAvailable(row.enrollmentId)) {
                reload();
                return;
            }

            var questions = evaluationService.getQuestions();

            InstructorEvaluationDialog dialog =
                    new InstructorEvaluationDialog(row.courseCode, row.instructor, questions);

            var result = dialog.showAndWait();

            if (result.isEmpty()) {
                return;
            }

            evaluationService.submit(row.enrollmentId, result.get());

            // Re-read the real database state so the empty star becomes a filled star.
            reload();

        } catch (RuntimeException e) {
            AlertUtil.error(
                    "Instructor Evaluation",
                    "The instructor evaluation could not be completed.",
                    e
            );
        }
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
        table.setItems(FXCollections.observableArrayList(
                all.stream().map(exam -> toExamRow(exam, Map.of())).toList()));
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
        final int enrollmentId;
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

        ClassRow(int enrollmentId, String courseCode, String courseName, String section, String campus, String instructor,
                 String crn, String schedule, String room, String grade, int totalAbsences,
                 EnrollmentStatus status, int credits) {
            this.enrollmentId = enrollmentId;
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

