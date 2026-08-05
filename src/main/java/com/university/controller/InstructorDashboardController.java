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
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    @FXML private TableView<TodayClass> todayTable;
    @FXML private TableColumn<TodayClass, String> colTime;
    @FXML private TableColumn<TodayClass, String> colCourse;
    @FXML private TableColumn<TodayClass, String> colRoom;

    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();
    private final GradeService gradeService = new GradeService();

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

    private void reload() {
        try {
            Semester current = semesterService.getCurrentSemester();
            todaysClasses.clear();

            if (current == null) {
                semesterLabel.setText("No current semester is set.");
                showFigures(0, 0, 0, "—");
                return;
            }
            semesterLabel.setText("Semester: " + current.getSemesterName());

            int instructorId = Session.current().requireInstructorId();
            List<Section> mine = sectionService.searchSections(
                    current.getSemesterId(), null, instructorId, null);

            int students = mine.stream().mapToInt(section -> section.getEnrolledCount()).sum();
            long pending = mine.stream()
                    .filter(section -> !gradeService.isSectionSubmitted(section.getSectionId()))
                    .count();

            showFigures(mine.size(), students, (int) pending, gradeWindowText(current));
            fillTodaysClasses(mine);
        } catch (Exception e) {
            AlertUtil.error("Dashboard", "Your dashboard could not be loaded.", e);
        }
    }

    private void showFigures(int sections, int students, int pending, String window) {
        kpiSections.setText(String.valueOf(sections));
        kpiStudents.setText(String.valueOf(students));
        kpiPendingGrades.setText(String.valueOf(pending));
        kpiGradeWindow.setText(window);
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

    private void fillTodaysClasses(List<Section> sections) {
        DayOfWeekCode today = todayCode();
        if (today == null) {
            return;
        }
        Map<Integer, Course> coursesById = courseService.listCourses(false).stream()
                .collect(Collectors.toMap(c -> c.getCourseId(), c -> c, (a, b) -> a));

        List<TodayClass> found = new ArrayList<>();
        for (Section section : sections) {
            for (SectionSchedule meeting : sectionService.listMeetings(section.getSectionId())) {
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
