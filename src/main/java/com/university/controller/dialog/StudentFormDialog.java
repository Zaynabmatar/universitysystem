package com.university.controller.dialog;

import com.university.enums.Gender;
import com.university.enums.StudentStatus;
import com.university.model.Program;
import com.university.model.Student;
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
 * Add / Edit student. Built in Java rather than FXML on purpose:
 * project_details.md Section 7 lists the complete set of FXML files and no
 * dialog appears in it.
 *
 * <p>There is no username or password field: {@link com.university.service.StudentService#create}
 * derives the login username from the student number and assigns the account's
 * mandatory {@code <user_id>@iuL} password itself, once the row exists. The
 * caller shows that password to the admin after the dialog closes.</p>
 *
 * <p>Validation happens before the dialog closes — the OK button's close is
 * consumed until every field is valid, and a message appears under the form
 * (project_details.md Section 13).</p>
 */
public final class StudentFormDialog extends Dialog<Student> {

    private final TextField studentNumberField     = new TextField();
    private final TextField firstNameField         = new TextField();
    private final TextField lastNameField          = new TextField();
    private final TextField emailField              = new TextField();
    private final TextField phoneField              = new TextField();
    private final DatePicker dobPicker              = new DatePicker();
    private final ComboBox<Gender> genderBox        = new ComboBox<>();
    private final TextArea addressField             = new TextArea();
    private final ComboBox<Program> programBox      = new ComboBox<>();
    private final DatePicker admissionPicker        = new DatePicker();
    private final ComboBox<StudentStatus> statusBox = new ComboBox<>();
    private final Label errorLabel                  = new Label();

    private final boolean editMode;
    private final Student model;

    /**
     * @param existing null = add mode; non-null = edit mode
     * @param programs every program, active or not (an existing student may sit on one
     *                 that has since been deactivated, and it must still show correctly)
     */
    public StudentFormDialog(Student existing, List<Program> programs) {
        this.editMode = existing != null;
        this.model = editMode ? existing : new Student();

        setTitle(editMode ? "Edit Student" : "Add Student");
        setHeaderText(editMode
                ? "Editing " + existing.getFirstName() + " " + existing.getLastName()
                : "A login account is created automatically. The temporary password is shown after saving.");
        getDialogPane().setMinWidth(620);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());

        genderBox.getItems().setAll(Gender.values());
        statusBox.getItems().setAll(StudentStatus.values());
        programBox.getItems().setAll(programs);
        programBox.setConverter(new StringConverter<>() {
            @Override public String toString(Program p) { return p == null ? "" : p.toString(); }
            @Override public Program fromString(String s) { return null; }
        });
        programBox.setMaxWidth(Double.MAX_VALUE);
        genderBox.setMaxWidth(Double.MAX_VALUE);
        statusBox.setMaxWidth(Double.MAX_VALUE);
        addressField.setPrefRowCount(2);
        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14));
        int r = 0;
        g.addRow(r++, new Label("Student number *"), studentNumberField, new Label("Status *"), statusBox);
        g.addRow(r++, new Label("First name *"),     firstNameField,     new Label("Last name *"), lastNameField);
        g.addRow(r++, new Label("Email *"),          emailField,         new Label("Phone"),       phoneField);
        g.addRow(r++, new Label("Date of birth"),    dobPicker,          new Label("Gender"),      genderBox);
        g.addRow(r++, new Label("Program *"),        programBox,         new Label("Admission date *"), admissionPicker);
        g.addRow(r++, new Label("Address"),          addressField);
        GridPane.setColumnSpan(addressField, 3);

        VBox box = new VBox(6, g, errorLabel);
        box.setPadding(new Insets(0, 14, 12, 14));
        box.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(box);

        if (editMode) fillFromModel(existing, programs);
        else {
            statusBox.setValue(StudentStatus.ACTIVE);
            admissionPicker.setValue(LocalDate.now());
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

    private void fillFromModel(Student s, List<Program> programs) {
        studentNumberField.setText(s.getStudentNumber());
        firstNameField.setText(s.getFirstName());
        lastNameField.setText(s.getLastName());
        emailField.setText(s.getEmail());
        phoneField.setText(s.getPhone());
        dobPicker.setValue(s.getDateOfBirth());
        genderBox.setValue(s.getGender());
        addressField.setText(s.getAddress());
        admissionPicker.setValue(s.getAdmissionDate());
        statusBox.setValue(s.getStatus());
        programs.stream()
                .filter(p -> p.getProgramId() == s.getProgramId())
                .findFirst()
                .ifPresent(programBox::setValue);
    }

    /** @return null when everything is valid, otherwise the message to show the admin. */
    private String validate() {
        clearErrorStyles();

        if (!ValidationUtil.isStudentNumber(studentNumberField.getText())) {
            return mark(studentNumberField, "Student number must be 4-20 digits, e.g. 2021001234.");
        }
        if (ValidationUtil.isBlank(firstNameField.getText()) || !ValidationUtil.maxLength(firstNameField.getText(), 50)) {
            return mark(firstNameField, "First name is required (maximum 50 characters).");
        }
        if (ValidationUtil.isBlank(lastNameField.getText()) || !ValidationUtil.maxLength(lastNameField.getText(), 50)) {
            return mark(lastNameField, "Last name is required (maximum 50 characters).");
        }
        if (!ValidationUtil.isEmail(emailField.getText()) || !ValidationUtil.maxLength(emailField.getText(), 100)) {
            return mark(emailField, "Enter a valid email address, e.g. sara@university.edu.");
        }
        if (ValidationUtil.notBlank(phoneField.getText()) && !ValidationUtil.isPhone(phoneField.getText())) {
            return mark(phoneField, "Phone number may contain digits, spaces, +, ( ) and -, 7-20 characters.");
        }
        if (dobPicker.getValue() != null) {
            if (!dobPicker.getValue().isBefore(LocalDate.now())) {
                return mark(dobPicker, "Date of birth must be in the past.");
            }
            if (dobPicker.getValue().isAfter(LocalDate.now().minusYears(15))) {
                return mark(dobPicker, "The student must be at least 15 years old.");
            }
        }
        if (!ValidationUtil.maxLength(addressField.getText(), 200)) {
            return mark(addressField, "Address must be 200 characters or fewer.");
        }
        if (programBox.getValue() == null) {
            return mark(programBox, "Select the student's program.");
        }
        if (admissionPicker.getValue() == null) {
            return mark(admissionPicker, "Admission date is required.");
        }
        if (admissionPicker.getValue().isAfter(LocalDate.now())) {
            return mark(admissionPicker, "Admission date cannot be in the future.");
        }
        if (statusBox.getValue() == null) {
            return mark(statusBox, "Select a status.");
        }
        return null;
    }

    private void writeToModel() {
        model.setStudentNumber(studentNumberField.getText().trim());
        model.setFirstName(firstNameField.getText().trim());
        model.setLastName(lastNameField.getText().trim());
        model.setEmail(emailField.getText().trim());
        model.setPhone(ValidationUtil.trimToNull(phoneField.getText()));
        model.setDateOfBirth(dobPicker.getValue());
        model.setGender(genderBox.getValue());
        model.setAddress(ValidationUtil.trimToNull(addressField.getText()));
        model.setProgramId(programBox.getValue().getProgramId());
        model.setAdmissionDate(admissionPicker.getValue());
        model.setStatus(statusBox.getValue());
    }

    private String mark(Control field, String message) {
        field.getStyleClass().add("field-error");
        field.requestFocus();
        return message;
    }

    private void clearErrorStyles() {
        for (Control c : List.<Control>of(studentNumberField, firstNameField, lastNameField, emailField,
                phoneField, dobPicker, genderBox, addressField, programBox, admissionPicker, statusBox)) {
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
