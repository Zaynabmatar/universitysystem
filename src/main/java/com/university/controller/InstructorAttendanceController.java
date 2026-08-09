package com.university.controller;

import com.university.enums.AttendanceStatus;
import com.university.enums.UserRole;
import com.university.model.AttendanceRow;
import com.university.model.Course;
import com.university.model.Section;
import com.university.model.Semester;
import com.university.service.AttendanceService;
import com.university.service.CourseService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.service.ValidationException;
import com.university.util.AlertUtil;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The instructor's attendance sheet: pick one of your own sections, pick one of its real
 * scheduled class dates off the full-semester calendar, mark each officially enrolled student
 * Present or Absent, and save. Every rule (own sections only, valid scheduled dates only, enrolled
 * students only, no duplicate rows) is enforced again in {@link AttendanceService} — this screen
 * only decides what to show and only wires clicks to the dates it already knows are allowed.
 */
public class InstructorAttendanceController {

    private static final String[] DAY_HEADERS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    @FXML private Label titleLabel;
    @FXML private ComboBox<SectionChoice> sectionCombo;
    @FXML private Label sectionLabel;
    @FXML private Label dateHintLabel;
    @FXML private ScrollPane calendarScroll;
    @FXML private VBox monthsBox;
    @FXML private TextField searchField;
    @FXML private TableView<AttendanceRow> attendanceTable;
    @FXML private TableColumn<AttendanceRow, String> colStudentId;
    @FXML private TableColumn<AttendanceRow, String> colStudentName;
    @FXML private TableColumn<AttendanceRow, String> colSection;
    @FXML private TableColumn<AttendanceRow, AttendanceStatus> colStatus;
    @FXML private Label statsLabel;
    @FXML private Button saveButton;

    private final AttendanceService attendanceService = new AttendanceService();
    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();

    private final ObservableList<AttendanceRow> rows = FXCollections.observableArrayList();
    private final FilteredList<AttendanceRow> filteredRows = new FilteredList<>(rows, r -> true);

    private int sectionId;
    private Set<LocalDate> scheduledDates = Set.of();
    /** The semester the section chooser is scoped to — its start/end dates bound the calendar. */
    private Semester currentSemester;
    private LocalDate selectedDate;
    /** Guards the combo listener while a load method is setting the selection itself. */
    private boolean loading;

    /** One entry of the section chooser: only sections this instructor teaches. */
    private record SectionChoice(int sectionId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.INSTRUCTOR);

