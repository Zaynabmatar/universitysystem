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
public final class SemesterFormDialog extends Dialog<Semester> {

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

    private final DatePicker evaluationStartDatePicker = new DatePicker();
    private final TextField evaluationStartTimeField   = new TextField();
    private final DatePicker evaluationEndDatePicker   = new DatePicker();
    private final TextField evaluationEndTimeField     = new TextField();

    private final Label errorLabel = new Label();

    private final boolean editMode;
    private final Semester model;

    /** @param existing null = add mode; non-null = edit mode */
    public SemesterFormDialog(Semester existing) {
        this.editMode = existing != null;
        this.model = editMode ? existing : new Semester();

        setTitle(editMode ? "Edit Semester" : "Add Semester");
        setHeaderText(existing != null ? "Editing " + existing.getSemesterName() : "Create a new semester.");
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

        evaluationStartTimeField.setPromptText("09:00");
        evaluationEndTimeField.setPromptText("17:00");
        evaluationStartTimeField.setPrefWidth(80);
        evaluationEndTimeField.setPrefWidth(80);
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

        g.add(sectionHeader("Instructor evaluation window"), 0, r++, 4, 1);
        g.addRow(r++,
                new Label("Evaluation start"),
                dateTimeBox(evaluationStartDatePicker, evaluationStartTimeField),
                new Label("Evaluation end"),
                dateTimeBox(evaluationEndDatePicker, evaluationEndTimeField));

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

        // In add mode, keep the evaluation window pre-filled with its default (start date /
        // end date + 21 days) as the admin edits the term dates, right up until they type into
        // the evaluation fields themselves — SemesterService applies the same default again on
        // save regardless, this is purely so the dialog does not show blank fields meanwhile.
        startDatePicker.valueProperty().addListener((obs, old, val) -> defaultEvaluationStartIfUntouched());
        endDatePicker.valueProperty().addListener((obs, old, val) -> defaultEvaluationEndIfUntouched());

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

    /** Mirrors {@code SemesterService}'s default: evaluation start = semester start date. */
    private void defaultEvaluationStartIfUntouched() {
        if (editMode || evaluationStartDatePicker.getValue() != null
                || !ValidationUtil.isBlank(evaluationStartTimeField.getText())) {
            return;
        }
        LocalDate start = startDatePicker.getValue();
        if (start == null) return;
        evaluationStartDatePicker.setValue(start);
        evaluationStartTimeField.setText("00:00");
    }

    /** Mirrors {@code SemesterService}'s default: evaluation end = semester end date + 21 days. */
    private void defaultEvaluationEndIfUntouched() {
        if (editMode || evaluationEndDatePicker.getValue() != null
                || !ValidationUtil.isBlank(evaluationEndTimeField.getText())) {
            return;
        }
        LocalDate end = endDatePicker.getValue();
        if (end == null) return;
        evaluationEndDatePicker.setValue(end.plusDays(21));
        evaluationEndTimeField.setText("23:59");
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

        if (s.getEvaluationStart() != null) {
            evaluationStartDatePicker.setValue(s.getEvaluationStart().toLocalDate());
            evaluationStartTimeField.setText(s.getEvaluationStart().toLocalTime().toString());
        }

        if (s.getEvaluationEnd() != null) {
            evaluationEndDatePicker.setValue(s.getEvaluationEnd().toLocalDate());
            evaluationEndTimeField.setText(s.getEvaluationEnd().toLocalTime().toString());
        }
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

        if (evaluationStartDatePicker.getValue() != null
                && ValidationUtil.parseTime(evaluationStartTimeField.getText()) == null) {
            return mark(evaluationStartTimeField, "Evaluation start time must look like 09:00.");
        }
        if (evaluationEndDatePicker.getValue() != null
                && ValidationUtil.parseTime(evaluationEndTimeField.getText()) == null) {
            return mark(evaluationEndTimeField, "Evaluation end time must look like 17:00.");
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
        boolean evaluationStartPresent = evaluationStartDatePicker.getValue() != null
                || !ValidationUtil.isBlank(evaluationStartTimeField.getText());
        boolean evaluationEndPresent = evaluationEndDatePicker.getValue() != null
                || !ValidationUtil.isBlank(evaluationEndTimeField.getText());

        if (evaluationStartPresent != evaluationEndPresent) {
            return "Evaluation start and end must either both be set or both be left blank.";
        }

        if (evaluationStartPresent) {
            LocalDateTime evaluationStart =
                    combine(evaluationStartDatePicker, evaluationStartTimeField);
            LocalDateTime evaluationEnd =
                    combine(evaluationEndDatePicker, evaluationEndTimeField);

            if (evaluationStart == null || evaluationEnd == null) {
                return "Evaluation start and end must include a valid date and time.";
            }

            if (!evaluationStart.isBefore(evaluationEnd)) {
                return mark(evaluationEndTimeField, "Evaluation must end after it starts.");
            }

            // The window is free to run past the semester's own end date (that is the whole
            // point — instructors are evaluated once the term is over), but never past 21 days
            // after it. Whether it must be pulled in further still, so it ends before the next
            // semester starts, needs the other semesters' dates and is enforced by
            // SemesterService when the dialog's result is saved, the same way a term-date
            // overlap with another semester is.
            LocalDateTime latestEnd = end.plusDays(21).atTime(23, 59, 59);
            if (evaluationEnd.isAfter(latestEnd)) {
                return mark(evaluationEndDatePicker, "Evaluation end cannot be later than 21 days "
                        + "after the semester ends (" + latestEnd.toLocalDate() + ").");
            }
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

        model.setEvaluationStart(
                evaluationStartDatePicker.getValue() == null
                        ? null
                        : combine(evaluationStartDatePicker, evaluationStartTimeField));

        model.setEvaluationEnd(
                evaluationEndDatePicker.getValue() == null
                        ? null
                        : combine(evaluationEndDatePicker, evaluationEndTimeField));
    }

    private String mark(Control field, String message) {
        field.getStyleClass().add("field-error");
        field.requestFocus();
        return message;
    }

    private void clearErrorStyles() {
        for (Control c : List.<Control>of(nameField, academicYearField, termBox, startDatePicker, endDatePicker,
                regStartDatePicker, regStartTimeField, regEndDatePicker, regEndTimeField,
                dropDeadlinePicker, withdrawDeadlinePicker, gradeEntryStartPicker, gradeEntryEndPicker,
                evaluationStartDatePicker, evaluationStartTimeField,
                evaluationEndDatePicker, evaluationEndTimeField)) {
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
