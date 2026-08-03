package com.university.controller.dialog;

import com.university.enums.Term;
import com.university.model.Semester;
import com.university.util.ValidationUtil;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Add / Edit semester. Built in Java rather than FXML on purpose:
 * project_details.md Section 7 lists the complete set of FXML files and no
 * dialog appears in it.
 *
 * <p>{@code isCurrent} is deliberately not on this form — it is a
 * whole-table transaction ("clear every row, then set one"), not a field of
 * a single row, and is changed only by the "Make This The Current Semester"
 * button on the screen.</p>
 */
public class SemesterFormDialog extends Dialog<Semester> {

    private final TextField nameField          = new TextField();
    private final TextField academicYearField  = new TextField();
    private final ComboBox<Term> termBox        = new ComboBox<>();

    private final DatePicker startDatePicker    = new DatePicker();
    private final DatePicker endDatePicker      = new DatePicker();

    private final DatePicker regStartDatePicker = new DatePicker();
    private final TextField regStartTimeField   = new TextField();
    private final DatePicker regEndDatePicker   = new DatePicker();
    private final TextField regEndTimeField     = new TextField();

    private final DatePicker dropDeadlinePicker     = new DatePicker();
    private final DatePicker withdrawDeadlinePicker = new DatePicker();
    private final DatePicker gradeEntryStartPicker  = new DatePicker();
    private final DatePicker gradeEntryEndPicker    = new DatePicker();

    private final Label errorLabel = new Label();

    private final boolean editMode;
    private final Semester model;

    /** @param existing null = add mode; non-null = edit mode */
    public SemesterFormDialog(Semester existing) {
        this.editMode = existing != null;
        this.model = editMode ? existing : new Semester();

        setTitle(editMode ? "Edit Semester" : "Add Semester");
        setHeaderText(editMode ? "Editing " + existing.getSemesterName() : "Create a new semester.");
        getDialogPane().setMinWidth(620);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());

