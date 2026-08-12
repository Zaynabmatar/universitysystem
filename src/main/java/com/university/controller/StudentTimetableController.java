package com.university.controller;

import com.university.enums.DayOfWeekCode;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.Enrollment;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.service.CourseService;
import com.university.service.RegistrationService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** The student's read-only weekly timetable: every ENROLLED section's meetings, laid out by day and hour. */
public class StudentTimetableController {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<DayOfWeekCode> DAYS = List.of(DayOfWeekCode.values());
    private static final int START_HOUR = 8;
    private static final int END_HOUR = 20;
    private static final int SLOT_MINUTES = 30;
    private static final String[] PALETTE =
            {"tt-color-1", "tt-color-2", "tt-color-3", "tt-color-4", "tt-color-5", "tt-color-6", "tt-color-7", "tt-color-8", "tt-color-9", "tt-color-10", "tt-color-11", "tt-color-12"};

    @FXML private Label semesterLabel;
    @FXML private Label summaryLabel;
    @FXML private Label emptyLabel;
    @FXML private GridPane timetableGrid;

    private final RegistrationService registrationService = new RegistrationService();
    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();

    private int studentId;
    private int courseColourIndex = 0;
    private final Map<Integer, String> courseColours = new java.util.LinkedHashMap<>();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        studentId = Session.current().requireStudentId();
        reload();
    }

    @FXML
    private void handleRefresh() {
        reload();
    }

    private void reload() {
        try {
            courseColourIndex = 0;
            Semester currentSemester = semesterService.getCurrentSemester();
            timetableGrid.getChildren().clear();

            if (currentSemester == null) {
                semesterLabel.setText("No current semester is set.");
                summaryLabel.setText("");
                showEmpty("You are not enrolled in any section this semester. Go to Registration to add courses.");
                return;
            }
            semesterLabel.setText("Semester: " + currentSemester.getSemesterName());

            List<Enrollment> mine = registrationService.currentRegistrations(studentId, currentSemester.getSemesterId());
            List<Section> sections = mine.stream()
                    .map(e -> sectionService.findById(e.getSectionId()))
                    .filter(Objects::nonNull)
                    .toList();

            if (sections.isEmpty()) {
                summaryLabel.setText("");
                showEmpty("You are not enrolled in any section this semester. Go to Registration to add courses.");
                return;
            }

            Map<Integer, Course> coursesById = courseService.listCourses(false).stream()
                    .collect(Collectors.toMap(c -> c.getCourseId(), c -> c, (a, b) -> a));
            Map<Integer, Section> sectionsById = sections.stream()
                    .collect(Collectors.toMap(s -> s.getSectionId(), s -> s, (a, b) -> a));

            List<SectionSchedule> meetings = new ArrayList<>();
            int totalCredits = 0;
            for (Section s : sections) {
                meetings.addAll(sectionService.listMeetings(s.getSectionId()));
                Course c = coursesById.get(s.getCourseId());
                if (c != null) {
                    totalCredits += c.getCredits();
                }
            }

            summaryLabel.setText(meetings.size() + " weekly meeting(s) · " + sections.size()
                    + " section(s) · " + totalCredits + " credits");
            hideEmpty();
            buildGrid(meetings, sectionsById, coursesById);
        } catch (Exception e) {
            AlertUtil.error("Could not load your timetable", "The timetable could not be loaded.", e);
        }
    }

    private void showEmpty(String message) {
        emptyLabel.setText(message);
        emptyLabel.setVisible(true);
        emptyLabel.setManaged(true);
    }

    private void hideEmpty() {
        emptyLabel.setVisible(false);
        emptyLabel.setManaged(false);
    }

    // ------------------------------------------------------------------ grid

    private void buildGrid(List<SectionSchedule> meetings, Map<Integer, Section> sectionsById,
                            Map<Integer, Course> coursesById) {
        timetableGrid.getColumnConstraints().clear();
        timetableGrid.getRowConstraints().clear();

        ColumnConstraints gutter = new ColumnConstraints(64);
        timetableGrid.getColumnConstraints().add(gutter);
        for (int d = 0; d < DAYS.size(); d++) {
            ColumnConstraints cc = new ColumnConstraints(150);
            timetableGrid.getColumnConstraints().add(cc);
        }

        int totalSlots = (END_HOUR - START_HOUR) * 60 / SLOT_MINUTES;
        RowConstraints headerRow = new RowConstraints(28);
        timetableGrid.getRowConstraints().add(headerRow);
        for (int s = 0; s < totalSlots; s++) {
            RowConstraints rc = new RowConstraints(24);
            timetableGrid.getRowConstraints().add(rc);
        }

        Label corner = new Label();
        timetableGrid.add(corner, 0, 0);
        for (int d = 0; d < DAYS.size(); d++) {
            Label dayLabel = new Label(DAYS.get(d).name());
            dayLabel.getStyleClass().add("timetable-day-header");
            dayLabel.setMaxWidth(Double.MAX_VALUE);
            dayLabel.setAlignment(Pos.CENTER);
            timetableGrid.add(dayLabel, d + 1, 0);
        }

        int slotsPerHour = 60 / SLOT_MINUTES;
        for (int h = 0; h < (END_HOUR - START_HOUR); h++) {
            Label t = new Label(String.format("%02d:00", START_HOUR + h));
            t.getStyleClass().add("timetable-time-label");
            timetableGrid.add(t, 0, 1 + h * slotsPerHour, 1, slotsPerHour);
        }

        for (SectionSchedule m : meetings) {
            int dayIndex = DAYS.indexOf(m.getDayOfWeek());
            if (dayIndex < 0) {
                continue;
            }
            int startSlot = slotIndex(m.getStartTime());
            int endSlot = slotIndex(m.getEndTime());
            int rowSpan = Math.max(1, endSlot - startSlot);

            Section section = sectionsById.get(m.getSectionId());
            if (section == null) {
                continue;
            }
            Course course = coursesById.get(section.getCourseId());

            VBox block = new VBox(2);
            int courseId = section.getCourseId();
            String courseColour = courseColours.computeIfAbsent(
                    courseId,
                    id -> PALETTE[courseColourIndex++ % PALETTE.length]
            );
            block.getStyleClass().addAll("timetable-block", courseColour);

            Label codeLabel = new Label(course != null
                    ? course.getCourseCode() + "-" + section.getSectionNumber() : "Section " + section.getSectionNumber());
            codeLabel.getStyleClass().add("timetable-block-title");
            Label timeLabel = new Label(HM.format(m.getStartTime()) + "-" + HM.format(m.getEndTime())
                    + (section.getRoom() != null ? " · " + section.getRoom() : ""));
            timeLabel.getStyleClass().add("timetable-block-sub");
            block.getChildren().addAll(codeLabel, timeLabel);

            timetableGrid.add(block, dayIndex + 1, 1 + startSlot, 1, rowSpan);
        }
    }

    private int slotIndex(LocalTime time) {
        int minutesFromStart = (time.getHour() * 60 + time.getMinute()) - START_HOUR * 60;
        return Math.max(0, minutesFromStart / SLOT_MINUTES);
    }
}


