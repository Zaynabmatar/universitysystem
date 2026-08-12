package com.university.controller.dialog;

import com.university.enums.UserRole;
import com.university.service.Session;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class ITSupportRequestDialog
        extends Dialog<ITSupportRequestDialog.Result> {

    public record Result(String problemType, String description) {}

    private final ComboBox<String> problemTypeBox = new ComboBox<>();
    private final TextArea descriptionArea = new TextArea();
    private final Label errorLabel = new Label();

    public ITSupportRequestDialog() {
        Session.current().requireRole(UserRole.STUDENT);

        setTitle("IT Support");
        setHeaderText("Submit IT Support Request");

        getDialogPane().setMinWidth(560);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var css = getClass().getResource("/css/app.css");
        if (css != null) {
            getDialogPane().getStylesheets().add(css.toExternalForm());
        }

        problemTypeBox.getItems().addAll(
                "Login Problem",
                "Password Problem",
                "University Email Problem",
                "Moodle / LMS Problem",
                "Account Locked",
                "System Error",
                "Other"
        );
        problemTypeBox.setMaxWidth(Double.MAX_VALUE);

        descriptionArea.setPrefRowCount(5);
        descriptionArea.setWrapText(true);

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
        grid.addRow(r++, new Label("Problem Type *"), problemTypeBox);
        grid.addRow(r++, new Label("Description *"), descriptionArea);

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
            if (button != ButtonType.OK) return null;

            return new Result(
                    problemTypeBox.getValue(),
                    descriptionArea.getText().trim()
            );
        });
    }

    private String validateForm() {
        if (problemTypeBox.getValue() == null) {
            return "Please select the problem type.";
        }

        if (descriptionArea.getText() == null || descriptionArea.getText().isBlank()) {
            return "Please describe the problem.";
        }

        if (descriptionArea.getText().length() > 1000) {
            return "Description must be 1000 characters or fewer.";
        }

        return null;
    }
}