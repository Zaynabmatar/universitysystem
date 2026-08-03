package com.university.controller;

import com.university.controller.dialog.InstructorFormDialog;
import com.university.enums.AcademicRank;
import com.university.enums.UserRole;
import com.university.model.Department;
import com.university.model.Instructor;
import com.university.service.CourseService;
import com.university.service.InstructorService;
import com.university.service.PasswordHasher;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Optional;

/** The admin's "Manage Instructors" screen: search/filter table plus add, edit, deactivate and reset-password actions. */
public class AdminInstructorsController {

    @FXML private TextField searchField;
    @FXML private ComboBox<Department> departmentFilter;
    @FXML private ComboBox<AcademicRank> rankFilter;
    @FXML private Label countLabel;
    @FXML private TableView<Instructor> instructorTable;
    @FXML private TableColumn<Instructor, String> colNumber;
    @FXML private TableColumn<Instructor, String> colName;
    @FXML private TableColumn<Instructor, String> colEmail;
    @FXML private TableColumn<Instructor, String> colPhone;
    @FXML private TableColumn<Instructor, String> colDepartment;
    @FXML private TableColumn<Instructor, String> colRank;
    @FXML private TableColumn<Instructor, String> colHireDate;
    @FXML private TableColumn<Instructor, String> colActive;
    @FXML private Button editButton;
    @FXML private Button deactivateButton;
    @FXML private Button reactivateButton;
    @FXML private Button resetPwdButton;

    private final InstructorService instructorService = new InstructorService();
    private final CourseService courseService = new CourseService();

