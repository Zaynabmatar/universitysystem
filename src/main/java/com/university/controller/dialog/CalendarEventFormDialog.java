package com.university.controller.dialog;

import com.university.enums.CalendarEventType;
import com.university.model.CalendarEvent;
import com.university.model.Semester;
import com.university.util.ValidationUtil;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Add / Edit one Admin-authored calendar event. Built in Java rather than FXML, the same reason
 * {@link SemesterFormDialog} is: no dialog belongs in the sidebar's FXML list.
 *
 * <p>The event's semester is fixed to whichever semester the Academic Calendar screen is already
 * showing — it is context, not a field on this form, exactly like {@code isCurrent} is
 * deliberately absent from {@link SemesterFormDialog}. The color is likewise never a free choice:
 * picking a {@link CalendarEventType} is picking its one fixed color, shown live as a swatch next
 * to the type chooser.</p>
 */
public final class CalendarEventFormDialog extends Dialog<CalendarEvent> {

    private final TextField titleField = new TextField();
    private final ComboBox<CalendarEventType> typeBox = new ComboBox<>();
    private final Region colorSwatch = new Region();
    private final DatePicker startDatePicker = new DatePicker();
    private final DatePicker endDatePicker = new DatePicker();
    private final TextArea descriptionArea = new TextArea();
    private final Label errorLabel = new Label();

    private final boolean editMode;
    private final Semester semester;
    private final CalendarEvent model;

    /** Every type an Admin may pick — SEMESTER is derived from the semester itself, never chosen here. */
    private static final List<CalendarEventType> SELECTABLE_TYPES = Arrays.stream(CalendarEventType.values())
            .filter(t -> t != CalendarEventType.SEMESTER)
            .toList();

    /**
     * @param semester the semester this event belongs to (its dates bound what may be entered)
     * @param existing null = add mode; non-null = edit mode
     */
    public CalendarEventFormDialog(Semester semester, CalendarEvent existing) {
        this.semester = semester;
        this.editMode = existing != null;
        this.model = editMode ? existing : new CalendarEvent();

        setTitle(editMode ? "Edit Calendar Event" : "Add Calendar Event");
        setHeaderText((editMode ? "Editing an event in " : "Adding an event to ") + semester.getSemesterName()
                + " (" + semester.getStartDate() + " to " + semester.getEndDate() + ").");
        getDialogPane().setMinWidth(560);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());

        typeBox.getItems().setAll(SELECTABLE_TYPES);
        typeBox.setMaxWidth(Double.MAX_VALUE);
        typeBox.valueProperty().addListener((obs, old, chosen) -> updateSwatch(chosen));
        colorSwatch.getStyleClass().add("legend-swatch");
        colorSwatch.setMinSize(16, 16);
        colorSwatch.setPrefSize(16, 16);

        descriptionArea.setPromptText("Optional note shown under the calendar (holidays and university events only).");
        descriptionArea.setWrapText(true);
        descriptionArea.setPrefRowCount(3);

        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14));
        int r = 0;

        g.addRow(r++, new Label("Title *"), titleField);
        titleField.setPromptText("e.g. Winter Break, Founders' Day");

        HBox typeRow = new HBox(8, typeBox, colorSwatch);
        typeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        g.addRow(r++, new Label("Type *"), typeRow);

        g.addRow(r++, new Label("Start date *"), startDatePicker, new Label("End date *"), endDatePicker);

        g.add(new Label("Description"), 0, r);
        g.add(descriptionArea, 1, r++, 3, 1);

        VBox box = new VBox(6, g, errorLabel);
        box.setPadding(new Insets(0, 14, 12, 14));
        box.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(box);

        if (editMode) {
            fillFromModel(existing);
        } else {
            startDatePicker.setValue(semester.getStartDate());
            endDatePicker.setValue(semester.getStartDate());
        }

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

    /** Preselects the date a click on the calendar grid targeted — add mode only. */
    public CalendarEventFormDialog withDate(LocalDate date) {
        if (!editMode && date != null) {
            startDatePicker.setValue(date);
            endDatePicker.setValue(date);
        }
        return this;
    }

    private void updateSwatch(CalendarEventType type) {
        colorSwatch.getStyleClass().removeIf(c -> c.startsWith("cal-"));
        if (type != null) {
            colorSwatch.getStyleClass().add(type.getCssClass());
        }
    }

    private void fillFromModel(CalendarEvent event) {
        titleField.setText(event.getTitle());
        typeBox.setValue(event.getType());
        startDatePicker.setValue(event.getStartDate());
        endDatePicker.setValue(event.getEndDate());
        descriptionArea.setText(event.getDescription() == null ? "" : event.getDescription());
    }

    private String validate() {
        clearErrorStyles();

        if (ValidationUtil.isBlank(titleField.getText())) {
            return mark(titleField, "A title is required.");
        }
        if (!ValidationUtil.maxLength(titleField.getText(), 150)) {
            return mark(titleField, "Title must be 150 characters or fewer.");
        }
        if (typeBox.getValue() == null) {
            return mark(typeBox, "Select an event type.");
        }
        if (startDatePicker.getValue() == null || endDatePicker.getValue() == null) {
            return "Both a start and an end date are required.";
        }
        LocalDate start = startDatePicker.getValue();
        LocalDate end = endDatePicker.getValue();
        if (end.isBefore(start)) {
            return mark(endDatePicker, "The end date cannot be before the start date.");
        }
        if (start.isBefore(semester.getStartDate()) || end.isAfter(semester.getEndDate())) {
            return mark(startDatePicker, "Dates must fall within " + semester.getSemesterName()
                    + " (" + semester.getStartDate() + " to " + semester.getEndDate() + ").");
        }
        if (descriptionArea.getText() != null && descriptionArea.getText().length() > 500) {
            return mark(descriptionArea, "Description must be 500 characters or fewer.");
        }
        return null;
    }

    private void writeToModel() {
        model.setSemesterId(semester.getSemesterId());
        model.setTitle(titleField.getText().trim());
        model.setType(typeBox.getValue());
        model.setStartDate(startDatePicker.getValue());
        model.setEndDate(endDatePicker.getValue());
        String description = descriptionArea.getText();
        model.setDescription(description == null || description.isBlank() ? null : description.trim());
    }

    private String mark(Control field, String message) {
        field.getStyleClass().add("field-error");
        field.requestFocus();
        return message;
    }

    private void clearErrorStyles() {
        for (Control c : List.<Control>of(titleField, typeBox, startDatePicker, endDatePicker, descriptionArea)) {
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
