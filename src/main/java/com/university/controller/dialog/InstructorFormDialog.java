package com.university.controller.dialog;

import com.university.enums.AcademicRank;
import com.university.model.Department;
import com.university.model.Instructor;
import com.university.service.AccountService;
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
 * <p>Mirrors {@link StudentFormDialog} exactly. The admin types no identity:
 * {@code InstructorService.create} takes the Instructor ID from SQL Server's
 * IDENTITY column, builds the university email from the name, and assigns the
 * mandatory {@code <Instructor ID>@iuL} password once the row exists.</p>
 *
 * <p>The Instructor ID keeps its place in the grid and is read-only in both
 * modes; the email is generated in add mode and editable in edit mode.</p>
 */
public final class InstructorFormDialog extends Dialog<Instructor> {

    private final TextField instructorIdField         = new TextField();
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
        setHeaderText(existing != null
                ? "Editing " + existing.getFirstName() + " " + existing.getLastName()
                : "A login account is created automatically. The Instructor ID, university email "
                  + "and temporary password are shown after saving.");
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
        g.addRow(r++, new Label("Instructor ID"),     instructorIdField,   new Label("Rank *"), rankBox);
        g.addRow(r++, new Label("First name *"),      firstNameField,      new Label("Last name *"), lastNameField);
        g.addRow(r++, new Label(editMode ? "Email *" : "Email"),
                                                      emailField,          new Label("Phone"),       phoneField);
        g.addRow(r++, new Label("Department *"),      departmentBox,       new Label("Hire date"),   hireDatePicker);

        // The Instructor ID is users.user_id and is never typed.
        instructorIdField.setEditable(false);
        instructorIdField.setFocusTraversable(false);

        VBox box = new VBox(6, g, errorLabel);
        box.setPadding(new Insets(0, 14, 12, 14));
        box.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(box);

        if (editMode) {
            fillFromModel(existing, departments);
        } else {
            instructorIdField.setPromptText("Generated automatically on save");
            emailField.setEditable(false);
            emailField.setFocusTraversable(false);
            emailField.setPromptText("Generated automatically from the name");
        }

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
        instructorIdField.setText(String.valueOf(i.getUserId()));
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

        if (ValidationUtil.isBlank(firstNameField.getText()) || !ValidationUtil.maxLength(firstNameField.getText(), 50)) {
            return mark(firstNameField, "First name is required (maximum 50 characters).");
        }
        if (ValidationUtil.isBlank(lastNameField.getText()) || !ValidationUtil.maxLength(lastNameField.getText(), 50)) {
            return mark(lastNameField, "Last name is required (maximum 50 characters).");
        }
        // Add mode generates the address, so there is nothing to check yet.
        if (editMode) {
            if (!ValidationUtil.isEmail(emailField.getText())
                    || !ValidationUtil.maxLength(emailField.getText(), 100)) {
                return mark(emailField, "Enter a valid email address, e.g. "
                        + "a.ali@" + AccountService.INSTRUCTOR_EMAIL_DOMAIN + ".");
            }
            String typed = emailField.getText().trim().toLowerCase();
            // The pre-split @university.edu.lb is still accepted for the fifty
            // instructors who have always used it — see AccountService.
            if (!typed.endsWith("@" + AccountService.INSTRUCTOR_EMAIL_DOMAIN)
                    && !typed.endsWith("@" + AccountService.LEGACY_INSTRUCTOR_EMAIL_DOMAIN)) {
                return mark(emailField, "An instructor email address must end in @"
                        + AccountService.INSTRUCTOR_EMAIL_DOMAIN + ".");
            }
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

    /** Neither the Instructor ID nor (in add mode) the email is the admin's to set. */
    private void writeToModel() {
        model.setFirstName(firstNameField.getText().trim());
        model.setLastName(lastNameField.getText().trim());
        if (editMode) {
            model.setEmail(emailField.getText().trim().toLowerCase());
        }
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
        for (Control c : List.<Control>of(firstNameField, lastNameField, emailField,
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