        termBox.getItems().setAll(Term.values());
        termBox.setMaxWidth(Double.MAX_VALUE);
        regStartTimeField.setPromptText("09:00");
        regEndTimeField.setPromptText("17:00");
        regStartTimeField.setPrefWidth(80);
        regEndTimeField.setPrefWidth(80);
        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14));
        int r = 0;

        g.add(sectionHeader("Identity"), 0, r++, 4, 1);
        g.addRow(r++, new Label("Semester name *"), nameField, new Label("Academic year *"), academicYearField);
        academicYearField.setPromptText("2025-2026");
        g.addRow(r++, new Label("Term *"), termBox);

        g.add(sectionHeader("Term dates"), 0, r++, 4, 1);
        g.addRow(r++, new Label("Start date *"), startDatePicker, new Label("End date *"), endDatePicker);

        g.add(sectionHeader("Registration window"), 0, r++, 4, 1);
        g.addRow(r++, new Label("Registration start *"), dateTimeBox(regStartDatePicker, regStartTimeField),
                       new Label("Registration end *"), dateTimeBox(regEndDatePicker, regEndTimeField));

        g.add(sectionHeader("Drop & withdraw deadlines"), 0, r++, 4, 1);
        g.addRow(r++, new Label("Drop deadline *"), dropDeadlinePicker, new Label("Withdraw deadline *"), withdrawDeadlinePicker);

        g.add(sectionHeader("Grade entry window"), 0, r++, 4, 1);
        g.addRow(r++, new Label("Grade entry start *"), gradeEntryStartPicker, new Label("Grade entry end *"), gradeEntryEndPicker);

        Label helpText = new Label(
                "Before the drop deadline a student may drop with no trace. Between the drop deadline and the "
              + "withdrawal deadline a drop becomes a W on the transcript. After the withdrawal deadline nothing "
              + "can be dropped at all.");
        helpText.getStyleClass().add("muted-text");
        helpText.setWrapText(true);

        VBox box = new VBox(6, g, helpText, errorLabel);
        box.setPadding(new Insets(0, 14, 12, 14));
        box.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(box);

        if (editMode) {
            fillFromModel(existing);
        } else {
            applyDefaults();
        }

        // Block the dialog from closing while anything is invalid.
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String problem = validate();
            if (problem != null) {
                showError(problem);
                event.consume();
            }
        });

        setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            writeToModel();
            return model;
        });
    }

    private Label sectionHeader(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    private javafx.scene.layout.HBox dateTimeBox(DatePicker date, TextField time) {
        date.setPrefWidth(140);
        return new javafx.scene.layout.HBox(6, date, time);
    }

    /** Sensible starting values for the fields the checklist's tests do not touch directly. */
    private void applyDefaults() {
        LocalDate today = LocalDate.now();
        regStartDatePicker.setValue(today);
        regStartTimeField.setText("09:00");
        regEndDatePicker.setValue(today.plusDays(7));
        regEndTimeField.setText("17:00");
        dropDeadlinePicker.setValue(today.plusDays(30));
        withdrawDeadlinePicker.setValue(today.plusDays(60));
        gradeEntryStartPicker.setValue(today.plusDays(90));
        gradeEntryEndPicker.setValue(today.plusDays(97));
    }

    private void fillFromModel(Semester s) {
        nameField.setText(s.getSemesterName());
        academicYearField.setText(s.getAcademicYear());
        termBox.setValue(s.getTerm());
        startDatePicker.setValue(s.getStartDate());
        endDatePicker.setValue(s.getEndDate());
        if (s.getRegistrationStart() != null) {
            regStartDatePicker.setValue(s.getRegistrationStart().toLocalDate());
            regStartTimeField.setText(s.getRegistrationStart().toLocalTime().toString());
        }
        if (s.getRegistrationEnd() != null) {
            regEndDatePicker.setValue(s.getRegistrationEnd().toLocalDate());
            regEndTimeField.setText(s.getRegistrationEnd().toLocalTime().toString());
        }
        dropDeadlinePicker.setValue(s.getDropDeadline());
        withdrawDeadlinePicker.setValue(s.getWithdrawDeadline());
        gradeEntryStartPicker.setValue(s.getGradeEntryStart());
        gradeEntryEndPicker.setValue(s.getGradeEntryEnd());
    }

    private LocalDateTime combine(DatePicker datePicker, TextField timeField) {
        LocalDate date = datePicker.getValue();
        LocalTime time = ValidationUtil.parseTime(timeField.getText());
        return (date == null || time == null) ? null : LocalDateTime.of(date, time);
    }

    /** @return null when everything is valid, otherwise the message to show the admin. */
    private String validate() {
        clearErrorStyles();

        if (ValidationUtil.isBlank(nameField.getText())) {
            return mark(nameField, "Semester name is required, for example Fall 2025.");
        }
        if (!ValidationUtil.maxLength(nameField.getText(), 50)) {
            return mark(nameField, "Semester name must be 50 characters or fewer.");
        }
        if (academicYearField.getText() == null || !academicYearField.getText().trim().matches("\\d{4}-\\d{4}")) {
            return mark(academicYearField, "Academic year must look like 2025-2026.");
        }
        if (termBox.getValue() == null) {
            return mark(termBox, "Select a term: Fall, Spring or Summer.");
        }
        if (regStartDatePicker.getValue() != null && ValidationUtil.parseTime(regStartTimeField.getText()) == null) {
            return mark(regStartTimeField, "Registration start time must look like 09:00.");
        }
        if (regEndDatePicker.getValue() != null && ValidationUtil.parseTime(regEndTimeField.getText()) == null) {
            return mark(regEndTimeField, "Registration end time must look like 17:00.");
        }

        LocalDateTime registrationStart = combine(regStartDatePicker, regStartTimeField);
        LocalDateTime registrationEnd = combine(regEndDatePicker, regEndTimeField);

        if (startDatePicker.getValue() == null || endDatePicker.getValue() == null
                || registrationStart == null || registrationEnd == null
                || dropDeadlinePicker.getValue() == null || withdrawDeadlinePicker.getValue() == null
                || gradeEntryStartPicker.getValue() == null || gradeEntryEndPicker.getValue() == null) {
            return "All eight dates are required.";
        }

        LocalDate start = startDatePicker.getValue();
        LocalDate end = endDatePicker.getValue();

        if (!start.isBefore(end)) {
            return mark(endDatePicker, "The end date must be after the start date.");
        }
        if (!registrationStart.isBefore(registrationEnd)) {
            return mark(regEndTimeField, "Registration must end after it starts.");
        }
        if (registrationEnd.toLocalDate().isAfter(end)) {
            return mark(regEndDatePicker, "Registration must close on or before the semester ends.");
        }
        if (dropDeadlinePicker.getValue().isBefore(start)) {
            return mark(dropDeadlinePicker, "The drop deadline cannot be before the semester starts.");
        }
        if (withdrawDeadlinePicker.getValue().isBefore(dropDeadlinePicker.getValue())) {
            return mark(withdrawDeadlinePicker, "The withdrawal deadline must be on or after the drop deadline.");
        }
        if (withdrawDeadlinePicker.getValue().isAfter(end)) {
            return mark(withdrawDeadlinePicker, "The withdrawal deadline cannot be after the semester ends.");
        }
        if (gradeEntryStartPicker.getValue().isBefore(start)) {
            return mark(gradeEntryStartPicker, "Grade entry cannot start before the semester starts.");
        }
        if (gradeEntryEndPicker.getValue().isBefore(gradeEntryStartPicker.getValue())) {
            return mark(gradeEntryEndPicker, "Grade entry must end on or after it starts.");
        }
        return null;
    }

    private void writeToModel() {
        model.setSemesterName(nameField.getText().trim());
        model.setAcademicYear(academicYearField.getText().trim());
        model.setTerm(termBox.getValue());
        model.setStartDate(startDatePicker.getValue());
        model.setEndDate(endDatePicker.getValue());
        model.setRegistrationStart(combine(regStartDatePicker, regStartTimeField));
        model.setRegistrationEnd(combine(regEndDatePicker, regEndTimeField));
        model.setDropDeadline(dropDeadlinePicker.getValue());
        model.setWithdrawDeadline(withdrawDeadlinePicker.getValue());
        model.setGradeEntryStart(gradeEntryStartPicker.getValue());
        model.setGradeEntryEnd(gradeEntryEndPicker.getValue());
    }

    private String mark(Control field, String message) {
        field.getStyleClass().add("field-error");
        field.requestFocus();
        return message;
    }

    private void clearErrorStyles() {
        for (Control c : List.<Control>of(nameField, academicYearField, termBox, startDatePicker, endDatePicker,
                regStartDatePicker, regStartTimeField, regEndDatePicker, regEndTimeField,
                dropDeadlinePicker, withdrawDeadlinePicker, gradeEntryStartPicker, gradeEntryEndPicker)) {
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
