package com.university.controller;

import com.university.enums.DayOfWeekCode;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.service.CourseService;
import com.university.service.GradeService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.Session;
import com.university.service.StudentService;
import com.university.util.AlertUtil;
import com.university.util.Async;
import com.university.util.SceneManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The instructor's landing page: what they teach, how many students they have, how much marking is
 * still open, and whether the grade-entry window is open at all (rule G2 answered before they even
 * click into a section).
 */
public class InstructorDashboardController {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    @FXML private Label semesterLabel;
    @FXML private Label kpiSections;
    @FXML private Label kpiStudents;
    @FXML private Label kpiPendingGrades;
    @FXML private Label kpiGradeWindow;
    @FXML private Label kpiUniversityGpa;
    @FXML private TableView<TodayClass> todayTable;
    @FXML private TableColumn<TodayClass, String> colTime;
    @FXML private TableColumn<TodayClass, String> colCourse;
    @FXML private TableColumn<TodayClass, String> colRoom;

    @FXML private AIAssistantPanelController aiAssistantController;
    @FXML private Button aiAssistantToggleButton;
    @FXML private VBox   aiAssistantExpandedBox;

    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();
    private final GradeService gradeService = new GradeService();
    private final StudentService studentService = new StudentService();

    private final ObservableList<TodayClass> todaysClasses = FXCollections.observableArrayList();

    /** One meeting happening today. */
    public record TodayClass(String time, String course, String room) { }

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.INSTRUCTOR);

        colTime.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().time()));
        colCourse.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().course()));
        colRoom.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().room()));

        todayTable.setItems(todaysClasses);
        todayTable.setPlaceholder(new Label("You have no classes today."));

        reload();

        aiAssistantController.configure(
                "Hi! I'm the University AI Assistant. Ask me about your sections, today's classes, "
                        + "the grade entry window, or your timetable — or use one of the quick questions above.",
                List.of("What is my next class?", "Is the grade entry window open?",
                        "How many students do I have?", "What is today's schedule?"));
    }

    @FXML
    private void handleRefresh() {
        reload();
    }

    @FXML
    private void handleGoSections() {
        SceneManager.getInstance().navigateTo("instructor_sections.fxml", "My Sections");
    }

    @FXML
    private void handleGoTimetable() {
        SceneManager.getInstance().navigateTo("instructor_timetable.fxml", "My Timetable");
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

    /** Everything {@link #reload()} needs from the database, fetched together off the FX thread. */
    private record InstructorData(Semester current, BigDecimal universityGpa, List<Section> mine,
                                   Set<Integer> submittedSectionIds, Map<Integer, Course> coursesById,
                                   Map<Integer, List<SectionSchedule>> meetingsBySection) {
    }

    private void reload() {
        int instructorId = Session.current().requireInstructorId();
        Async.run(
                () -> {
                    Semester current = semesterService.getCurrentSemester();
                    BigDecimal universityGpa = studentService.universityAverageGpa();
                    if (current == null) {
                        return new InstructorData(null, universityGpa, List.of(), Set.of(), Map.of(), Map.of());
                    }
                    List<Section> mine = sectionService.searchSections(
                            current.getSemesterId(), null, instructorId, null);
                    Set<Integer> submitted = gradeService.submittedSectionIds(instructorId, current.getSemesterId());
                    Map<Integer, Course> coursesById = courseService.listCourses(false).stream()
                            .collect(Collectors.toMap(c -> c.getCourseId(), c -> c, (a, b) -> a));
                    Map<Integer, List<SectionSchedule>> meetingsBySection =
                            sectionService.listMeetingsForInstructor(instructorId, current.getSemesterId());
                    return new InstructorData(current, universityGpa, mine, submitted, coursesById, meetingsBySection);
                },
                this::applyReload,
                error -> AlertUtil.error("Dashboard", "Your dashboard could not be loaded.", error));
    }

    private void applyReload(InstructorData data) {
        todaysClasses.clear();
        kpiUniversityGpa.setText(formatGpa(data.universityGpa()));

        if (data.current() == null) {
            semesterLabel.setText("No current semester is set.");
            showFigures(0, 0, 0, "—");
            return;
        }
        semesterLabel.setText("Semester: " + data.current().getSemesterName());

        int students = data.mine().stream().mapToInt(section -> section.getEnrolledCount()).sum();
        long pending = data.mine().stream()
                .filter(section -> !data.submittedSectionIds().contains(section.getSectionId()))
                .count();

        showFigures(data.mine().size(), students, (int) pending, gradeWindowText(data.current()));
        fillTodaysClasses(data.mine(), data.coursesById(), data.meetingsBySection());
    }

    private void showFigures(int sections, int students, int pending, String window) {
        kpiSections.setText(String.valueOf(sections));
        kpiStudents.setText(String.valueOf(students));
        kpiPendingGrades.setText(String.valueOf(pending));
        kpiGradeWindow.setText(window);
    }

    /** e.g. "3.12 / 4.00", or "—" when no student has completed any credits yet. */
    private String formatGpa(BigDecimal averageGpa) {
        if (averageGpa == null) {
            return "—";
        }
        return averageGpa.setScale(2, RoundingMode.HALF_UP) + " / 4.00";
    }

    /** Rule G2 stated up front, so nobody types a full sheet of marks into a closed window. */
    private String gradeWindowText(Semester semester) {
        LocalDate start = semester.getGradeEntryStart();
        LocalDate end = semester.getGradeEntryEnd();
        if (start == null || end == null) {
            return "Not set";
        }
        LocalDate today = LocalDate.now();
        if (today.isBefore(start)) {
            return "Opens " + start;
        }
        if (today.isAfter(end)) {
            return "CLOSED";
        }
        return "OPEN until " + end;
    }

    private void fillTodaysClasses(List<Section> sections, Map<Integer, Course> coursesById,
                                    Map<Integer, List<SectionSchedule>> meetingsBySection) {
        DayOfWeekCode today = todayCode();
        if (today == null) {
            return;
        }

        List<TodayClass> found = new ArrayList<>();
        for (Section section : sections) {
            for (SectionSchedule meeting : meetingsBySection.getOrDefault(section.getSectionId(), List.of())) {
                if (meeting.getDayOfWeek() != today) {
                    continue;
                }
                Course course = coursesById.get(section.getCourseId());
                found.add(new TodayClass(
                        HM.format(meeting.getStartTime()) + " - " + HM.format(meeting.getEndTime()),
                        (course == null ? "Course " + section.getCourseId() : course.getCourseCode()
                                + " — " + course.getCourseTitle()) + "  (Sec. " + section.getSectionNumber() + ")",
                        section.getRoom() == null ? "—" : section.getRoom()));
            }
        }
        found.sort(Comparator.comparing((TodayClass tc) -> tc.time()));
        todaysClasses.setAll(found);
    }

    /** Maps today onto the schedule's day codes; null when the code set has no entry for today. */
    private DayOfWeekCode todayCode() {
        String wanted = switch (LocalDate.now().getDayOfWeek()) {
            case SUNDAY -> "SUN";
            case MONDAY -> "MON";
            case TUESDAY -> "TUE";
            case WEDNESDAY -> "WED";
            case THURSDAY -> "THU";
            case FRIDAY -> "FRI";
            case SATURDAY -> "SAT";
        };
        for (DayOfWeekCode code : DayOfWeekCode.values()) {
            if (code.name().equals(wanted)) {
                return code;
            }
        }
        return null;
    }
}