    private final ObservableList<Instructor> rows = FXCollections.observableArrayList();
    private List<Department> departments = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);

        colNumber    .setCellValueFactory(new PropertyValueFactory<>("employeeNumber"));
        colName      .setCellValueFactory(new PropertyValueFactory<>("fullName"));
        colEmail     .setCellValueFactory(new PropertyValueFactory<>("email"));
        colPhone     .setCellValueFactory(new PropertyValueFactory<>("phone"));
        colDepartment.setCellValueFactory(c -> new SimpleStringProperty(departmentCodeFor(c.getValue().getDepartmentId())));
        colRank      .setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getAcademicRank().toString()));
        colHireDate  .setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getHireDate() == null ? "—" : c.getValue().getHireDate().toString()));
        colActive    .setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActive() ? "Active" : "Deactivated"));

        instructorTable.setItems(rows);
        instructorTable.setPlaceholder(new Label("No instructors match your filters."));

        departments = courseService.listDepartments(false);
        departmentFilter.getItems().add(null);
        departmentFilter.getItems().addAll(departments);
        departmentFilter.setConverter(new StringConverter<>() {
            @Override public String toString(Department d) { return d == null ? "All departments" : d.toString(); }
            @Override public Department fromString(String s) { return null; }
        });
        departmentFilter.getSelectionModel().selectFirst();

        rankFilter.getItems().add(null);
        rankFilter.getItems().addAll(AcademicRank.values());
        rankFilter.setConverter(new StringConverter<>() {
            @Override public String toString(AcademicRank r) { return r == null ? "All ranks" : r.toString(); }
            @Override public AcademicRank fromString(String s) { return null; }
        });
        rankFilter.getSelectionModel().selectFirst();

        // Live filtering.
        searchField.textProperty().addListener((o, a, b) -> reload());
        departmentFilter.valueProperty().addListener((o, a, b) -> reload());
        rankFilter.valueProperty().addListener((o, a, b) -> reload());

        // Row-dependent buttons.
        var selected = instructorTable.getSelectionModel().selectedItemProperty();
        editButton.disableProperty().bind(selected.isNull());
        resetPwdButton.disableProperty().bind(selected.isNull());
        deactivateButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> selected.get() == null || !selected.get().isActive(), selected));
        reactivateButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> selected.get() == null || selected.get().isActive(), selected));

        reload();
    }

    // ------------------------------------------------------------------ data

    private void reload() {
        try {
            Department department = departmentFilter.getValue();
            AcademicRank rank = rankFilter.getValue();
            String term = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();

            List<Instructor> result = instructorService.search(term).stream()
                    .filter(i -> department == null || i.getDepartmentId() == department.getDepartmentId())
                    .filter(i -> rank == null || i.getAcademicRank() == rank)
                    .toList();

            rows.setAll(result);
            countLabel.setText(result.size() + " instructor" + (result.size() == 1 ? "" : "s"));

        } catch (Exception e) {
            AlertUtil.error("Could not load instructors", "The instructor list could not be loaded.", e);
        }
    }

    private String departmentCodeFor(int departmentId) {
        return departments.stream()
                .filter(d -> d.getDepartmentId() == departmentId)
                .findFirst()
                .map(Department::getDepartmentCode)
                .orElse("—");
    }

    @FXML private void handleRefresh() { reload(); }

    @FXML
    private void handleClearFilters() {
        searchField.clear();
        departmentFilter.getSelectionModel().selectFirst();
        rankFilter.getSelectionModel().selectFirst();
        reload();
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void handleAdd() {
        InstructorFormDialog dialog = new InstructorFormDialog(null, departments);
        Optional<Instructor> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        try {
            Instructor created = instructorService.create(result.get());
            AlertUtil.success("Instructor added",
                    "The instructor was created.\n\n"
                    + "User ID: " + created.getUserId() + "\n"
                    + "Temporary password: " + PasswordHasher.defaultPasswordFor(created.getUserId()));
            reload();

        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The instructor could not be created.", e);
        }
    }

    @FXML
    private void handleEdit() {
        Instructor selected = instructorTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<Instructor> result = new InstructorFormDialog(selected, departments).showAndWait();
        if (result.isEmpty()) return;

        try {
            instructorService.update(result.get());
            AlertUtil.success("Saved", "The instructor's details were updated.");
            reload();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The instructor could not be updated.", e);
        }
    }

    @FXML
    private void handleDeactivate() {
        Instructor selected = instructorTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        boolean ok = AlertUtil.confirm("Deactivate instructor",
                "Deactivate " + selected.getFullName() + " (" + selected.getEmployeeNumber() + ")?\n\n"
                + "Nothing is deleted. Their login is disabled and they can no longer be assigned new "
                + "sections, but every section and grade they are already on is kept.");
        if (!ok) return;

        try {
            instructorService.deactivate(selected.getInstructorId());
            AlertUtil.success("Deactivated", selected.getFullName() + " can no longer log in.");
            reload();
        } catch (Exception e) {
            AlertUtil.error("Could not deactivate", "The instructor could not be deactivated.", e);
        }
    }

    @FXML
    private void handleReactivate() {
        Instructor selected = instructorTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!AlertUtil.confirm("Reactivate instructor",
                "Reactivate " + selected.getFullName() + "? They will be able to log in again.")) {
            return;
        }
        try {
            instructorService.reactivate(selected.getInstructorId());
            AlertUtil.success("Reactivated", selected.getFullName() + " can log in again.");
            reload();
        } catch (Exception e) {
            AlertUtil.error("Could not reactivate", "The instructor could not be reactivated.", e);
        }
    }

    @FXML
    private void handleResetPassword() {
        Instructor selected = instructorTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!AlertUtil.confirm("Reset password",
                "Reset the password for " + selected.getFullName() + " (User ID " + selected.getUserId() + ")?")) {
            return;
        }
        try {
            String newPassword = instructorService.resetPasswordToDefault(selected.getInstructorId());
            AlertUtil.success("Password reset",
                    "User ID: " + selected.getUserId() + "\nNew password: " + newPassword
                    + "\n\nTell the instructor to change it after signing in.");
        } catch (Exception e) {
            AlertUtil.error("Could not reset password", "The password could not be reset.", e);
        }
    }
}
