package com.university.controller;

import com.university.controller.dialog.DepartmentFormDialog;
import com.university.controller.dialog.ProgramFormDialog;
import com.university.enums.UserRole;
import com.university.model.Department;
import com.university.model.Program;
import com.university.service.CourseService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

import java.util.List;
import java.util.Optional;

/** The admin's "Departments &amp; Programs" screen: two tabs over the academic catalogue's structure. */
public class AdminProgramsController {

    @FXML private TableView<Department> departmentTable;
    @FXML private TableColumn<Department, String> deptColCode;
    @FXML private TableColumn<Department, String> deptColName;
    @FXML private TableColumn<Department, String> deptColPrograms;
    @FXML private TableColumn<Department, String> deptColCourses;
    @FXML private TableColumn<Department, String> deptColActive;
    @FXML private Button deptEditButton;
    @FXML private Button deptDeactivateButton;
    @FXML private Button deptReactivateButton;

    @FXML private TableView<Program> programTable;
    @FXML private TableColumn<Program, String> progColCode;
    @FXML private TableColumn<Program, String> progColName;
    @FXML private TableColumn<Program, String> progColDepartment;
    @FXML private TableColumn<Program, String> progColDegree;
    @FXML private TableColumn<Program, String> progColCredits;
    @FXML private TableColumn<Program, String> progColActive;
    @FXML private Button progEditButton;
    @FXML private Button progDeactivateButton;
    @FXML private Button progReactivateButton;

    private final CourseService courseService = new CourseService();

