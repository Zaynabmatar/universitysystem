package com.university.controller.dialog;

import com.university.service.Session;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class StudentServiceRequestDialog extends Dialog<StudentServiceRequestDialog.Result> {

    public record Result(
            String serviceName,
            String language,
            String purpose,
            String copyType,
            String notes
    ) {}

    private final ComboBox<String> languageBox = new ComboBox<>();
    private final ComboBox<String> purposeBox = new ComboBox<>();
    private final ComboBox<String> copyTypeBox = new ComboBox<>();
    private final TextArea notesArea = new TextArea();
    private final Label errorLabel = new Label();

    public StudentServiceRequestDialog(String serviceName) {
        Session session = Session.current();
        session.requireRole(com.university.enums.UserRole.STUDENT);

        setTitle(serviceName);
        setHeaderText("Submit " + serviceName + " Request");

        getDialogPane().setMinWidth(560);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var css = getClass().getResource("/css/app.css");
        if (css != null) {
            getDialogPane().getStylesheets().add(css.toExternalForm());
        }

        languageBox.getItems().addAll("English", "Arabic");
        purposeBox.getItems().addAll(
                "Personal Use",
                "Work",
                "Embassy",
                "Scholarship",
                "Bank",
                "Other"
        );
        copyTypeBox.getItems().addAll(
                "Physical Copy - Pickup",
                "Digital Copy"
        );

        languageBox.setMaxWidth(Double.MAX_VALUE);
        purposeBox.setMaxWidth(Double.MAX_VALUE);
        copyTypeBox.setMaxWidth(Double.MAX_VALUE);

        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);

        TextField nameField = new TextField(session.getDisplayName());
        nameField.setEditable(false);

        TextField idField = new TextField(String.valueOf(session.getUser().getUserId()));
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
        grid.addRow(r++, new Label("Language *"), languageBox);
        grid.addRow(r++, new Label("Purpose *"), purposeBox);
        grid.addRow(r++, new Label("Copy Type *"), copyTypeBox);
        grid.addRow(r++, new Label("Notes"), notesArea);

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
                    serviceName,
                    languageBox.getValue(),
                    purposeBox.getValue(),
                    copyTypeBox.getValue(),
                    notesArea.getText() == null ? "" : notesArea.getText().trim()
            );
        });
    }

    private String validateForm() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        if (languageBox.getValue() == null) {
            return "Please select the document language.";
        }

        if (purposeBox.getValue() == null) {
            return "Please select the request purpose.";
        }

        if (copyTypeBox.getValue() == null) {
            return "Please select the copy type.";
        }

        if (notesArea.getText() != null && notesArea.getText().length() > 500) {
            return "Notes must be 500 characters or fewer.";
        }

        return null;
    }
}
