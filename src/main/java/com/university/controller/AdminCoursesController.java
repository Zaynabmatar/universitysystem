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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
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
    @FXML private VBox weightsPanel;

    private final CourseService courseService = new CourseService();

    private final ObservableList<Course> rows = FXCollections.observableArrayList();
    private List<Department> departments = List.of();

    // ---- Grading Weights panel (built in code; no FXML Spinner value-factory support) ----
    private final Label weightsHintLabel = new Label("Select a course above to view or edit its grading configuration.");
    private final Label weightsCourseLabel = new Label();
    private final Label courseworkWeightLabel = new Label("Coursework/Lab weight %");
    private final Label courseworkMaxLabel = new Label("Coursework/Lab max mark");
    private final Spinner<Integer> courseworkWeightSpinner = new Spinner<>(0, 100, 20);
    private final Spinner<Integer> midtermWeightSpinner = new Spinner<>(0, 100, 30);
    private final Spinner<Integer> finalWeightSpinner = new Spinner<>(0, 100, 50);
    // Max mark is a 2-option choice, not a free range: it's either the weight value itself or 100.
    private final ComboBox<Integer> courseworkMaxBox = new ComboBox<>();
    private final ComboBox<Integer> midtermMaxBox = new ComboBox<>();
    private final ComboBox<Integer> finalMaxBox = new ComboBox<>();
    private final Label weightsSumLabel = new Label();
    private final Button saveWeightsButton = new Button("Save Grading Config");
    private final GridPane weightsGrid = new GridPane();
    private boolean loadingWeights;

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

        // Deactivated courses are highlighted across the whole row.
        courseTable.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(Course item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("row-deactivated");
                if (!empty && item != null && !item.isActive()) {
                    getStyleClass().add("row-deactivated");
                }
            }
        });
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

        buildWeightsPanel();
        selected.addListener((o, a, b) -> loadWeightsForSelection(b));
        loadWeightsForSelection(null);

        reload();
    }

    // ------------------------------------------------------------- grading weights

    /** Builds the weight-editing controls in code, same as {@link CourseFormDialog}'s Spinners. */
    private void buildWeightsPanel() {
        List<Spinner<Integer>> weightSpinners = List.of(courseworkWeightSpinner, midtermWeightSpinner,
                finalWeightSpinner);
        for (Spinner<Integer> s : weightSpinners) {
            s.setEditable(true);
            s.setPrefWidth(90);
        }
        for (ComboBox<Integer> b : List.of(courseworkMaxBox, midtermMaxBox, finalMaxBox)) {
            b.setPrefWidth(90);
        }
        courseworkWeightSpinner.valueProperty().addListener((obs, old, val) -> refreshMaxMarkOptions(courseworkWeightSpinner, courseworkMaxBox));
        midtermWeightSpinner.valueProperty().addListener((obs, old, val) -> refreshMaxMarkOptions(midtermWeightSpinner, midtermMaxBox));
        finalWeightSpinner.valueProperty().addListener((obs, old, val) -> refreshMaxMarkOptions(finalWeightSpinner, finalMaxBox));
        for (Spinner<Integer> s : weightSpinners) {
            s.valueProperty().addListener((obs, old, val) -> updateWeightsSumLabel());
        }
        weightsSumLabel.getStyleClass().add("error-text");
        weightsCourseLabel.getStyleClass().add("muted-text");
        weightsHintLabel.getStyleClass().add("muted-text");

        weightsGrid.setHgap(12);
        weightsGrid.setVgap(9);
        weightsGrid.addRow(0, courseworkWeightLabel, courseworkWeightSpinner,
                new Label("Midterm weight %"), midtermWeightSpinner,
                new Label("Final weight %"), finalWeightSpinner);
        weightsGrid.addRow(1, courseworkMaxLabel, courseworkMaxBox,
                new Label("Midterm max mark"), midtermMaxBox,
                new Label("Final max mark"), finalMaxBox);

        HBox saveRow = new HBox(10, weightsSumLabel, saveWeightsButton);
        saveRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        saveWeightsButton.setOnAction(e -> handleSaveWeights());

        weightsPanel.getChildren().addAll(weightsHintLabel, weightsCourseLabel, weightsGrid, saveRow);
        setWeightsControlsVisible(false);
    }

    private void setWeightsControlsVisible(boolean visible) {
        weightsCourseLabel.setVisible(visible);
        weightsCourseLabel.setManaged(visible);
        weightsGrid.setVisible(visible);
        weightsGrid.setManaged(visible);
        saveWeightsButton.getParent().setVisible(visible);
        saveWeightsButton.getParent().setManaged(visible);
        weightsHintLabel.setVisible(!visible);
        weightsHintLabel.setManaged(!visible);
    }

    private void loadWeightsForSelection(Course course) {
        if (course == null) {
            setWeightsControlsVisible(false);
            return;
        }
        loadingWeights = true;
        setWeightsControlsVisible(true);
        weightsCourseLabel.setText("Editing grading config for " + course.getCourseCode() + " - " + course.getCourseTitle());
        String componentName = course.isHasLab() ? "Lab" : "Coursework";
        courseworkWeightLabel.setText(componentName + " weight %");
        courseworkMaxLabel.setText(componentName + " max mark");
        courseworkWeightSpinner.getValueFactory().setValue(course.getCourseworkWeight().intValue());
        midtermWeightSpinner.getValueFactory().setValue(course.getMidtermWeight().intValue());
        finalWeightSpinner.getValueFactory().setValue(course.getFinalWeight().intValue());
        refreshMaxMarkOptions(courseworkWeightSpinner, courseworkMaxBox);
        refreshMaxMarkOptions(midtermWeightSpinner, midtermMaxBox);
        refreshMaxMarkOptions(finalWeightSpinner, finalMaxBox);
        setMaxMarkValue(courseworkMaxBox, course.getCourseworkMaxMark().intValue());
        setMaxMarkValue(midtermMaxBox, course.getMidtermMaxMark().intValue());
        setMaxMarkValue(finalMaxBox, course.getFinalMaxMark().intValue());
        loadingWeights = false;
        updateWeightsSumLabel();
    }

    /** Max mark is always either the component's weight value or 100 — never a free-typed range. */
    private void refreshMaxMarkOptions(Spinner<Integer> weightSpinner, ComboBox<Integer> maxBox) {
        int weight = weightSpinner.getValue();
        Integer previous = maxBox.getValue();
        List<Integer> options = weight == 100 ? List.of(100) : List.of(weight, 100);
        maxBox.getItems().setAll(options);
        maxBox.setValue(previous != null && options.contains(previous) ? previous : options.get(0));
    }

    /** Selects the option matching the course's stored max mark, falling back to the weight option. */
    private void setMaxMarkValue(ComboBox<Integer> maxBox, int desired) {
        maxBox.setValue(maxBox.getItems().contains(desired) ? desired : maxBox.getItems().get(0));
    }

    private void updateWeightsSumLabel() {
        if (loadingWeights) return;
        int sum = courseworkWeightSpinner.getValue() + midtermWeightSpinner.getValue() + finalWeightSpinner.getValue();
        weightsSumLabel.setText(sum == 100 ? "" : "Sum: " + sum + "% (must total 100%)");
        saveWeightsButton.setDisable(sum != 100);
    }

    private void handleSaveWeights() {
        Course selected = courseTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        int sum = courseworkWeightSpinner.getValue() + midtermWeightSpinner.getValue() + finalWeightSpinner.getValue();
        if (sum != 100) {
            AlertUtil.warn("Could not save", "Coursework/Lab, Midterm and Final weights must total 100%.");
            return;
        }

        selected.setCourseworkWeight(new BigDecimal(courseworkWeightSpinner.getValue()));
        selected.setMidtermWeight(new BigDecimal(midtermWeightSpinner.getValue()));
        selected.setFinalWeight(new BigDecimal(finalWeightSpinner.getValue()));
        selected.setCourseworkMaxMark(new BigDecimal(courseworkMaxBox.getValue()));
        selected.setMidtermMaxMark(new BigDecimal(midtermMaxBox.getValue()));
        selected.setFinalMaxMark(new BigDecimal(finalMaxBox.getValue()));

        try {
            courseService.updateCourse(selected);
            AlertUtil.success("Saved", "The grading configuration was updated.");
            reload();
            selectCourseById(selected.getCourseId());
        } catch (ServiceException se) {
            AlertUtil.warn("Could not save", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The grading configuration could not be updated.", e);
        }
    }

    private void selectCourseById(int courseId) {
        rows.stream()
                .filter(c -> c.getCourseId() == courseId)
                .findFirst()
                .ifPresent(c -> courseTable.getSelectionModel().select(c));
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

