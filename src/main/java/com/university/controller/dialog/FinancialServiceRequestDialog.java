package com.university.controller.dialog;

import com.university.enums.UserRole;
import com.university.service.Session;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class FinancialServiceRequestDialog
        extends Dialog<FinancialServiceRequestDialog.Result> {

    public record Result(
            String requestType,
            String semester,
            String notes
    ) {}

    private final ComboBox<String> requestTypeBox = new ComboBox<>();
    private final TextField semesterField = new TextField();
    private final TextArea notesArea = new TextArea();
    private final Label errorLabel = new Label();

    public FinancialServiceRequestDialog() {
        Session.current().requireRole(UserRole.STUDENT);

        setTitle("Financial Service");
        setHeaderText("Submit Financial Service Request");

        getDialogPane().setMinWidth(560);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var css = getClass().getResource("/css/app.css");
        if (css != null) {
            getDialogPane().getStylesheets().add(css.toExternalForm());
        }

        requestTypeBox.getItems().addAll(
                "Payment Statement",
                "Payment Receipt",
                "Installment Request",
                "Payment Issue",
                "Refund Inquiry",
                "Other"
        );

        requestTypeBox.setMaxWidth(Double.MAX_VALUE);

        notesArea.setPrefRowCount(4);
        notesArea.setWrapText(true);

        TextField nameField = new TextField(Session.current().getDisplayName());
        nameField.setEditable(false);

        TextField idField = new TextField(
                String.valueOf(Session.current().getUser().getUserId())
        );
        idField.setEditable(false);

        errorLabel.getStyleClass().add("error-text");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(14));

        int r = 0;
        grid.addRow(r++, new Label("Student Name"), nameField);
        grid.addRow(r++, new Label("Student ID"), idField);
        grid.addRow(r++, new Label("Request Type *"), requestTypeBox);
        grid.addRow(r++, new Label("Semester *"), semesterField);
        grid.addRow(r++, new Label("Description / Notes"), notesArea);

        VBox content = new VBox(6, grid, errorLabel);
        content.setPadding(new Insets(0, 14, 12, 14));
        content.setMinHeight(Region.USE_PREF_SIZE);

        getDialogPane().setContent(content);

        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);

        ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String problem = validateForm();

            if (problem != null) {
                errorLabel.setText(problem);
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                event.consume();
            }
        });

        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }

            return new Result(
                    requestTypeBox.getValue(),
                    semesterField.getText().trim(),
                    notesArea.getText() == null ? "" : notesArea.getText().trim()
            );
        });
    }

    private String validateForm() {
        if (requestTypeBox.getValue() == null) {
            return "Please select a financial request type.";
        }

        if (semesterField.getText() == null || semesterField.getText().isBlank()) {
            return "Please enter the semester, for example Fall 2026.";
        }

        if (notesArea.getText() != null && notesArea.getText().length() > 500) {
            return "Description must be 500 characters or fewer.";
        }

        return null;
    }
}