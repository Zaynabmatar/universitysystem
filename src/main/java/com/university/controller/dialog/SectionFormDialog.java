package com.university.controller.dialog;

import com.university.enums.DayOfWeekCode;
import com.university.enums.SectionStatus;
import com.university.model.Campus;
import com.university.model.Course;
import com.university.model.Instructor;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.service.SectionService;
import com.university.service.ServiceException;
import com.university.util.ValidationUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Add / Edit section, together with its whole weekly schedule. Built in Java
 * rather than FXML on purpose: project_details.md Section 7 lists the
 * complete set of FXML files and no dialog appears in it, and no third FXML
 * was created for the schedule editor either — it lives inside this dialog.
 *
 * <p>The dialog saves through {@link SectionService} itself (a section and
 * its meetings are one transaction), so its result is only "saved / not
 * saved" — the OK-button filter reports rules S1, S2 and S3 by keeping the
 * dialog open and showing the service's message.</p>
 */
public final class SectionFormDialog extends Dialog<Boolean> {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final ComboBox<Course> courseBox           = new ComboBox<>();
    private final ComboBox<Semester> semesterBox       = new ComboBox<>();
    private final ComboBox<Instructor> instructorBox   = new ComboBox<>();
    private final ComboBox<Campus> campusBox           = new ComboBox<>();
    private final TextField sectionNumberField          = new TextField();
    private final Spinner<Integer> capacitySpinner      = new Spinner<>(1, 500, 30);
    private final TextField roomField                   = new TextField();
    private final ComboBox<SectionStatus> statusBox     = new ComboBox<>();

    private final ComboBox<DayOfWeekCode> dayBox = new ComboBox<>();
    private final TextField startField           = new TextField();
    private final TextField endField             = new TextField();
    private final Button addMeetingButton        = new Button("Add meeting");
    private final TableView<SectionSchedule> meetingTable = new TableView<>();
    private final Button removeMeetingButton     = new Button("Remove meeting");
    private final Label meetingErrorLabel        = new Label();

    private final Label errorLabel = new Label();

    private final SectionService sectionService;
    private final boolean editMode;
    private final Section model;
    private final ObservableList<SectionSchedule> meetings = FXCollections.observableArrayList();

    /**
     * @param existing        null = add mode; non-null = edit mode
     * @param courses         every active course
     * @param semesters       every semester
     * @param instructors     every active instructor
     * @param campuses        every campus
     * @param currentSemester pre-selected in add mode when not null
     * @param sectionService  used to save, and (in edit mode) to load the existing meetings
     */
    public SectionFormDialog(Section existing, List<Course> courses, List<Semester> semesters,
                              List<Instructor> instructors, List<Campus> campuses,
                              Semester currentSemester, SectionService sectionService) {
        this.sectionService = sectionService;
        this.editMode = existing != null;
        this.model = editMode ? existing : new Section();

        setTitle(editMode ? "Edit Section" : "Add Section");
        setHeaderText(existing != null ? "Editing " + existing.getSectionNumber() : "A section is one offering of one course in one semester.");
        getDialogPane().setMinWidth(700);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());

        courseBox.getItems().setAll(courses);
        courseBox.setMaxWidth(Double.MAX_VALUE);
        semesterBox.getItems().setAll(semesters);
        semesterBox.setMaxWidth(Double.MAX_VALUE);
        campusBox.getItems().setAll(campuses);
        campusBox.setMaxWidth(Double.MAX_VALUE);

        instructorBox.getItems().add(null);
        instructorBox.getItems().addAll(instructors);
        instructorBox.setConverter(new StringConverter<>() {
            @Override public String toString(Instructor i) { return i == null ? "TBA (not assigned yet)" : i.getFullName(); }
            @Override public Instructor fromString(String s) { return null; }
        });
        instructorBox.setMaxWidth(Double.MAX_VALUE);

