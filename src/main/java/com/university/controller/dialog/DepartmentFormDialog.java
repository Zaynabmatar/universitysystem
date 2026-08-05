package com.university.controller.dialog;

import com.university.model.Department;
import com.university.util.ValidationUtil;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Add / Edit department. Built in Java rather than FXML on purpose:
 * project_details.md Section 7 lists the complete set of FXML files and no
 * dialog appears in it.
 */
public final class DepartmentFormDialog extends Dialog<Department> {

    private final TextField departmentCodeField = new TextField();
    private final TextField departmentNameField = new TextField();
    private final Label errorLabel = new Label();

    private final boolean editMode;
    private final Department model;

    /** @param existing null = add mode; non-null = edit mode */
    public DepartmentFormDialog(Department existing) {
        this.editMode = existing != null;
        this.model = editMode ? existing : new Department();

        setTitle(editMode ? "Edit Department" : "Add Department");
        setHeaderText(existing != null ? "Editing " + existing.getDepartmentName() : "Create a new department.");
        getDialogPane().setMinWidth(420);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());

        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14));
        g.addRow(0, new Label("Department code *"), departmentCodeField);
        g.addRow(1, new Label("Department name *"), departmentNameField);

        VBox box = new VBox(6, g, errorLabel);
        box.setPadding(new Insets(0, 14, 12, 14));
        box.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(box);

        if (editMode) fillFromModel(existing);

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

    private void fillFromModel(Department d) {
        departmentCodeField.setText(d.getDepartmentCode());
        departmentNameField.setText(d.getDepartmentName());
    }

    /** @return null when everything is valid, otherwise the message to show the admin. */
    private String validate() {
        clearErrorStyles();

        if (!ValidationUtil.isShortCode(departmentCodeField.getText())) {
            return mark(departmentCodeField, "Department code must be 2-10 letters/digits, e.g. CS.");
        }
        if (ValidationUtil.isBlank(departmentNameField.getText()) || !ValidationUtil.maxLength(departmentNameField.getText(), 100)) {
            return mark(departmentNameField, "Department name is required (maximum 100 characters).");
        }
        return null;
    }

    private void writeToModel() {
        model.setDepartmentCode(departmentCodeField.getText().trim());
        model.setDepartmentName(departmentNameField.getText().trim());
    }

    private String mark(Control field, String message) {
        field.getStyleClass().add("field-error");
        field.requestFocus();
        return message;
    }

    private void clearErrorStyles() {
        for (Control c : List.<Control>of(departmentCodeField, departmentNameField)) {
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
