package com.university.controller;

import com.university.enums.DayOfWeekCode;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.service.CourseService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The instructor's own weekly teaching timetable (project_details.md Section 2.2) — the same
 * weekly grid as the student's, drawn from the sections they teach rather than the ones they are
 * enrolled in.
 *
 * <p>Rule G1 again holds at the query level: {@link SectionService#searchSections} is asked only
 * for this instructor's {@code instructor_id}, so another instructor's teaching load is never
 * loaded.</p>
 */
public class InstructorTimetableController {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<DayOfWeekCode> DAYS = List.of(DayOfWeekCode.values());
    private static final int START_HOUR = 8;
    private static final int END_HOUR = 20;
    private static final int SLOT_MINUTES = 30;
    private static final String[] PALETTE =
            {"tt-color-1", "tt-color-2", "tt-color-3", "tt-color-4",
             "tt-color-5", "tt-color-6", "tt-color-7", "tt-color-8",
             "tt-color-9", "tt-color-10", "tt-color-11", "tt-color-12"};

    @FXML private Label semesterLabel;
    @FXML private Label summaryLabel;
    @FXML private Label emptyLabel;
    @FXML private GridPane timetableGrid;
    @FXML private FlowPane legendBox;

    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();

    private int instructorId;
    private final Map<Integer, String> courseColours = new LinkedHashMap<>();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.INSTRUCTOR);
        instructorId = Session.current().requireInstructorId();
        reload();
    }

    @FXML
    private void handleRefresh() {
        reload();
    }

    private void reload() {
        try {
            timetableGrid.getChildren().clear();
            legendBox.getChildren().clear();
            courseColours.clear();

            Semester current = semesterService.getCurrentSemester();
            if (current == null) {
                semesterLabel.setText("No current semester is set.");
                summaryLabel.setText("");
                showEmpty("You are not teaching any sections this semester.");
                return;
            }
            semesterLabel.setText("Semester: " + current.getSemesterName());

            List<Section> mine = sectionService.searchSections(
                    current.getSemesterId(), null, instructorId, null);
            if (mine.isEmpty()) {
                summaryLabel.setText("");
                showEmpty("You are not teaching any sections this semester.");
                return;
            }

            Map<Integer, Course> coursesById = courseService.listCourses(false).stream()
                    .collect(Collectors.toMap(c -> c.getCourseId(), c -> c, (a, b) -> a));
            Map<Integer, Section> sectionsById = mine.stream()
                    .collect(Collectors.toMap(s -> s.getSectionId(), s -> s, (a, b) -> a));

            List<SectionSchedule> meetings = new ArrayList<>();
            int totalStudents = 0;

            for (Section section : mine) {
                meetings.addAll(sectionService.listMeetings(section.getSectionId()));
                totalStudents += section.getEnrolledCount();
            }

            summaryLabel.setText(meetings.size() + " weekly meeting(s) · "
                    + mine.size() + " section(s) · "
                    + totalStudents + " student(s)");

            hideEmpty();
            buildGrid(meetings, sectionsById, coursesById);
            buildLegend(mine, coursesById);
        } catch (Exception e) {
            AlertUtil.error("My Timetable", "Your teaching timetable could not be loaded.", e);
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

        timetableGrid.getColumnConstraints().add(new ColumnConstraints(64));
        for (int day = 0; day < DAYS.size(); day++) {
            timetableGrid.getColumnConstraints().add(new ColumnConstraints(150));
        }

        int totalSlots = (END_HOUR - START_HOUR) * 60 / SLOT_MINUTES;
        timetableGrid.getRowConstraints().add(new RowConstraints(28));
        for (int slot = 0; slot < totalSlots; slot++) {
            timetableGrid.getRowConstraints().add(new RowConstraints(24));
        }

        timetableGrid.add(new Label(), 0, 0);
        for (int day = 0; day < DAYS.size(); day++) {
            Label dayLabel = new Label(DAYS.get(day).name());
            dayLabel.getStyleClass().add("timetable-day-header");
            dayLabel.setMaxWidth(Double.MAX_VALUE);
            dayLabel.setAlignment(Pos.CENTER);
            timetableGrid.add(dayLabel, day + 1, 0);
        }

        int slotsPerHour = 60 / SLOT_MINUTES;
        for (int hour = 0; hour < (END_HOUR - START_HOUR); hour++) {
            Label time = new Label(String.format("%02d:00", START_HOUR + hour));
            time.getStyleClass().add("timetable-time-label");
            timetableGrid.add(time, 0, 1 + hour * slotsPerHour, 1, slotsPerHour);
        }

        for (SectionSchedule meeting : meetings) {
            int dayIndex = DAYS.indexOf(meeting.getDayOfWeek());
            if (dayIndex < 0) {
                continue;
            }
            Section section = sectionsById.get(meeting.getSectionId());
            if (section == null) {
                continue;
            }
            int startSlot = slotIndex(meeting.getStartTime());
            int rowSpan = Math.max(1, slotIndex(meeting.getEndTime()) - startSlot);
            Course course = coursesById.get(section.getCourseId());

            VBox block = new VBox(2);
            block.getStyleClass().addAll("timetable-block", colourFor(section.getCourseId()));

            Label code = new Label(course != null
                    ? course.getCourseCode() + "-" + section.getSectionNumber()
                    : "Section " + section.getSectionNumber());
            code.getStyleClass().add("timetable-block-title");

            Label detail = new Label(HM.format(meeting.getStartTime()) + "-" + HM.format(meeting.getEndTime())
                    + (section.getRoom() != null ? " · " + section.getRoom() : ""));
            detail.getStyleClass().add("timetable-block-sub");

            block.getChildren().addAll(code, detail);
            timetableGrid.add(block, dayIndex + 1, 1 + startSlot, 1, rowSpan);
        }


    }

    /** One chip per course, in the colour its blocks carry. */
    private void buildLegend(List<Section> sections, Map<Integer, Course> coursesById) {
        Map<Integer, String> byCourse = new LinkedHashMap<>();
        for (Section section : sections) {
            Course course = coursesById.get(section.getCourseId());
            byCourse.putIfAbsent(section.getCourseId(),
                    course == null ? "Course " + section.getCourseId()
                                   : course.getCourseCode() + " — " + course.getCourseTitle());
        }
        byCourse.forEach((courseId, text) -> {
            Label chip = new Label(text);
            chip.getStyleClass().addAll("timetable-block", colourFor(courseId), "legend-chip");
            legendBox.getChildren().add(chip);
        });
    }

    /**
     * Adds a class/exam block to the timetable.
     * If another block already occupies the exact same day/time range,
     * both are displayed side by side instead of being drawn on top of each other.
     */
    /**
     * Adds a class/exam block to the weekly timetable.
     * Any real time overlap on the same day is displayed side by side.
     */
    private String colourFor(int courseId) {
        if (courseColours.containsKey(courseId)) {
            return courseColours.get(courseId);
        }

        if (courseColours.size() >= PALETTE.length) {
            return PALETTE[PALETTE.length - 1];
        }

        String colour = PALETTE[courseColours.size()];
        courseColours.put(courseId, colour);
        return colour;
    }

    private int slotIndex(LocalTime time) {
        int minutesFromStart = (time.getHour() * 60 + time.getMinute()) - START_HOUR * 60;
        return Math.max(0, minutesFromStart / SLOT_MINUTES);
    }
}

