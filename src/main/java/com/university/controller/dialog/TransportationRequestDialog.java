package com.university.controller.dialog;

import com.university.enums.UserRole;
import com.university.service.Session;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;

public final class TransportationRequestDialog
        extends Dialog<TransportationRequestDialog.Result> {

    public record Result(
            String pickupArea,
            String pickupPoint,
            String schedule,
            String phone,
            LocalDate startDate,
            String notes
    ) {}

    private final TextField pickupAreaField = new TextField();
    private final TextField pickupPointField = new TextField();
    private final ComboBox<String> scheduleBox = new ComboBox<>();
    private final TextField phoneField = new TextField();
    private final DatePicker startDatePicker = new DatePicker();
    private final TextArea notesArea = new TextArea();
    private final Label errorLabel = new Label();

    public TransportationRequestDialog() {
        Session.current().requireRole(UserRole.STUDENT);

        setTitle("Transportation Service");
        setHeaderText("Submit Transportation Service Request");

        getDialogPane().setMinWidth(560);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var css = getClass().getResource("/css/app.css");
        if (css != null) {
            getDialogPane().getStylesheets().add(css.toExternalForm());
        }

        scheduleBox.getItems().addAll("Morning", "Evening", "Both");
        scheduleBox.setMaxWidth(Double.MAX_VALUE);

        startDatePicker.setValue(LocalDate.now());
        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);

        errorLabel.getStyleClass().add("error-text");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);

        TextField nameField = new TextField(Session.current().getDisplayName());
        nameField.setEditable(false);

        TextField idField = new TextField(
                String.valueOf(Session.current().getUser().getUserId())
        );
        idField.setEditable(false);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(14));

        int r = 0;
        grid.addRow(r++, new Label("Student Name"), nameField);
        grid.addRow(r++, new Label("Student ID"), idField);
        grid.addRow(r++, new Label("Pickup Area *"), pickupAreaField);
        grid.addRow(r++, new Label("Pickup Point *"), pickupPointField);
        grid.addRow(r++, new Label("Schedule *"), scheduleBox);
        grid.addRow(r++, new Label("Phone Number *"), phoneField);
        grid.addRow(r++, new Label("Start Date *"), startDatePicker);
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
                    pickupAreaField.getText().trim(),
                    pickupPointField.getText().trim(),
                    scheduleBox.getValue(),
                    phoneField.getText().trim(),
                    startDatePicker.getValue(),
                    notesArea.getText() == null ? "" : notesArea.getText().trim()
            );
        });
    }

    private String validateForm() {
        if (pickupAreaField.getText() == null || pickupAreaField.getText().isBlank()) {
            return "Please enter your pickup area.";
        }

        if (pickupPointField.getText() == null || pickupPointField.getText().isBlank()) {
            return "Please enter your pickup point.";
        }

        if (scheduleBox.getValue() == null) {
            return "Please select Morning, Evening, or Both.";
        }

        if (phoneField.getText() == null || phoneField.getText().isBlank()) {
            return "Please enter your phone number.";
        }

        if (startDatePicker.getValue() == null) {
            return "Please select a start date.";
        }

        if (notesArea.getText() != null && notesArea.getText().length() > 500) {
            return "Notes must be 500 characters or fewer.";
        }

        return null;
    }
}