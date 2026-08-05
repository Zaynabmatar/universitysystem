package com.university.controller;

import com.university.controller.dialog.CourseFormDialog;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.Department;
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
import javafx.util.StringConverter;

import java.util.List;
import java.util.Optional;

/** The admin's "Manage Courses" screen: search/filter table plus add, edit and soft-delete actions. */
public class AdminCoursesController {

    @FXML private TextField searchField;
    @FXML private ComboBox<Department> departmentFilter;
    @FXML private ComboBox<Integer> yearFilter;
    @FXML private CheckBox activeOnlyCheck;
    @FXML private Label countLabel;
    @FXML private TableView<Course> courseTable;
    @FXML private TableColumn<Course, String> colCode;
    @FXML private TableColumn<Course, String> colTitle;
    @FXML private TableColumn<Course, String> colCredits;
    @FXML private TableColumn<Course, String> colDept;
    @FXML private TableColumn<Course, String> colYear;
    @FXML private TableColumn<Course, String> colPrereq;
    @FXML private TableColumn<Course, String> colUnlocks;
    @FXML private TableColumn<Course, String> colState;
    @FXML private Button editButton;
    @FXML private Button deactivateButton;
    @FXML private Button reactivateButton;

    private final CourseService courseService = new CourseService();

    private final ObservableList<Course> rows = FXCollections.observableArrayList();
    private List<Department> departments = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);

        colCode.setCellValueFactory(new PropertyValueFactory<>("courseCode"));
        colTitle.setCellValueFactory(new PropertyValueFactory<>("courseTitle"));
        colCredits.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getCredits())));
        colDept.setCellValueFactory(c -> new SimpleStringProperty(departmentCodeFor(c.getValue().getDepartmentId())));
        colYear.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getLevelYear())));
        colPrereq.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(courseService.countPrerequisitesOf(c.getValue().getCourseId()))));
        colUnlocks.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(courseService.countCoursesUnlockedBy(c.getValue().getCourseId()))));
        colState.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActive() ? "Active" : "Inactive"));

        courseTable.setItems(rows);
        courseTable.setPlaceholder(new Label("No courses match your filters."));

        departments = courseService.listDepartments(false);
        departmentFilter.getItems().add(null);
        departmentFilter.getItems().addAll(departments);
        departmentFilter.setConverter(new StringConverter<>() {
            @Override public String toString(Department d) { return d == null ? "All departments" : d.toString(); }
            @Override public Department fromString(String s) { return null; }
        });
        departmentFilter.getSelectionModel().selectFirst();

        yearFilter.getItems().add(null);
        yearFilter.getItems().addAll(1, 2, 3, 4);
        yearFilter.setConverter(new StringConverter<>() {
            @Override public String toString(Integer y) { return y == null ? "All years" : "Year " + y; }
            @Override public Integer fromString(String s) { return null; }
        });
        yearFilter.getSelectionModel().selectFirst();

        // Live filtering.
        searchField.textProperty().addListener((o, a, b) -> reload());
        departmentFilter.valueProperty().addListener((o, a, b) -> reload());
        yearFilter.valueProperty().addListener((o, a, b) -> reload());
        activeOnlyCheck.selectedProperty().addListener((o, a, b) -> reload());

        // Row-dependent buttons.
        var selected = courseTable.getSelectionModel().selectedItemProperty();
        editButton.disableProperty().bind(selected.isNull());
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
            Integer year = yearFilter.getValue();

            List<Course> result = courseService.searchCourses(searchField.getText()).stream()
                    .filter(c -> department == null || c.getDepartmentId() == department.getDepartmentId())
                    .filter(c -> year == null || c.getLevelYear() == year)
                    .filter(c -> !activeOnlyCheck.isSelected() || c.isActive())
                    .toList();

            rows.setAll(result);
            countLabel.setText(result.size() + " course" + (result.size() == 1 ? "" : "s"));

        } catch (Exception e) {
            AlertUtil.error("Could not load courses", "The course catalogue could not be loaded.", e);
        }
    }

    private String departmentCodeFor(int departmentId) {
        return departments.stream()
                .filter(d -> d.getDepartmentId() == departmentId)
                .findFirst()
                .map(d -> d.getDepartmentCode())
                .orElse("—");
    }

    @FXML private void handleRefresh() { reload(); }

    @FXML
    private void handleClearFilters() {
        searchField.clear();
        departmentFilter.getSelectionModel().selectFirst();
        yearFilter.getSelectionModel().selectFirst();
        activeOnlyCheck.setSelected(false);
        reload();
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void handleAdd() {
        Optional<Course> result = new CourseFormDialog(null, departments).showAndWait();
        if (result.isEmpty()) return;

        try {
            courseService.createCourse(result.get());
            AlertUtil.success("Course added", "The course was created.");
            reload();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The course could not be created.", e);
        }
    }

    @FXML
    private void handleEdit() {
        Course selected = courseTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<Course> result = new CourseFormDialog(selected, departments).showAndWait();
        if (result.isEmpty()) return;

        try {
            courseService.updateCourse(result.get());
            AlertUtil.success("Saved", "The course's details were updated.");
            reload();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The course could not be updated.", e);
        }
    }

    @FXML
    private void handleDeactivate() {
        Course selected = courseTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        int openSections = courseService.countOpenSectionsForCourse(selected.getCourseId());
        String message = openSections > 0
                ? "This course has " + openSections + " open section(s) in the current semester. "
                  + "Deactivating it stops new sections being created but does not cancel existing ones "
                  + "or delete any grade. Continue?"
                : "Deactivate " + selected.getCourseCode() + "? The course stays in the database and all "
                  + "past grades keep working. It just cannot be offered any more.";

        if (!AlertUtil.confirm("Deactivate course", message)) return;

        try {
            courseService.setCourseActive(selected.getCourseId(), false);
            AlertUtil.success("Deactivated", selected.getCourseCode() + " can no longer be offered.");
            reload();
        } catch (Exception e) {
            AlertUtil.error("Could not deactivate", "The course could not be deactivated.", e);
        }
    }

    @FXML
    private void handleReactivate() {
        Course selected = courseTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!AlertUtil.confirm("Reactivate course", "Reactivate " + selected.getCourseCode() + "?")) {
            return;
        }
        try {
            courseService.setCourseActive(selected.getCourseId(), true);
            AlertUtil.success("Reactivated", selected.getCourseCode() + " can be offered again.");
            reload();
        } catch (Exception e) {
            AlertUtil.error("Could not reactivate", "The course could not be reactivated.", e);
        }
    }
}
