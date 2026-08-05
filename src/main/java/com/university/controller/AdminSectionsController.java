package com.university.controller;

import com.university.controller.dialog.SectionFormDialog;
import com.university.enums.SectionStatus;
import com.university.enums.UserRole;
import com.university.model.Campus;
import com.university.model.Course;
import com.university.model.Instructor;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.service.CourseService;
import com.university.service.InstructorService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.util.StringConverter;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The admin's "Manage Sections" screen: course offerings, weekly schedules, and rules S1-S4. */
public class AdminSectionsController {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    @FXML private ComboBox<Semester> semesterFilter;
    @FXML private ComboBox<Course> courseFilter;
    @FXML private ComboBox<Instructor> instructorFilter;
    @FXML private ComboBox<SectionStatus> statusFilter;
    @FXML private TextField searchField;
    @FXML private Label countLabel;
    @FXML private TableView<Section> sectionTable;
    @FXML private TableColumn<Section, String> colCourse;
    @FXML private TableColumn<Section, String> colTitle;
    @FXML private TableColumn<Section, String> colSection;
    @FXML private TableColumn<Section, String> colSemester;
    @FXML private TableColumn<Section, String> colInstructor;
    @FXML private TableColumn<Section, String> colRoom;
    @FXML private TableColumn<Section, String> colSchedule;
    @FXML private TableColumn<Section, String> colSeats;
    @FXML private TableColumn<Section, Double> colFill;
    @FXML private TableColumn<Section, String> colStatus;
    @FXML private Button editButton;
    @FXML private Button cancelButton;
    @FXML private Button reopenButton;
    @FXML private Button deleteButton;
    @FXML private Button gradesButton;

    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();
    private final InstructorService instructorService = new InstructorService();

