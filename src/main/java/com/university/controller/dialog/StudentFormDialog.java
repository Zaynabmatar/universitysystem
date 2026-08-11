package com.university.controller.dialog;

import com.university.enums.Gender;
import com.university.enums.StudentStatus;
import com.university.model.Program;
import com.university.model.Student;
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
 * Add / Edit student. Built in Java rather than FXML on purpose:
 * project_details.md Section 7 lists the complete set of FXML files and no
 * dialog appears in it.
 *
 * <p>The admin never types an identity here. There is no id field to fill in,
 * no username field and no password field: {@code StudentService.create} takes
 * the Student ID from SQL Server's IDENTITY column, builds the university
 * email from the name, and assigns the mandatory {@code <Student ID>@iuL}
 * password once the row exists. The caller shows all three to the admin after
 * the dialog closes.</p>
 *
 * <p>The two identity fields keep their places in the grid so the form looks
 * and sizes exactly as it always has. In <b>add</b> mode both are read-only
 * and say what is about to be generated; in <b>edit</b> mode the Student ID
 * stays read-only — it is not the admin's to change — while the email becomes
 * editable, because correcting somebody's address is a legitimate thing to
 * need.</p>
 *
 * <p>Validation happens before the dialog closes — the OK button's close is
 * consumed until every field is valid, and a message appears under the form
 * (project_details.md Section 13).</p>
 */
public final class StudentFormDialog extends Dialog<Student> {

    private final TextField studentIdField          = new TextField();
    private final TextField firstNameField          = new TextField();
    private final TextField lastNameField           = new TextField();
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
        setHeaderText(existing != null
                ? "Editing " + existing.getFirstName() + " " + existing.getLastName()
                : "A login account is created automatically. The Student ID, university email "
                  + "and temporary password are shown after saving.");
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
        g.addRow(r++, new Label("Student ID"),       studentIdField,     new Label("Status *"), statusBox);
        g.addRow(r++, new Label("First name *"),     firstNameField,     new Label("Last name *"), lastNameField);
        g.addRow(r++, new Label(editMode ? "Email *" : "Email"),
                                                     emailField,         new Label("Phone"),       phoneField);
        g.addRow(r++, new Label("Date of birth"),    dobPicker,          new Label("Gender"),      genderBox);
        g.addRow(r++, new Label("Program *"),        programBox,         new Label("Admission date *"), admissionPicker);
        g.addRow(r++, new Label("Address"),          addressField);
        GridPane.setColumnSpan(addressField, 3);

        // The Student ID is users.user_id and is never typed: read-only in both
        // modes, and in add mode it does not exist yet at all.
        studentIdField.setEditable(false);
        studentIdField.setFocusTraversable(false);

        VBox box = new VBox(6, g, errorLabel);
        box.setPadding(new Insets(0, 14, 12, 14));
        box.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(box);

        if (editMode) fillFromModel(existing, programs);
        else {
            statusBox.setValue(StudentStatus.ACTIVE);
            admissionPicker.setValue(LocalDate.now());
            phoneField.setText("+961 ");
            phoneField.positionCaret(phoneField.getText().length());
            // Nothing to show yet, and nothing for the admin to decide.
            studentIdField.setPromptText("Generated automatically on save");
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

    private void fillFromModel(Student s, List<Program> programs) {
        studentIdField.setText(String.valueOf(s.getUserId()));
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
                        + "z.matar@" + AccountService.STUDENT_EMAIL_DOMAIN + ".");
            }
            if (!emailField.getText().trim().toLowerCase()
                    .endsWith("@" + AccountService.STUDENT_EMAIL_DOMAIN)) {
                return mark(emailField, "A student email address must end in @"
                        + AccountService.STUDENT_EMAIL_DOMAIN + ".");
            }
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

    /**
     * The Student ID is not written back: it belongs to the account, and in add
     * mode it does not exist yet. In add mode the email is not written back
     * either — the service generates it.
     */
    private void writeToModel() {
        model.setFirstName(firstNameField.getText().trim());
        model.setLastName(lastNameField.getText().trim());
        if (editMode) {
            model.setEmail(emailField.getText().trim().toLowerCase());
        }
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
        for (Control c : List.<Control>of(firstNameField, lastNameField, emailField,
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
