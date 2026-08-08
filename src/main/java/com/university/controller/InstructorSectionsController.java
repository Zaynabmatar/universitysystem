package com.university.controller;

import com.university.controller.dialog.ExamFormDialog;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.service.CourseService;
import com.university.service.ExamService;
import com.university.service.GradeService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The instructor's landing list for grading: only the sections THEY teach this semester
 * (project_details.md Section 2.2). This is the gateway to the grade sheet.
 *
 * <p>Rule G1 is enforced here at the query level, not only by disabling a button —
 * {@link SectionService#searchSections} is asked for sections belonging to this instructor's
 * {@code instructor_id} and nobody else's, so another instructor's section is never even loaded
 * into memory, let alone shown.</p>
 */
public class InstructorSectionsController {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    @FXML private Label semesterLabel;
    @FXML private TableView<Section> sectionTable;
    @FXML private TableColumn<Section, String> colCourse;
    @FXML private TableColumn<Section, String> colTitle;
    @FXML private TableColumn<Section, String> colSection;
    @FXML private TableColumn<Section, String> colRoom;
    @FXML private TableColumn<Section, String> colSchedule;
    @FXML private TableColumn<Section, String> colSeats;
    @FXML private TableColumn<Section, String> colGradeStatus;
    @FXML private javafx.scene.control.Button enterGradesButton;
    @FXML private javafx.scene.control.Button viewRosterButton;
    @FXML private javafx.scene.control.Button addExamButton;

    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();
    private final GradeService gradeService = new GradeService();
    private final ExamService examService = new ExamService();

    private final ObservableList<Section> rows = FXCollections.observableArrayList();
    private List<Course> courses = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.INSTRUCTOR);

        courses = courseService.listCourses(false);

        colCourse.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                courseCodeOf(c.getValue().getCourseId())));
        colTitle.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                courseTitleOf(c.getValue().getCourseId())));
        colSection.setCellValueFactory(new PropertyValueFactory<>("sectionNumber"));
        colRoom.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getRoom() == null ? "—" : c.getValue().getRoom()));
        colSchedule.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                scheduleTextOf(c.getValue().getSectionId())));
        colSeats.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getEnrolledCount() + " / " + c.getValue().getCapacity()));
        colGradeStatus.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                gradeService.gradeStatusLabel(c.getValue().getSectionId())));
        colGradeStatus.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                getStyleClass().removeAll("badge-ok", "badge-warn", "badge-neutral");
                if (empty || status == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(status);
                if (status.startsWith("Submitted")) {
                    getStyleClass().add("badge-ok");
                } else if (status.equals("Draft saved")) {
                    getStyleClass().add("badge-warn");
                } else {
                    getStyleClass().add("badge-neutral");
                }
            }
        });

        sectionTable.setItems(rows);
        sectionTable.setPlaceholder(new Label("You are not teaching any sections this semester."));

        var selected = sectionTable.getSelectionModel().selectedItemProperty();
        enterGradesButton.disableProperty().bind(selected.isNull());
        viewRosterButton.disableProperty().bind(selected.isNull());
        addExamButton.disableProperty().bind(selected.isNull());

        reload();
    }

    private void reload() {
        try {
            Semester currentSemester = semesterService.getCurrentSemester();
            int instructorId = Session.current().requireInstructorId();

            if (currentSemester == null) {
                semesterLabel.setText("No current semester is set.");
                rows.clear();
                return;
            }
            semesterLabel.setText("Semester: " + currentSemester.getSemesterName());

            List<Section> mine = sectionService.searchSections(
                    currentSemester.getSemesterId(), null, instructorId, null);
            rows.setAll(mine);
            sectionTable.refresh();
        } catch (Exception e) {
            AlertUtil.error("My Sections", "Your sections could not be loaded.", e);
        }
    }

    @FXML
    private void handleRefresh() {
        reload();
    }

    @FXML
    private void handleEnterGrades() {
        Section selected = sectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            AlertUtil.error("Select a section", "Select one of your sections first.");
            return;
        }
        try {
            InstructorGradesController controller =
                    SceneManager.getInstance().navigateTo("instructor_grades.fxml", "Enter Grades");
            if (controller != null) {
                controller.load(selected.getSectionId(),
                        courseCodeOf(selected.getCourseId()) + "-" + selected.getSectionNumber()
                                + "  " + courseTitleOf(selected.getCourseId()));
            }
        } catch (Exception e) {
            AlertUtil.error("Grades", "The grade sheet could not be opened.", e);
        }
    }

    @FXML
    private void handleViewRoster() {
        Section selected = sectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        List<String> names;
        try {
            names = rosterNames(selected.getSectionId());
        } catch (RuntimeException e) {
            AlertUtil.error("Students", "The student list could not be loaded.", e);
            return;
        }

        ListView<String> listView = new ListView<>(FXCollections.observableArrayList(names));
        listView.setPrefSize(320, 300);
        listView.setPlaceholder(new Label("No students are enrolled in this section."));

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Students");
        dialog.setHeaderText(courseCodeOf(selected.getCourseId()) + "-" + selected.getSectionNumber());
        dialog.getDialogPane().setContent(listView);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        var css = getClass().getResource("/css/app.css");
        if (css != null) {
            dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        }
        dialog.showAndWait();
    }

    @FXML
    private void handleAddExam() {
        Section selected = sectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        String label = courseCodeOf(selected.getCourseId()) + "-" + selected.getSectionNumber();
        int actingUserId = Session.current().getUser().getUserId();

        ExamFormDialog dialog = new ExamFormDialog(selected, label, examService, actingUserId);
        Boolean saved = dialog.showAndWait().orElse(false);
        if (Boolean.TRUE.equals(saved)) {
            AlertUtil.success("Exam added", "The exam for " + label + " is now visible to every enrolled student.");
        }
    }

    private List<String> rosterNames(int sectionId) {
        return gradeService.getGradeSheet(sectionId).stream()
                .map(row -> row.getStudentUserId() + " — " + row.getStudentName())
                .collect(Collectors.toList());
    }

    private String courseCodeOf(int courseId) {
        return courses.stream().filter(c -> c.getCourseId() == courseId).findFirst()
                .map(c -> c.getCourseCode()).orElse("—");
    }

    private String courseTitleOf(int courseId) {
        return courses.stream().filter(c -> c.getCourseId() == courseId).findFirst()
                .map(c -> c.getCourseTitle()).orElse("—");
    }

    private String scheduleTextOf(int sectionId) {
        List<SectionSchedule> meetings = sectionService.listMeetings(sectionId);
        if (meetings.isEmpty()) {
            return "No meetings set";
        }
        return meetings.stream()
                .map(m -> m.getDayOfWeek().name() + " " + HM.format(m.getStartTime()) + "-" + HM.format(m.getEndTime()))
                .collect(Collectors.joining(", "));
    }
}
