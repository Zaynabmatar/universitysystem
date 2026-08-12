package com.university.controller.dialog;

import com.university.enums.UserRole;
import com.university.service.Session;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class InternetServiceRequestDialog
        extends Dialog<InternetServiceRequestDialog.Result> {

    public record Result(
            String mobileNumber,
            String networkType,
            String packageSize,
            String activationType,
            String notes
    ) {}

    private final TextField mobileNumberField = new TextField();
    private final ComboBox<String> networkTypeBox = new ComboBox<>();
    private final ComboBox<String> packageSizeBox = new ComboBox<>();
    private final ComboBox<String> activationTypeBox = new ComboBox<>();
    private final TextArea notesArea = new TextArea();
    private final Label errorLabel = new Label();

    public InternetServiceRequestDialog() {
        Session.current().requireRole(UserRole.STUDENT);

        setTitle("University Internet Service");
        setHeaderText("Request Student Mobile Data Package");

        getDialogPane().setMinWidth(560);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var css = getClass().getResource("/css/app.css");
        if (css != null) {
            getDialogPane().getStylesheets().add(css.toExternalForm());
        }

        networkTypeBox.getItems().addAll(
                "3G",
                "4G",
                "5G"
        );

        packageSizeBox.getItems().addAll(
                "5 GB",
                "10 GB",
                "20 GB"
        );

        activationTypeBox.getItems().addAll(
                "New Activation",
                "Renewal"
        );

        networkTypeBox.setMaxWidth(Double.MAX_VALUE);
        packageSizeBox.setMaxWidth(Double.MAX_VALUE);
        activationTypeBox.setMaxWidth(Double.MAX_VALUE);

        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);

        TextField nameField =
                new TextField(Session.current().getDisplayName());
        nameField.setEditable(false);

        TextField idField =
                new TextField(String.valueOf(
                        Session.current().getUser().getUserId()
                ));
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
        grid.addRow(r++, new Label("Mobile Number *"), mobileNumberField);
        grid.addRow(r++, new Label("Network Type *"), networkTypeBox);
        grid.addRow(r++, new Label("Package Size *"), packageSizeBox);
        grid.addRow(r++, new Label("Activation Type *"), activationTypeBox);
        grid.addRow(r++, new Label("Notes"), notesArea);

        VBox content = new VBox(6, grid, errorLabel);
        content.setPadding(new Insets(0, 14, 12, 14));
        content.setMinHeight(Region.USE_PREF_SIZE);

        getDialogPane().setContent(content);

        Button ok =
                (Button) getDialogPane().lookupButton(ButtonType.OK);

        ok.addEventFilter(
                javafx.event.ActionEvent.ACTION,
                event -> {
                    String problem = validateForm();

                    if (problem != null) {
                        errorLabel.setText(problem);
                        errorLabel.setVisible(true);
                        errorLabel.setManaged(true);
                        event.consume();
                    }
                }
        );

        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }

            return new Result(
                    mobileNumberField.getText().trim(),
                    networkTypeBox.getValue(),
                    packageSizeBox.getValue(),
                    activationTypeBox.getValue(),
                    notesArea.getText() == null
                            ? ""
                            : notesArea.getText().trim()
            );
        });
    }

    private String validateForm() {

        if (mobileNumberField.getText() == null
                || mobileNumberField.getText().isBlank()) {
            return "Please enter your mobile number.";
        }

        if (networkTypeBox.getValue() == null) {
            return "Please select 3G, 4G, or 5G.";
        }

        if (packageSizeBox.getValue() == null) {
            return "Please select a data package.";
        }

        if (activationTypeBox.getValue() == null) {
            return "Please select New Activation or Renewal.";
        }

        if (notesArea.getText() != null
                && notesArea.getText().length() > 500) {
            return "Notes must be 500 characters or fewer.";
        }

        return null;
    }
}