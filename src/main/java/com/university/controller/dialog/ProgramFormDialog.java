package com.university.controller.dialog;

import com.university.enums.DegreeType;
import com.university.model.Department;
import com.university.model.Program;
import com.university.util.ValidationUtil;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;

/**
 * Add / Edit program. Built in Java rather than FXML on purpose:
 * project_details.md Section 7 lists the complete set of FXML files and no
 * dialog appears in it.
 */
public final class ProgramFormDialog extends Dialog<Program> {

    private final TextField programCodeField          = new TextField();
    private final TextField programNameField          = new TextField();
    private final ComboBox<Department> departmentBox  = new ComboBox<>();
    private final ComboBox<DegreeType> degreeTypeBox   = new ComboBox<>();
    private final TextField totalCreditsField          = new TextField();
    private final Label errorLabel                     = new Label();

    private final boolean editMode;
    private final Program model;

    /**
     * @param existing    null = add mode; non-null = edit mode
     * @param departments every department, active or not (an existing program may sit in
     *                    one that has since been deactivated, and it must still show correctly)
     */
    public ProgramFormDialog(Program existing, List<Department> departments) {
        this.editMode = existing != null;
        this.model = editMode ? existing : new Program();

        setTitle(editMode ? "Edit Program" : "Add Program");
        setHeaderText(editMode ? "Editing " + existing.getProgramName() : "Create a new program.");
        getDialogPane().setMinWidth(480);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());

        departmentBox.getItems().setAll(departments);
        departmentBox.setConverter(new StringConverter<>() {
            @Override public String toString(Department d) { return d == null ? "" : d.toString(); }
            @Override public Department fromString(String s) { return null; }
        });
        degreeTypeBox.getItems().setAll(DegreeType.values());
        departmentBox.setMaxWidth(Double.MAX_VALUE);
        degreeTypeBox.setMaxWidth(Double.MAX_VALUE);
        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14));
        int r = 0;
        g.addRow(r++, new Label("Program code *"), programCodeField, new Label("Degree *"), degreeTypeBox);
        g.addRow(r++, new Label("Program name *"), programNameField);
        g.addRow(r++, new Label("Department *"), departmentBox, new Label("Total credits required *"), totalCreditsField);

        VBox box = new VBox(6, g, errorLabel);
        box.setPadding(new Insets(0, 14, 12, 14));
        box.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(box);

        if (editMode) fillFromModel(existing, departments);

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

    private void fillFromModel(Program p, List<Department> departments) {
        programCodeField.setText(p.getProgramCode());
        programNameField.setText(p.getProgramName());
        degreeTypeBox.setValue(p.getDegreeType());
        totalCreditsField.setText(String.valueOf(p.getTotalCreditsRequired()));
        departments.stream()
                .filter(d -> d.getDepartmentId() == p.getDepartmentId())
                .findFirst()
                .ifPresent(departmentBox::setValue);
    }

    /** @return null when everything is valid, otherwise the message to show the admin. */
    private String validate() {
        clearErrorStyles();

        if (!ValidationUtil.isShortCode(programCodeField.getText())) {
            return mark(programCodeField, "Program code must be 2-10 letters/digits, e.g. BSCS.");
        }
        if (ValidationUtil.isBlank(programNameField.getText()) || !ValidationUtil.maxLength(programNameField.getText(), 100)) {
            return mark(programNameField, "Program name is required (maximum 100 characters).");
        }
        if (departmentBox.getValue() == null) {
            return mark(departmentBox, "Select the program's department.");
        }
        if (degreeTypeBox.getValue() == null) {
            return mark(degreeTypeBox, "Select a degree type.");
        }
        if (!ValidationUtil.isIntInRange(totalCreditsField.getText(), 30, 300)) {
            return mark(totalCreditsField, "Total credits required must be a whole number between 30 and 300.");
        }
        return null;
    }

    private void writeToModel() {
        model.setProgramCode(programCodeField.getText().trim());
        model.setProgramName(programNameField.getText().trim());
        model.setDepartmentId(departmentBox.getValue().getDepartmentId());
        model.setDegreeType(degreeTypeBox.getValue());
        model.setTotalCreditsRequired(ValidationUtil.parseInt(totalCreditsField.getText()));
    }

    private String mark(Control field, String message) {
        field.getStyleClass().add("field-error");
        field.requestFocus();
        return message;
    }

    private void clearErrorStyles() {
        for (Control c : List.<Control>of(programCodeField, programNameField, departmentBox, degreeTypeBox, totalCreditsField)) {
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
