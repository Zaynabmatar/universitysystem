package com.university.controller.dialog;

import com.university.model.Section;
import com.university.service.ExamService;
import com.university.service.ServiceException;
import com.university.util.ValidationUtil;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The instructor announces one exam for one section: date, start time, duration and room.
 * Built in Java like the other dialogs in this package (no dedicated FXML/phase entry for a
 * dialog) — see {@link SectionFormDialog}.
 *
 * <p>Saving here is what makes the exam show up on every enrolled student's Classes page: it
 * writes one {@code dbo.exams} row keyed only by {@code section_id}, and the student side reads
 * it back through their own enrollment, so no other wiring is needed.</p>
 */
public final class ExamFormDialog extends Dialog<Boolean> {

    private final DatePicker examDatePicker = new DatePicker();
    private final TextField startTimeField = new TextField();
    private final Spinner<Integer> durationSpinner = new Spinner<>(15, 300, 120, 15);
    private final TextField roomField = new TextField();
    private final Label errorLabel = new Label();

    private final ExamService examService;
    private final int sectionId;
    private final int createdByUserId;

    public ExamFormDialog(Section section, String sectionLabel, ExamService examService, int createdByUserId) {
        this.examService = examService;
        this.sectionId = section.getSectionId();
        this.createdByUserId = createdByUserId;

        setTitle("Add Exam");
        setResizable(true);
        initStyle(javafx.stage.StageStyle.UNDECORATED);
        setHeaderText("Every student enrolled in " + sectionLabel + " will see this exam.");

        getDialogPane().lookup(".header-panel").setStyle(
                "-fx-background-color: transparent;"
        );
        var addExamHeaderLabel = getDialogPane().lookup(".header-panel .label");
        if (addExamHeaderLabel != null) {
            addExamHeaderLabel.setStyle(
                    "-fx-text-fill: #243B80; -fx-font-weight: bold; -fx-font-size: 16px;"
            );
        }
        getDialogPane().setMinWidth(420);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) {
            getDialogPane().getStylesheets().add(css.toExternalForm());
        }

        examDatePicker.setPromptText("Exam date");
        startTimeField.setPromptText("10:00");
        roomField.setPromptText("Room");
        roomField.setText(section.getRoom());
        durationSpinner.setEditable(true);

        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14, 14, 8, 14));
        g.addRow(0, new Label("Exam date *"), examDatePicker);
        g.addRow(1, new Label("Start time *"), startTimeField);
        g.addRow(2, new Label("Duration (minutes) *"), durationSpinner);
        g.addRow(3, new Label("Room"), roomField);

        getDialogPane().setContent(new VBox(8, g, errorLabel));

        ButtonType ok = ButtonType.OK;
        getDialogPane().lookupButton(ok).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String problem = validate();
            if (problem != null) {
                showError(problem);
                event.consume();
                return;
            }
            try {
                examService.createExam(sectionId, examDatePicker.getValue(),
                        ValidationUtil.parseTime(startTimeField.getText()), durationSpinner.getValue(),
                        ValidationUtil.trimToNull(roomField.getText()), createdByUserId);
            } catch (ServiceException se) {
                showError(se.getMessage());
                event.consume();
            }
        });

        setResultConverter(button -> button == ButtonType.OK);
    }

    private String validate() {
        clearErrorStyles();
        if (examDatePicker.getValue() == null) {
            return mark(examDatePicker, "Select the exam date.");
        }
        if (ValidationUtil.parseTime(startTimeField.getText()) == null) {
            return mark(startTimeField, "Start time must look like 10:00.");
        }
        return null;
    }

    private String mark(Control field, String message) {
        field.getStyleClass().add("field-error");
        field.requestFocus();
        return message;
    }

    private void clearErrorStyles() {
        for (Control c : List.<Control>of(examDatePicker, startTimeField)) {
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