        statusBox.getItems().setAll(SectionStatus.values());
        statusBox.setMaxWidth(Double.MAX_VALUE);

        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14, 14, 8, 14));
        int r = 0;
        g.addRow(r++, new Label("Course *"), courseBox, new Label("Semester *"), semesterBox);
        g.addRow(r++, new Label("Instructor"), instructorBox, new Label("Campus *"), campusBox);
        g.addRow(r++, new Label("Section number *"), sectionNumberField, new Label("Capacity *"), capacitySpinner);
        sectionNumberField.setPromptText("01");
        g.addRow(r, new Label("Room"), roomField, new Label("Status *"), statusBox);
        roomField.setPromptText("B204");

        getDialogPane().setContent(new VBox(8, g, buildScheduleEditor(), errorLabel));

        if (editMode) {
            fillFromModel(existing, courses, semesters, instructors, campuses);
        } else {
            statusBox.setValue(SectionStatus.OPEN);
            if (currentSemester != null) semesterBox.setValue(currentSemester);
        }

        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String problem = validateForm();
            if (problem != null) {
                showError(problem);
                event.consume();
                return;
            }
            writeToModel();
            try {
                sectionService.saveSection(model, new ArrayList<>(meetings));
            } catch (ServiceException se) {
                showError(se.getMessage());
                event.consume();
            }
        });

        setResultConverter(button -> button == ButtonType.OK);
    }

    // ------------------------------------------------------------------ schedule editor

    private TitledPane buildScheduleEditor() {
        dayBox.getItems().setAll(DayOfWeekCode.values());
        dayBox.setValue(DayOfWeekCode.SUN);
        startField.setPromptText("10:00");
        startField.setPrefWidth(80);
        endField.setPromptText("11:30");
        endField.setPrefWidth(80);
        addMeetingButton.setOnAction(e -> handleAddMeeting());
        removeMeetingButton.setOnAction(e -> {
            SectionSchedule selected = meetingTable.getSelectionModel().getSelectedItem();
            if (selected != null) meetings.remove(selected);
        });
        removeMeetingButton.disableProperty().bind(meetingTable.getSelectionModel().selectedItemProperty().isNull());

        TableColumn<SectionSchedule, String> dayCol = new TableColumn<>("Day");
        dayCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDayOfWeek().name()));
        dayCol.setPrefWidth(80);
        TableColumn<SectionSchedule, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(c -> new SimpleStringProperty(
                HM.format(c.getValue().getStartTime()) + "-" + HM.format(c.getValue().getEndTime())));
        timeCol.setPrefWidth(140);
        meetingTable.getColumns().setAll(List.of(dayCol, timeCol));
        meetingTable.setItems(meetings);
        meetingTable.setPrefHeight(150);
        meetingTable.setPlaceholder(new Label("No meetings added yet."));

        meetingErrorLabel.getStyleClass().add("error-text");
        meetingErrorLabel.setWrapText(true);
        meetingErrorLabel.setVisible(false);
        meetingErrorLabel.setManaged(false);

        Label hint = new Label(
                "A class that ends at 11:00 and one that starts at 11:00 do not conflict. Overlap means "
              + "(newStart < existingEnd) AND (newEnd > existingStart).");
        hint.getStyleClass().add("muted-text");
        hint.setWrapText(true);

        HBox addRow = new HBox(8, new Label("Day"), dayBox, new Label("Start"), startField,
                new Label("End"), endField, addMeetingButton);
        addRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox content = new VBox(8, addRow, meetingTable, removeMeetingButton, meetingErrorLabel, hint);
        content.setPadding(new Insets(10));

        TitledPane pane = new TitledPane("Weekly meetings (section_schedules)", content);
        pane.setCollapsible(false);
        return pane;
    }

    private void handleAddMeeting() {
        meetingErrorLabel.setVisible(false);
        meetingErrorLabel.setManaged(false);

        LocalTime start = ValidationUtil.parseTime(startField.getText());
        if (start == null) {
            showMeetingError("Start time must look like 10:00.");
            return;
        }
        LocalTime end = ValidationUtil.parseTime(endField.getText());
        if (end == null) {
            showMeetingError("End time must look like 11:30.");
            return;
        }
        if (!start.isBefore(end)) {
            showMeetingError("The end time must be after the start time.");
            return;
        }

        SectionSchedule candidate = new SectionSchedule();
        candidate.setDayOfWeek(dayBox.getValue());
        candidate.setStartTime(start);
        candidate.setEndTime(end);

        for (SectionSchedule existing : meetings) {
            if (candidate.clashesWith(existing)) {
                showMeetingError("Two meetings of this section overlap: " + label(existing) + " and " + label(candidate) + ".");
                return;
            }
        }

        meetings.add(candidate);
        startField.clear();
        endField.clear();
    }

    private String label(SectionSchedule m) {
        return m.getDayOfWeek().name() + " " + HM.format(m.getStartTime()) + "-" + HM.format(m.getEndTime());
    }

    private void showMeetingError(String message) {
        meetingErrorLabel.setText(message);
        meetingErrorLabel.setVisible(true);
        meetingErrorLabel.setManaged(true);
    }

    // ------------------------------------------------------------------ model

    private void fillFromModel(Section s, List<Course> courses, List<Semester> semesters,
                                List<Instructor> instructors, List<Campus> campuses) {
        courses.stream().filter(c -> c.getCourseId() == s.getCourseId()).findFirst().ifPresent(courseBox::setValue);
        semesters.stream().filter(x -> x.getSemesterId() == s.getSemesterId()).findFirst().ifPresent(semesterBox::setValue);
        if (s.getInstructorId() != null) {
            instructors.stream().filter(i -> i.getInstructorId() == s.getInstructorId()).findFirst()
                    .ifPresent(instructorBox::setValue);
        }
        campuses.stream().filter(c -> c.getCampusId() == s.getCampusId()).findFirst().ifPresent(campusBox::setValue);
        sectionNumberField.setText(s.getSectionNumber());
        capacitySpinner.getValueFactory().setValue(s.getCapacity());
        roomField.setText(s.getRoom());
        statusBox.setValue(s.getStatus());
        meetings.setAll(sectionService.listMeetings(s.getSectionId()));
    }

    private String validateForm() {
        clearErrorStyles();
        if (courseBox.getValue() == null) return mark(courseBox, "Select a course.");
        if (semesterBox.getValue() == null) return mark(semesterBox, "Select a semester.");
        if (campusBox.getValue() == null) return mark(campusBox, "Select a campus.");
        if (ValidationUtil.isBlank(sectionNumberField.getText())) {
            return mark(sectionNumberField, "Section number is required, for example 01.");
        }
        if (ValidationUtil.notBlank(roomField.getText()) && !ValidationUtil.maxLength(roomField.getText(), 20)) {
            return mark(roomField, "Room must be 20 characters or fewer.");
        }
        if (statusBox.getValue() == null) return mark(statusBox, "Select a status.");
        if (meetings.isEmpty()) {
            return "Add at least one weekly meeting. Without meeting times the timetable and the "
                 + "conflict rules cannot work.";
        }
        return null;
    }

    private void writeToModel() {
        model.setCourseId(courseBox.getValue().getCourseId());
        model.setSemesterId(semesterBox.getValue().getSemesterId());
        model.setInstructorId(instructorBox.getValue() == null ? null : instructorBox.getValue().getInstructorId());
        model.setCampusId(campusBox.getValue().getCampusId());
        model.setSectionNumber(sectionNumberField.getText().trim());
        model.setCapacity(capacitySpinner.getValue());
        model.setRoom(ValidationUtil.trimToNull(roomField.getText()));
        model.setStatus(statusBox.getValue());
    }

    private String mark(Control field, String message) {
        field.getStyleClass().add("field-error");
        field.requestFocus();
        return message;
    }

    private void clearErrorStyles() {
        for (Control c : List.<Control>of(courseBox, semesterBox, campusBox, sectionNumberField, roomField, statusBox)) {
            c.getStyleClass().remove("field-error");
        }
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }
}
