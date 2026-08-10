package com.university.controller;

import com.university.controller.dialog.ExamFormDialog;
import com.university.enums.NotificationType;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.GradeSheetRow;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.service.CourseService;
import com.university.service.ExamService;
import com.university.service.GradeService;
import com.university.service.NotificationService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import com.university.util.ValidationUtil;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    // ------------------------------------------------------------ notification section
    @FXML private RadioButton notifyAllRadio;
    @FXML private RadioButton notifySectionRadio;
    @FXML private RadioButton notifySpecificRadio;
    @FXML private HBox notifySectionRow;
    @FXML private HBox notifySpecificRow;
    @FXML private ComboBox<Section> notifySectionCombo;
    @FXML private TextField notifySearchField;
    @FXML private ComboBox<GradeSheetRow> notifyResultsBox;
    @FXML private Label notifyRecipientCountLabel;
    @FXML private TextField notifySubjectField;
    @FXML private TextArea notifyMessageField;

    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();
    private final GradeService gradeService = new GradeService();
    private final ExamService examService = new ExamService();
    private final NotificationService notificationService = new NotificationService();

    private final ObservableList<Section> rows = FXCollections.observableArrayList();
    private List<Course> courses = List.of();
    /** Every student across this instructor's own sections this semester, deduplicated. */
    private List<GradeSheetRow> allMyStudents = List.of();

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

        // Sized to show every one of the instructor's sections without its own inner scrollbar —
        // the page-level ScrollPane in the FXML is what scrolls the whole screen if the section
        // list runs taller than the window.
        double headerHeight = 34;
        sectionTable.setFixedCellSize(32);
        sectionTable.prefHeightProperty().bind(
                Bindings.max(Bindings.size(rows), 1)
                        .multiply(sectionTable.fixedCellSizeProperty())
                        .add(headerHeight));
        sectionTable.minHeightProperty().bind(sectionTable.prefHeightProperty());
        sectionTable.maxHeightProperty().bind(sectionTable.prefHeightProperty());

        var selected = sectionTable.getSelectionModel().selectedItemProperty();
        enterGradesButton.disableProperty().bind(selected.isNull());
        viewRosterButton.disableProperty().bind(selected.isNull());
        addExamButton.disableProperty().bind(selected.isNull());

        reload();
        initNotificationSection();
    }

    private void reload() {
        try {
            Semester currentSemester = semesterService.getCurrentSemester();
            int instructorId = Session.current().requireInstructorId();

            if (currentSemester == null) {
                semesterLabel.setText("No current semester is set.");
                rows.clear();
                allMyStudents = List.of();
                refreshNotifyData();
                return;
            }
            semesterLabel.setText("Semester: " + currentSemester.getSemesterName());

            List<Section> mine = sectionService.searchSections(
                    currentSemester.getSemesterId(), null, instructorId, null);
            rows.setAll(mine);
            sectionTable.refresh();

            allMyStudents = mine.stream()
                    .flatMap(s -> gradeService.getGradeSheet(s.getSectionId()).stream())
                    .collect(Collectors.toMap(GradeSheetRow::getStudentUserId, r -> r, (a, b) -> a, LinkedHashMap::new))
                    .values().stream()
                    .sorted(Comparator.comparing(GradeSheetRow::getStudentUserId))
                    .toList();
            refreshNotifyData();
        } catch (Exception e) {
            AlertUtil.error("My Sections", "Your sections could not be loaded.", e);
        }
    }

    /** Re-syncs the notification section's section list / search results / recipient count after a reload. */
    private void refreshNotifyData() {
        notifySectionCombo.getItems().setAll(rows);
        notifySectionCombo.setValue(null);
        refreshNotifySearchResults();
        updateRecipientCount();
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

    // ------------------------------------------------------------------ send notification

    private void initNotificationSection() {
        ToggleGroup recipientGroup = new ToggleGroup();
        notifyAllRadio.setToggleGroup(recipientGroup);
        notifySectionRadio.setToggleGroup(recipientGroup);
        notifySpecificRadio.setToggleGroup(recipientGroup);
        recipientGroup.selectedToggleProperty().addListener((o, a, b) -> updateNotifySectionVisibility());

        notifySectionCombo.setConverter(new StringConverter<>() {
            @Override public String toString(Section s) {
                return s == null ? "Select section" : courseCodeOf(s.getCourseId()) + "-" + s.getSectionNumber();
            }
            @Override public Section fromString(String s) { return null; }
        });
        notifySectionCombo.valueProperty().addListener((o, a, b) -> updateRecipientCount());

        notifyResultsBox.setConverter(new StringConverter<>() {
            @Override public String toString(GradeSheetRow r) {
                return r == null ? "" : r.getStudentUserId() + " - " + r.getStudentName();
            }
            @Override public GradeSheetRow fromString(String s) { return null; }
        });
        notifyResultsBox.valueProperty().addListener((o, a, b) -> updateRecipientCount());

        notifySearchField.textProperty().addListener((o, a, b) -> refreshNotifySearchResults());

        updateNotifySectionVisibility();
    }

    private void updateNotifySectionVisibility() {
        boolean sectionMode = notifySectionRadio.isSelected();
        boolean specificMode = notifySpecificRadio.isSelected();
        notifySectionRow.setVisible(sectionMode);
        notifySectionRow.setManaged(sectionMode);
        notifySpecificRow.setVisible(specificMode);
        notifySpecificRow.setManaged(specificMode);
        updateRecipientCount();
    }

    private void refreshNotifySearchResults() {
        notifyResultsBox.setValue(null);
        String term = notifySearchField.getText();
        if (ValidationUtil.isBlank(term)) {
            notifyResultsBox.getItems().clear();
            return;
        }
        String needle = term.trim().toLowerCase();
        List<GradeSheetRow> matches = allMyStudents.stream()
                .filter(s -> String.valueOf(s.getStudentUserId()).contains(needle)
                        || s.getStudentName().toLowerCase().contains(needle))
                .toList();
        notifyResultsBox.getItems().setAll(matches);
        if (!matches.isEmpty()) {
            notifyResultsBox.show();
        }
    }

    /**
     * Every account this send would reach. Every branch is scoped to the instructor's own
     * sections this semester — {@link #allMyStudents} and per-section rosters are both built
     * from {@link SectionService#searchSections} results for {@code Session.current()}'s
     * instructor id, so another instructor's students are never reachable here.
     */
    private List<GradeSheetRow> resolveNotifyRecipients() {
        if (notifySpecificRadio.isSelected()) {
            GradeSheetRow selected = notifyResultsBox.getValue();
            return selected == null ? List.of() : List.of(selected);
        }
        if (notifySectionRadio.isSelected()) {
            Section section = notifySectionCombo.getValue();
            return section == null ? List.of() : gradeService.getGradeSheet(section.getSectionId());
        }
        return allMyStudents;
    }

    private void updateRecipientCount() {
        int count = resolveNotifyRecipients().size();
        notifyRecipientCountLabel.setText(count + " student" + (count == 1 ? "" : "s") + " will receive this notification.");
    }

    private String describeNotifyRecipients(List<GradeSheetRow> recipients) {
        if (notifySpecificRadio.isSelected()) {
            GradeSheetRow r = recipients.get(0);
            return r.getStudentName() + " (Student ID " + r.getStudentUserId() + ")";
        }
        if (notifySectionRadio.isSelected()) {
            Section section = notifySectionCombo.getValue();
            String label = section == null ? "the selected section"
                    : courseCodeOf(section.getCourseId()) + "-" + section.getSectionNumber();
            return recipients.size() + " student" + (recipients.size() == 1 ? "" : "s") + " in " + label;
        }
        return "all " + recipients.size() + " of your student" + (recipients.size() == 1 ? "" : "s");
    }

    @FXML
    private void handleSendNotification() {
        String subject = notifySubjectField.getText() == null ? "" : notifySubjectField.getText().trim();
        String message = notifyMessageField.getText() == null ? "" : notifyMessageField.getText().trim();

        if (ValidationUtil.isBlank(subject) || !ValidationUtil.maxLength(subject, 100)) {
            AlertUtil.warn("Cannot send notification", "Subject is required (maximum 100 characters).");
            return;
        }
        if (ValidationUtil.isBlank(message) || !ValidationUtil.maxLength(message, 500)) {
            AlertUtil.warn("Cannot send notification", "Message is required (maximum 500 characters).");
            return;
        }
        if (notifySectionRadio.isSelected() && notifySectionCombo.getValue() == null) {
            AlertUtil.warn("Cannot send notification", "Select a section.");
            return;
        }
        if (notifySpecificRadio.isSelected() && notifyResultsBox.getValue() == null) {
            AlertUtil.warn("Cannot send notification", "Search for and select a student.");
            return;
        }

        List<GradeSheetRow> recipients = resolveNotifyRecipients();
        if (recipients.isEmpty()) {
            AlertUtil.warn("No recipients", "No students match the selected recipients.");
            return;
        }

        boolean ok = AlertUtil.confirm("Send notification",
                "Send this notification to " + describeNotifyRecipients(recipients) + "?");
        if (!ok) return;

        try {
            List<Integer> userIds = recipients.stream().map(GradeSheetRow::getStudentUserId).toList();
            notificationService.notifyAll(userIds, NotificationType.GENERAL, subject, message);
            AlertUtil.success("Notification sent",
                    "Sent to " + recipients.size() + " student" + (recipients.size() == 1 ? "" : "s") + ".");
            notifySubjectField.clear();
            notifyMessageField.clear();
            notifySearchField.clear();
            notifyResultsBox.getItems().clear();
            notifyResultsBox.setValue(null);
            updateRecipientCount();
        } catch (ServiceException se) {
            AlertUtil.warn("Could not send", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not send", "The notification could not be sent.", e);
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