    private final ObservableList<Section> rows = FXCollections.observableArrayList();
    private List<Course> courses = List.of();
    private List<Semester> semesters = List.of();
    private List<Instructor> instructors = List.of();
    private List<Campus> campuses = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);

        courses = courseService.listCourses(false);
        semesters = semesterService.listAll();
        instructors = instructorService.search(null);
        campuses = sectionService.listCampuses();

        colCourse.setCellValueFactory(c -> new SimpleStringProperty(courseCodeOf(c.getValue().getCourseId())));
        colTitle.setCellValueFactory(c -> new SimpleStringProperty(courseTitleOf(c.getValue().getCourseId())));
        colSection.setCellValueFactory(new PropertyValueFactory<>("sectionNumber"));
        colSemester.setCellValueFactory(c -> new SimpleStringProperty(semesterNameOf(c.getValue().getSemesterId())));
        colInstructor.setCellValueFactory(c -> new SimpleStringProperty(instructorNameOf(c.getValue().getInstructorId())));
        colRoom.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getRoom() == null ? "—" : c.getValue().getRoom()));
        colSchedule.setCellValueFactory(c -> new SimpleStringProperty(scheduleTextOf(c.getValue().getSectionId())));
        colSeats.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getEnrolledCount() + " / " + c.getValue().getCapacity()));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getStatus().toString()));

        colFill.setCellValueFactory(c -> {
            Section s = c.getValue();
            double rate = s.getCapacity() == 0 ? 0.0 : (double) s.getEnrolledCount() / s.getCapacity();
            return new SimpleObjectProperty<>(rate);
        });
        colFill.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            @Override protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    return;
                }
                bar.setProgress(value);
                bar.setStyle(value >= 1.0 ? "-fx-accent: #c62828;" : "");
                setGraphic(bar);
            }
        });

        sectionTable.setItems(rows);
        sectionTable.setPlaceholder(new Label("No sections match your filters."));

        semesterFilter.getItems().add(null);
        semesterFilter.getItems().addAll(semesters);
        semesterFilter.setConverter(nullableConverter(s -> s.toString(), "All semesters"));
        semesterFilter.setValue(semesterService.getCurrentSemester());

        courseFilter.getItems().add(null);
        courseFilter.getItems().addAll(courses);
        courseFilter.setConverter(nullableConverter(c -> c.toString(), "All courses"));
        courseFilter.getSelectionModel().selectFirst();

        instructorFilter.getItems().add(null);
        instructorFilter.getItems().addAll(instructors);
        instructorFilter.setConverter(nullableConverter(i -> i.getFullName(), "All instructors"));
        instructorFilter.getSelectionModel().selectFirst();

        statusFilter.getItems().add(null);
        statusFilter.getItems().addAll(SectionStatus.values());
        statusFilter.setConverter(nullableConverter(st -> st.toString(), "All statuses"));
        statusFilter.getSelectionModel().selectFirst();

        searchField.textProperty().addListener((o, a, b) -> reload());
        semesterFilter.valueProperty().addListener((o, a, b) -> reload());
        courseFilter.valueProperty().addListener((o, a, b) -> reload());
        instructorFilter.valueProperty().addListener((o, a, b) -> reload());
        statusFilter.valueProperty().addListener((o, a, b) -> reload());

        var selected = sectionTable.getSelectionModel().selectedItemProperty();
        editButton.disableProperty().bind(selected.isNull());
        cancelButton.disableProperty().bind(selected.isNull());
        reopenButton.disableProperty().bind(selected.isNull());
        deleteButton.disableProperty().bind(selected.isNull());
        gradesButton.disableProperty().bind(selected.isNull());

        reload();
    }

    private <T> StringConverter<T> nullableConverter(Function<T, String> display, String nullLabel) {
        return new StringConverter<>() {
            @Override public String toString(T t) { return t == null ? nullLabel : display.apply(t); }
            @Override public T fromString(String s) { return null; }
        };
    }

    // ------------------------------------------------------------------ lookups

    private String courseCodeOf(int courseId) {
        return courses.stream().filter(c -> c.getCourseId() == courseId).findFirst()
                .map(c -> c.getCourseCode()).orElse("—");
    }

    private String courseTitleOf(int courseId) {
        return courses.stream().filter(c -> c.getCourseId() == courseId).findFirst()
                .map(c -> c.getCourseTitle()).orElse("—");
    }

    private String semesterNameOf(int semesterId) {
        return semesters.stream().filter(s -> s.getSemesterId() == semesterId).findFirst()
                .map(s -> s.getSemesterName()).orElse("—");
    }

    private String instructorNameOf(Integer instructorId) {
        if (instructorId == null) return "TBA";
        return instructors.stream().filter(i -> i.getInstructorId() == instructorId).findFirst()
                .map(i -> i.getFullName()).orElse("TBA");
    }

    private String scheduleTextOf(int sectionId) {
        List<SectionSchedule> meetings = sectionService.listMeetings(sectionId);
        if (meetings.isEmpty()) return "No meetings set";
        return meetings.stream()
                .map(m -> m.getDayOfWeek().name() + " " + HM.format(m.getStartTime()) + "-" + HM.format(m.getEndTime()))
                .collect(Collectors.joining(", "));
    }

    private String labelOf(Section s) {
        return courseCodeOf(s.getCourseId()) + "-" + s.getSectionNumber();
    }

    // ------------------------------------------------------------------ data

    private void reload() {
        try {
            Semester semester = semesterFilter.getValue();
            Course course = courseFilter.getValue();
            Instructor instructor = instructorFilter.getValue();
            SectionStatus status = statusFilter.getValue();
            String term = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();

            List<Section> result = sectionService.searchSections(
                        semester == null ? null : semester.getSemesterId(),
                        course == null ? null : course.getCourseId(),
                        instructor == null ? null : instructor.getInstructorId(),
                        status).stream()
                    .filter(s -> term.isEmpty()
                            || courseCodeOf(s.getCourseId()).toLowerCase().contains(term)
                            || courseTitleOf(s.getCourseId()).toLowerCase().contains(term)
                            || (s.getRoom() != null && s.getRoom().toLowerCase().contains(term)))
                    .toList();

            rows.setAll(result);
            countLabel.setText(result.size() + " section" + (result.size() == 1 ? "" : "s"));
        } catch (Exception e) {
            AlertUtil.error("Could not load sections", "The section list could not be loaded.", e);
        }
    }

    @FXML private void handleRefresh() { reload(); }

    @FXML
    private void handleClearFilters() {
        searchField.clear();
        semesterFilter.setValue(semesterService.getCurrentSemester());
        courseFilter.getSelectionModel().selectFirst();
        instructorFilter.getSelectionModel().selectFirst();
        statusFilter.getSelectionModel().selectFirst();
        reload();
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void handleAdd() {
        Optional<Boolean> result = new SectionFormDialog(null, courses, semesters, instructors, campuses,
                semesterService.getCurrentSemester(), sectionService).showAndWait();
        if (result.isPresent() && result.get()) {
            AlertUtil.success("Section added", "The section was created.");
            reload();
        }
    }

    @FXML
    private void handleEdit() {
        Section selected = sectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Section fresh = sectionService.findById(selected.getSectionId());
        Optional<Boolean> result = new SectionFormDialog(fresh, courses, semesters, instructors, campuses,
                semesterService.getCurrentSemester(), sectionService).showAndWait();
        if (result.isPresent() && result.get()) {
            AlertUtil.success("Saved", "The section's details were updated.");
            reload();
        }
    }

    /**
     * RULE G5 — the registrar's way into a grade sheet. There is deliberately no
     * {@code admin_grades.fxml}: this opens the instructor's own screen, which detects the ADMIN
     * role and switches itself into correction mode (submitted rows stay editable, the button
     * reads "Apply Correction", a reason is required). The role is re-checked in
     * {@code GradeService.adminOverride}, so opening the screen grants nothing on its own.
     */
    @FXML
    private void handleGrades() {
        Section selected = sectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        try {
            InstructorGradesController controller = SceneManager.getInstance()
                    .navigateTo("instructor_grades.fxml", "Grades");
            if (controller != null) {
                controller.load(selected.getSectionId(), labelOf(selected) + "  "
                        + courseTitleOf(selected.getCourseId()));
            }
        } catch (Exception e) {
            AlertUtil.error("Grades", "The grade sheet could not be opened.", e);
        }
    }

    @FXML
    private void handleCancel() {
        Section selected = sectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        int n = sectionService.countEnrollments(selected.getSectionId());
        boolean ok = AlertUtil.confirm("Cancel section",
                "Cancel " + labelOf(selected) + "?\n\n"
                + n + " student(s) have an enrolment record in it. Cancelling keeps every one of those "
                + "records and every grade — it only stops new registrations. Nothing is deleted.");
        if (!ok) return;

        try {
            sectionService.cancelSection(selected.getSectionId());
            AlertUtil.success("Cancelled", labelOf(selected) + " is now cancelled.");
            reload();
        } catch (Exception e) {
            AlertUtil.error("Could not cancel", "The section could not be cancelled.", e);
        }
    }

    @FXML
    private void handleReopen() {
        Section selected = sectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!AlertUtil.confirm("Re-open section",
                "Re-open " + labelOf(selected) + "? Students will be able to register for it again.")) {
            return;
        }
        try {
            sectionService.reopenSection(selected.getSectionId());
            AlertUtil.success("Re-opened", labelOf(selected) + " is open again.");
            reload();
        } catch (Exception e) {
            AlertUtil.error("Could not re-open", "The section could not be re-opened.", e);
        }
    }

    @FXML
    private void handleDelete() {
        Section selected = sectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!AlertUtil.confirm("Delete section",
                "Permanently delete " + labelOf(selected) + "? This is only possible because it has "
                + "never had a single enrolment.")) {
            return;
        }
        try {
            sectionService.deleteSection(selected.getSectionId());
            AlertUtil.success("Deleted", "The section was deleted.");
            reload();
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot delete this section", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not delete", "The section could not be deleted.", e);
        }
    }
}