    private final ObservableList<Department> departmentRows = FXCollections.observableArrayList();
    private final ObservableList<Program> programRows = FXCollections.observableArrayList();
    private List<Department> departments = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);
        initDepartmentsTab();
        initProgramsTab();
        reloadDepartments();
        reloadPrograms();
    }

    @FXML
    private void handleRefresh() {
        reloadDepartments();
        reloadPrograms();
    }

    // ------------------------------------------------------------------ departments

    private void initDepartmentsTab() {
        deptColCode.setCellValueFactory(new PropertyValueFactory<>("departmentCode"));
        deptColName.setCellValueFactory(new PropertyValueFactory<>("departmentName"));
        deptColPrograms.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(courseService.countActiveProgramsInDepartment(c.getValue().getDepartmentId()))));
        deptColCourses.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(courseService.countActiveCoursesInDepartment(c.getValue().getDepartmentId()))));
        deptColActive.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActive() ? "Active" : "Deactivated"));

        departmentTable.setItems(departmentRows);

        departmentTable.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(Department item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("row-deactivated");
                if (!empty && item != null && !item.isActive()) {
                    getStyleClass().add("row-deactivated");
                }
            }
        });
        departmentTable.setPlaceholder(new Label("No departments yet."));

        var selected = departmentTable.getSelectionModel().selectedItemProperty();
        deptEditButton.disableProperty().bind(selected.isNull());
        deptDeactivateButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> selected.get() == null || !selected.get().isActive(), selected));
        deptReactivateButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> selected.get() == null || selected.get().isActive(), selected));
    }

    private void reloadDepartments() {
        try {
            departments = courseService.listDepartments(false);
            departmentRows.setAll(departments);
        } catch (Exception e) {
            AlertUtil.error("Could not load departments", "The department list could not be loaded.", e);
        }
    }

    @FXML
    private void handleAddDepartment() {
        Optional<Department> result = new DepartmentFormDialog(null).showAndWait();
        if (result.isEmpty()) return;

        try {
            courseService.createDepartment(result.get());
            AlertUtil.success("Department added", "The department was created.");
            reloadDepartments();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The department could not be created.", e);
        }
    }

    @FXML
    private void handleEditDepartment() {
        Department selected = departmentTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<Department> result = new DepartmentFormDialog(selected).showAndWait();
        if (result.isEmpty()) return;

        try {
            courseService.updateDepartment(result.get());
            AlertUtil.success("Saved", "The department's details were updated.");
            reloadDepartments();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The department could not be updated.", e);
        }
    }

    @FXML
    private void handleDeactivateDepartment() {
        Department selected = departmentTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        int programs = courseService.countActiveProgramsInDepartment(selected.getDepartmentId());
        int courses = courseService.countActiveCoursesInDepartment(selected.getDepartmentId());
        String warning = (programs > 0 || courses > 0)
                ? "\n\nThis department still has " + programs + " active program" + (programs == 1 ? "" : "s")
                  + " and " + courses + " active course" + (courses == 1 ? "" : "s") + "."
                : "";

        boolean ok = AlertUtil.confirm("Deactivate department",
                "Deactivate " + selected.getDepartmentCode() + " — " + selected.getDepartmentName() + "?\n\n"
                + "Nothing is deleted. The department is hidden from new assignments, but everything already "
                + "linked to it is kept." + warning);
        if (!ok) return;

        try {
            courseService.setDepartmentActive(selected.getDepartmentId(), false);
            AlertUtil.success("Deactivated", selected.getDepartmentName() + " is now deactivated.");
            reloadDepartments();
        } catch (Exception e) {
            AlertUtil.error("Could not deactivate", "The department could not be deactivated.", e);
        }
    }

    @FXML
    private void handleReactivateDepartment() {
        Department selected = departmentTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!AlertUtil.confirm("Reactivate department", "Reactivate " + selected.getDepartmentName() + "?")) {
            return;
        }
        try {
            courseService.setDepartmentActive(selected.getDepartmentId(), true);
            AlertUtil.success("Reactivated", selected.getDepartmentName() + " is active again.");
            reloadDepartments();
        } catch (Exception e) {
            AlertUtil.error("Could not reactivate", "The department could not be reactivated.", e);
        }
    }

    // ------------------------------------------------------------------ programs

    private void initProgramsTab() {
        progColCode.setCellValueFactory(new PropertyValueFactory<>("programCode"));
        progColName.setCellValueFactory(new PropertyValueFactory<>("programName"));
        progColDepartment.setCellValueFactory(c -> new SimpleStringProperty(departmentCodeFor(c.getValue().getDepartmentId())));
        progColDegree.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getDegreeType() == null ? "—" : c.getValue().getDegreeType().toString()));
        progColCredits.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getTotalCreditsRequired())));
        progColActive.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActive() ? "Active" : "Deactivated"));

        programTable.setItems(programRows);

        programTable.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(Program item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("row-deactivated");
                if (!empty && item != null && !item.isActive()) {
                    getStyleClass().add("row-deactivated");
                }
            }
        });
        programTable.setPlaceholder(new Label("No programs yet."));

        var selected = programTable.getSelectionModel().selectedItemProperty();
        progEditButton.disableProperty().bind(selected.isNull());
        progDeactivateButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> selected.get() == null || !selected.get().isActive(), selected));
        progReactivateButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> selected.get() == null || selected.get().isActive(), selected));
    }

    private void reloadPrograms() {
        try {
            programRows.setAll(courseService.listPrograms(false));
        } catch (Exception e) {
            AlertUtil.error("Could not load programs", "The program list could not be loaded.", e);
        }
    }

    private String departmentCodeFor(int departmentId) {
        return departments.stream()
                .filter(d -> d.getDepartmentId() == departmentId)
                .findFirst()
                .map(d -> d.getDepartmentCode())
                .orElse("—");
    }

    @FXML
    private void handleAddProgram() {
        if (departments.isEmpty()) {
            AlertUtil.warn("No departments", "Create a department before adding a program.");
            return;
        }
        Optional<Program> result = new ProgramFormDialog(null, departments).showAndWait();
        if (result.isEmpty()) return;

        try {
            courseService.createProgram(result.get());
            AlertUtil.success("Program added", "The program was created.");
            reloadPrograms();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The program could not be created.", e);
        }
    }

    @FXML
    private void handleEditProgram() {
        Program selected = programTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<Program> result = new ProgramFormDialog(selected, departments).showAndWait();
        if (result.isEmpty()) return;

        try {
            courseService.updateProgram(result.get());
            AlertUtil.success("Saved", "The program's details were updated.");
            reloadPrograms();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The program could not be updated.", e);
        }
    }

    @FXML
    private void handleDeactivateProgram() {
        Program selected = programTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        int students = courseService.countStudentsInProgram(selected.getProgramId());
        String warning = students > 0
                ? "\n\nThis program has " + students + " student" + (students == 1 ? "" : "s") + " currently on it."
                : "";

        boolean ok = AlertUtil.confirm("Deactivate program",
                "Deactivate " + selected.getProgramCode() + " — " + selected.getProgramName() + "?\n\n"
                + "Nothing is deleted. New students can no longer be admitted into it, but everyone already "
                + "on it is kept exactly as they are." + warning);
        if (!ok) return;

        try {
            courseService.setProgramActive(selected.getProgramId(), false);
            AlertUtil.success("Deactivated", selected.getProgramName() + " is now deactivated.");
            reloadPrograms();
        } catch (Exception e) {
            AlertUtil.error("Could not deactivate", "The program could not be deactivated.", e);
        }
    }

    @FXML
    private void handleReactivateProgram() {
        Program selected = programTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!AlertUtil.confirm("Reactivate program", "Reactivate " + selected.getProgramName() + "?")) {
            return;
        }
        try {
            courseService.setProgramActive(selected.getProgramId(), true);
            AlertUtil.success("Reactivated", selected.getProgramName() + " is active again.");
            reloadPrograms();
        } catch (Exception e) {
            AlertUtil.error("Could not reactivate", "The program could not be reactivated.", e);
        }
    }
}

