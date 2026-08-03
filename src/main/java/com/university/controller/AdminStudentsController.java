package com.university.controller;

import com.university.controller.dialog.StudentFormDialog;
import com.university.enums.StudentStatus;
import com.university.enums.UserRole;
import com.university.model.Program;
import com.university.model.Student;
import com.university.service.CourseService;
import com.university.service.PasswordHasher;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.service.StudentService;
import com.university.util.AlertUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Optional;

/** The admin's "Manage Students" screen: search/filter table plus add, edit, deactivate and reset-password actions. */
public class AdminStudentsController {

    @FXML private TextField searchField;
    @FXML private ComboBox<Program> programFilter;
    @FXML private ComboBox<StudentStatus> statusFilter;
    @FXML private Label countLabel;
    @FXML private TableView<Student> studentTable;
    @FXML private TableColumn<Student, String> colNumber;
    @FXML private TableColumn<Student, String> colName;
    @FXML private TableColumn<Student, String> colEmail;
    @FXML private TableColumn<Student, String> colPhone;
    @FXML private TableColumn<Student, String> colProgram;
    @FXML private TableColumn<Student, String> colStatus;
    @FXML private TableColumn<Student, String> colStanding;
    @FXML private TableColumn<Student, String> colGpa;
    @FXML private TableColumn<Student, String> colCredits;
    @FXML private Button editButton;
    @FXML private Button deactivateButton;
    @FXML private Button reactivateButton;
    @FXML private Button resetPwdButton;

    private final StudentService studentService = new StudentService();
    private final CourseService courseService = new CourseService();

    private final ObservableList<Student> rows = FXCollections.observableArrayList();
    private List<Program> programs = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);

        colNumber  .setCellValueFactory(new PropertyValueFactory<>("studentNumber"));
        colName    .setCellValueFactory(new PropertyValueFactory<>("fullName"));
        colEmail   .setCellValueFactory(new PropertyValueFactory<>("email"));
        colPhone   .setCellValueFactory(new PropertyValueFactory<>("phone"));
        colProgram .setCellValueFactory(c -> new SimpleStringProperty(programCodeFor(c.getValue().getProgramId())));
        colStatus  .setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getStatus().toString()));
        colStanding.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getAcademicStanding().toString()));
        colGpa     .setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCumulativeGpa().toPlainString()));
        colCredits .setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getCompletedCredits())));

        studentTable.setItems(rows);
        studentTable.setPlaceholder(new Label("No students match your filters."));

        programs = courseService.listPrograms(false);
        programFilter.getItems().add(null);
        programFilter.getItems().addAll(programs);
        programFilter.setConverter(new StringConverter<>() {
            @Override public String toString(Program p) { return p == null ? "All programs" : p.toString(); }
            @Override public Program fromString(String s) { return null; }
        });
        programFilter.getSelectionModel().selectFirst();

        statusFilter.getItems().add(null);
        statusFilter.getItems().addAll(StudentStatus.values());
        statusFilter.setConverter(new StringConverter<>() {
            @Override public String toString(StudentStatus s) { return s == null ? "All statuses" : s.toString(); }
            @Override public StudentStatus fromString(String s) { return null; }
        });
        statusFilter.getSelectionModel().selectFirst();

        // Live filtering.
        searchField.textProperty().addListener((o, a, b) -> reload());
        programFilter.valueProperty().addListener((o, a, b) -> reload());
        statusFilter.valueProperty().addListener((o, a, b) -> reload());

        // Row-dependent buttons.
        var selected = studentTable.getSelectionModel().selectedItemProperty();
        editButton.disableProperty().bind(selected.isNull());
        resetPwdButton.disableProperty().bind(selected.isNull());
        deactivateButton.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> selected.get() == null || selected.get().getStatus() == StudentStatus.WITHDRAWN, selected));
        reactivateButton.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> selected.get() == null || selected.get().getStatus() != StudentStatus.WITHDRAWN, selected));

        reload();
    }

    // ------------------------------------------------------------------ data

    private void reload() {
        try {
            Program program = programFilter.getValue();
            StudentStatus status = statusFilter.getValue();

            List<Student> result = studentService.search(searchField.getText()).stream()
                    .filter(s -> program == null || s.getProgramId() == program.getProgramId())
                    .filter(s -> status == null || s.getStatus() == status)
                    .toList();

            rows.setAll(result);
            countLabel.setText(result.size() + " student" + (result.size() == 1 ? "" : "s"));

        } catch (Exception e) {
            AlertUtil.error("Could not load students", "The student list could not be loaded.", e);
        }
    }

    private String programCodeFor(int programId) {
        return programs.stream()
                .filter(p -> p.getProgramId() == programId)
                .findFirst()
                .map(Program::getProgramCode)
                .orElse("—");
    }

    @FXML private void handleRefresh() { reload(); }

    @FXML
    private void handleClearFilters() {
        searchField.clear();
        programFilter.getSelectionModel().selectFirst();
        statusFilter.getSelectionModel().selectFirst();
        reload();
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void handleAdd() {
        StudentFormDialog dialog = new StudentFormDialog(null, programs);
        Optional<Student> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        try {
            Student created = studentService.create(result.get());
            AlertUtil.success("Student added",
                    "The student was created.\n\n"
                    + "User ID: " + created.getUserId() + "\n"
                    + "Temporary password: " + PasswordHasher.defaultPasswordFor(created.getUserId()));
            reload();

        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The student could not be created.", e);
        }
    }

    @FXML
    private void handleEdit() {
        Student selected = studentTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<Student> result = new StudentFormDialog(selected, programs).showAndWait();
        if (result.isEmpty()) return;

        try {
            studentService.update(result.get());
            AlertUtil.success("Saved", "The student's details were updated.");
            reload();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The student could not be updated.", e);
        }
    }

    @FXML
    private void handleDeactivate() {
        Student selected = studentTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        boolean ok = AlertUtil.confirm("Deactivate student",
                "Deactivate " + selected.getFullName() + " (" + selected.getStudentNumber() + ")?\n\n"
                + "Nothing is deleted. The student's status becomes WITHDRAWN, their login is disabled, "
                + "and all their history and grades are kept.");
        if (!ok) return;

        try {
            studentService.deactivate(selected.getStudentId());
            AlertUtil.success("Deactivated", selected.getFullName() + " can no longer log in or register.");
            reload();
        } catch (Exception e) {
            AlertUtil.error("Could not deactivate", "The student could not be deactivated.", e);
        }
    }

    @FXML
    private void handleReactivate() {
        Student selected = studentTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!AlertUtil.confirm("Reactivate student",
                "Reactivate " + selected.getFullName() + "? Their status becomes ACTIVE and they can log in again.")) {
            return;
        }
        try {
            studentService.reactivate(selected.getStudentId());
            AlertUtil.success("Reactivated", selected.getFullName() + " can log in again.");
            reload();
        } catch (Exception e) {
            AlertUtil.error("Could not reactivate", "The student could not be reactivated.", e);
        }
    }

    @FXML
    private void handleResetPassword() {
        Student selected = studentTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!AlertUtil.confirm("Reset password",
                "Reset the password for " + selected.getFullName() + " (User ID " + selected.getUserId() + ")?")) {
            return;
        }
        try {
            String newPassword = studentService.resetPasswordToDefault(selected.getStudentId());
            AlertUtil.success("Password reset",
                    "User ID: " + selected.getUserId() + "\nNew password: " + newPassword
                    + "\n\nTell the student to change it after signing in.");
        } catch (Exception e) {
            AlertUtil.error("Could not reset password", "The password could not be reset.", e);
        }
    }
}
