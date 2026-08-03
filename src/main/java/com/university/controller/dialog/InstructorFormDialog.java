package com.university.controller.dialog;

import com.university.enums.AcademicRank;
import com.university.model.Department;
import com.university.model.Instructor;
import com.university.util.ValidationUtil;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.List;

/**
 * Add / Edit instructor. Built in Java rather than FXML on purpose:
 * project_details.md Section 7 lists the complete set of FXML files and no
 * dialog appears in it.
 *
 * <p>There is no username or password field, mirroring {@link StudentFormDialog}:
 * {@link com.university.service.InstructorService#create} derives the login
 * username from the employee number and assigns the account's mandatory
 * {@code <user_id>@iuL} password once the row exists.</p>
 */
public class InstructorFormDialog extends Dialog<Instructor> {

    private final TextField employeeNumberField      = new TextField();
    private final TextField firstNameField            = new TextField();
    private final TextField lastNameField             = new TextField();
    private final TextField emailField                = new TextField();
    private final TextField phoneField                = new TextField();
    private final ComboBox<Department> departmentBox  = new ComboBox<>();
    private final ComboBox<AcademicRank> rankBox      = new ComboBox<>();
    private final DatePicker hireDatePicker           = new DatePicker();
    private final Label errorLabel                    = new Label();

    private final boolean editMode;
    private final Instructor model;

    /**
     * @param existing    null = add mode; non-null = edit mode
     * @param departments every department, active or not (an existing instructor may sit in
     *                    one that has since been deactivated, and it must still show correctly)
     */
    public InstructorFormDialog(Instructor existing, List<Department> departments) {
        this.editMode = existing != null;
        this.model = editMode ? existing : new Instructor();

        setTitle(editMode ? "Edit Instructor" : "Add Instructor");
        setHeaderText(editMode
                ? "Editing " + existing.getFirstName() + " " + existing.getLastName()
                : "A login account is created automatically. The temporary password is shown after saving.");
        getDialogPane().setMinWidth(560);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());

        departmentBox.getItems().setAll(departments);
        departmentBox.setConverter(new StringConverter<>() {
            @Override public String toString(Department d) { return d == null ? "" : d.toString(); }
            @Override public Department fromString(String s) { return null; }
        });
        rankBox.getItems().setAll(AcademicRank.values());
        departmentBox.setMaxWidth(Double.MAX_VALUE);
        rankBox.setMaxWidth(Double.MAX_VALUE);
        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14));
        int r = 0;
        g.addRow(r++, new Label("Employee number *"), employeeNumberField, new Label("Rank *"), rankBox);
        g.addRow(r++, new Label("First name *"),      firstNameField,      new Label("Last name *"), lastNameField);
        g.addRow(r++, new Label("Email *"),           emailField,          new Label("Phone"),       phoneField);
        g.addRow(r++, new Label("Department *"),      departmentBox,       new Label("Hire date"),   hireDatePicker);

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

    private void fillFromModel(Instructor i, List<Department> departments) {
        employeeNumberField.setText(i.getEmployeeNumber());
        firstNameField.setText(i.getFirstName());
        lastNameField.setText(i.getLastName());
        emailField.setText(i.getEmail());
        phoneField.setText(i.getPhone());
        rankBox.setValue(i.getAcademicRank());
        hireDatePicker.setValue(i.getHireDate());
        departments.stream()
                .filter(d -> d.getDepartmentId() == i.getDepartmentId())
                .findFirst()
                .ifPresent(departmentBox::setValue);
    }

    /** @return null when everything is valid, otherwise the message to show the admin. */
    private String validate() {
        clearErrorStyles();

        if (!ValidationUtil.isShortCode(employeeNumberField.getText())) {
            return mark(employeeNumberField, "Employee number must be 2-10 letters/digits, e.g. EMP9001.");
        }
        if (ValidationUtil.isBlank(firstNameField.getText()) || !ValidationUtil.maxLength(firstNameField.getText(), 50)) {
            return mark(firstNameField, "First name is required (maximum 50 characters).");
        }
        if (ValidationUtil.isBlank(lastNameField.getText()) || !ValidationUtil.maxLength(lastNameField.getText(), 50)) {
            return mark(lastNameField, "Last name is required (maximum 50 characters).");
        }
        if (!ValidationUtil.isEmail(emailField.getText()) || !ValidationUtil.maxLength(emailField.getText(), 100)) {
            return mark(emailField, "Enter a valid email address, e.g. ahmad@university.edu.");
        }
        if (ValidationUtil.notBlank(phoneField.getText()) && !ValidationUtil.isPhone(phoneField.getText())) {
            return mark(phoneField, "Phone number may contain digits, spaces, +, ( ) and -, 7-20 characters.");
        }
        if (departmentBox.getValue() == null) {
            return mark(departmentBox, "Select the instructor's department.");
        }
        if (rankBox.getValue() == null) {
            return mark(rankBox, "Select an academic rank.");
        }
        if (hireDatePicker.getValue() != null && hireDatePicker.getValue().isAfter(LocalDate.now())) {
            return mark(hireDatePicker, "Hire date cannot be in the future.");
        }
        return null;
    }

    private void writeToModel() {
        model.setEmployeeNumber(employeeNumberField.getText().trim());
        model.setFirstName(firstNameField.getText().trim());
        model.setLastName(lastNameField.getText().trim());
        model.setEmail(emailField.getText().trim());
        model.setPhone(ValidationUtil.trimToNull(phoneField.getText()));
        model.setDepartmentId(departmentBox.getValue().getDepartmentId());
        model.setAcademicRank(rankBox.getValue());
        model.setHireDate(hireDatePicker.getValue());
    }

    private String mark(Control field, String message) {
        field.getStyleClass().add("field-error");
        field.requestFocus();
        return message;
    }

    private void clearErrorStyles() {
        for (Control c : List.<Control>of(employeeNumberField, firstNameField, lastNameField, emailField,
                phoneField, departmentBox, rankBox, hireDatePicker)) {
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