        colStudentId.setCellValueFactory(c ->
                new SimpleStringProperty(String.valueOf(c.getValue().getStudentUserId())));
        colStudentName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getStudentName()));
        colSection.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getSectionLabel()));
        colStatus.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getStatus()));
        colStatus.setCellFactory(statusCellFactory());

        attendanceTable.setItems(filteredRows);
        attendanceTable.setPlaceholder(new Label("Choose one of your sections, then a scheduled class date."));

        saveButton.setDisable(true);

        fillSectionChooser();

        sectionCombo.valueProperty().addListener((obs, old, chosen) -> {
            if (loading || chosen == null) {
                return;
            }
            onSectionChosen(chosen);
        });

        searchField.textProperty().addListener((obs, old, text) -> applySearchFilter(text));
    }

    private void applySearchFilter(String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ENGLISH);
        if (needle.isEmpty()) {
            filteredRows.setPredicate(r -> true);
            return;
        }
        filteredRows.setPredicate(r ->
                r.getStudentName().toLowerCase(Locale.ENGLISH).contains(needle)
                        || String.valueOf(r.getStudentUserId()).contains(needle));
    }

    // ------------------------------------------------------------------ loading

    private void fillSectionChooser() {
        try {
            Semester current = semesterService.getCurrentSemester();
            currentSemester = current;
            if (current == null) {
                return;
            }
            // Own-sections-only at the data level, same as InstructorSectionsController and
            // InstructorGradesController: only sections carrying this instructor's instructor_id
            // are ever fetched, so another instructor's section is never even loaded into memory.
            int instructorId = Session.current().requireInstructorId();
            List<Section> sections = sectionService.searchSections(
                    current.getSemesterId(), null, instructorId, null);
            List<Course> courses = courseService.listCourses(false);

            List<SectionChoice> choices = sections.stream()
                    .map(section -> new SectionChoice(section.getSectionId(), labelFor(section, courses)))
                    .toList();
            sectionCombo.setItems(FXCollections.observableArrayList(choices));
        } catch (RuntimeException e) {
            AlertUtil.error("Attendance", "Your sections could not be loaded.", e);
        }
    }

    private String labelFor(Section section, List<Course> courses) {
        Optional<Course> course = courses.stream()
                .filter(c -> c.getCourseId() == section.getCourseId())
                .findFirst();
        return course.map(c -> c.getCourseCode() + "-" + section.getSectionNumber() + "  " + c.getCourseTitle())
                .orElse("Section " + section.getSectionNumber());
    }

    private void onSectionChosen(SectionChoice chosen) {
        sectionId = chosen.sectionId();
        sectionLabel.setText(chosen.label());
        selectedDate = null;
        rows.clear();
        updateStats();
        saveButton.setDisable(true);

        try {
            scheduledDates = new HashSet<>(attendanceService.listScheduledDates(sectionId));
        } catch (RuntimeException e) {
            scheduledDates = Set.of();
            AlertUtil.error("Attendance", "This section's schedule could not be loaded.", e);
        }

        buildCalendar();

        dateHintLabel.setText(scheduledDates.isEmpty()
                ? "This section has no weekly schedule set, so no class dates are available."
                : "Pick one of the highlighted class dates.");
    }

    private void onDateChosen(LocalDate date) {
        selectedDate = date;
        buildCalendar();
        loadRoster(date);
    }

    private void loadRoster(LocalDate date) {
        try {
            int instructorId = Session.current().requireInstructorId();
            List<AttendanceRow> roster = attendanceService.getRoster(instructorId, sectionId, date);
            rows.setAll(roster);
            attendanceTable.refresh();
            updateStats();
            saveButton.setDisable(rows.isEmpty());
        } catch (ValidationException e) {
            rows.clear();
            updateStats();
            saveButton.setDisable(true);
            AlertUtil.error("Attendance", e.getMessage());
        } catch (RuntimeException e) {
            rows.clear();
            updateStats();
            saveButton.setDisable(true);
            AlertUtil.error("Attendance", "The class roster could not be loaded.", e);
        }
    }

    // ------------------------------------------------------------------ calendar

    /** Rebuilds the whole semester calendar — one month block per month the semester spans. */
    private void buildCalendar() {
        monthsBox.getChildren().clear();
        if (currentSemester == null || currentSemester.getStartDate() == null
                || currentSemester.getEndDate() == null) {
            return;
        }
        YearMonth start = YearMonth.from(currentSemester.getStartDate());
        YearMonth end = YearMonth.from(currentSemester.getEndDate());
        for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            monthsBox.getChildren().add(buildMonth(ym));
        }
        calendarScroll.setVvalue(0);
    }

    private Node buildMonth(YearMonth month) {
        VBox box = new VBox(6);
        box.getStyleClass().add("calendar-month");

        Label header = new Label(month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + month.getYear());
        header.getStyleClass().add("section-title");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("calendar-grid");
        grid.setHgap(4);
        grid.setVgap(4);
        for (int i = 0; i < DAY_HEADERS.length; i++) {
            Label dayHeader = new Label(DAY_HEADERS[i]);
            dayHeader.getStyleClass().add("calendar-day-header");
            dayHeader.setPrefSize(32, 20);
            dayHeader.setAlignment(Pos.CENTER);
            grid.add(dayHeader, i, 0);
        }

        LocalDate firstOfMonth = month.atDay(1);
        int col = firstOfMonth.getDayOfWeek().getValue() % 7; // Sunday lands in column 0
        int row = 1;
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            grid.add(buildDayCell(month.atDay(day)), col, row);
            col++;
            if (col > 6) {
                col = 0;
                row++;
            }
        }

        box.getChildren().addAll(header, grid);
        return box;
    }

    private Node buildDayCell(LocalDate date) {
        Label cell = new Label(String.valueOf(date.getDayOfMonth()));
        cell.getStyleClass().add("calendar-day-cell");
        cell.setPrefSize(32, 28);
        cell.setAlignment(Pos.CENTER);

        if (scheduledDates.contains(date)) {
            cell.getStyleClass().add("scheduled-class-day");
            if (date.equals(selectedDate)) {
                cell.getStyleClass().add("selected-class-day");
            }
            cell.setOnMouseClicked(e -> onDateChosen(date));
        } else {
            cell.getStyleClass().add("calendar-day-disabled");
        }
        return cell;
    }

    // ------------------------------------------------------------------ status column

    private Callback<TableColumn<AttendanceRow, AttendanceStatus>, TableCell<AttendanceRow, AttendanceStatus>>
            statusCellFactory() {
        return col -> new TableCell<>() {
            private final ComboBox<AttendanceStatus> combo =
                    new ComboBox<>(FXCollections.observableArrayList(AttendanceStatus.values()));

            {
                combo.getStyleClass().add("attendance-status-combo");
                combo.setOnAction(e -> {
                    AttendanceRow row = getIndex() >= 0 && getIndex() < getTableView().getItems().size()
                            ? getTableView().getItems().get(getIndex()) : null;
                    if (row != null && combo.getValue() != null) {
                        row.setStatus(combo.getValue());
                    }
                    applyBadgeStyle(combo.getValue());
                });
            }

            private void applyBadgeStyle(AttendanceStatus status) {
                combo.getStyleClass().removeAll("badge-ok", "badge-danger");
                if (status == AttendanceStatus.PRESENT) {
                    combo.getStyleClass().add("badge-ok");
                } else if (status == AttendanceStatus.ABSENT) {
                    combo.getStyleClass().add("badge-danger");
                }
            }

            @Override
            protected void updateItem(AttendanceStatus status, boolean empty) {
                super.updateItem(status, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                combo.setValue(status);
                applyBadgeStyle(status);
                setGraphic(combo);
            }
        };
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void handleRefresh() {
        if (selectedDate != null) {
            loadRoster(selectedDate);
        }
    }

    @FXML
    private void handleSave() {
        if (sectionId <= 0 || selectedDate == null) {
            AlertUtil.error("Select a date", "Choose a section and a scheduled class date first.");
            return;
        }
        try {
            int instructorId = Session.current().requireInstructorId();
            int actingUserId = Session.current().getUser().getUserId();
            attendanceService.saveAttendance(instructorId, sectionId, selectedDate, rows, actingUserId);
            AlertUtil.success("Attendance saved", "Attendance for " + selectedDate + " was saved.");
            loadRoster(selectedDate);
        } catch (ValidationException e) {
            AlertUtil.error("Cannot save", e.getMessage());
        } catch (ServiceException e) {
            AlertUtil.error("Cannot save", "Attendance could not be saved. Please try again.", e);
        }
    }

    private void updateStats() {
        long present = rows.stream().filter(r -> r.getStatus() == AttendanceStatus.PRESENT).count();
        long absent = rows.stream().filter(r -> r.getStatus() == AttendanceStatus.ABSENT).count();
        statsLabel.setText(rows.isEmpty() ? "" : rows.size() + " students  •  " + present + " present  •  " + absent + " absent");
    }
}
