package com.university.controller;

import com.university.enums.SectionStatus;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.Enrollment;
import com.university.model.Instructor;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.service.CourseService;
import com.university.service.InstructorService;
import com.university.service.RegistrationException;
import com.university.service.RegistrationService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.service.TranscriptService;
import com.university.util.AlertUtil;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The student's "Register for Courses" screen: browse open sections in the current semester,
 * register (enforcing rules R1-R8), and drop or withdraw from a current registration.
 */
public class StudentRegistrationController {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    @FXML private Label semesterLabel;
    @FXML private Label creditsBadge;
    @FXML private Label errorLabel;

    @FXML private TableView<Section> availableTable;
    @FXML private TableColumn<Section, String> colCourse;
    @FXML private TableColumn<Section, String> colTitle;
    @FXML private TableColumn<Section, Integer> colCredits;
    @FXML private TableColumn<Section, String> colInstructor;
    @FXML private TableColumn<Section, String> colSchedule;
    @FXML private TableColumn<Section, String> colSeats;
    @FXML private TableColumn<Section, String> colStatus;
    @FXML private Button registerButton;
    @FXML private TextField courseCodeSearchField;
    @FXML private CheckBox hideFullCheckBox;

    @FXML private TableView<Section> myTable;
    @FXML private TableColumn<Section, String> myColCourse;
    @FXML private TableColumn<Section, String> myColSection;
    @FXML private TableColumn<Section, String> myColTitle;
    @FXML private TableColumn<Section, Integer> myColCredits;
    @FXML private TableColumn<Section, String> myColSchedule;
    @FXML private TableColumn<Section, String> myColRoom;
    @FXML private Button dropButton;

    private final RegistrationService registrationService = new RegistrationService();
    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();
    private final InstructorService instructorService = new InstructorService();
    private final TranscriptService transcriptService = new TranscriptService();

    private final ObservableList<Section> availableRows = FXCollections.observableArrayList();
    private final ObservableList<Section> myRows = FXCollections.observableArrayList();

    /** Every section {@link #reloadAvailable()} loaded, before the course-code search and
     *  Hide Full Sections filter are applied — what {@link #applyAvailableFilters()} filters
     *  from and {@link #handleClearFilters()} restores. */
    private List<Section> allAvailableSections = List.of();

    private List<Course> courses = List.of();
    private List<Instructor> instructors = List.of();
    private Semester currentSemester;
    private int studentId;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        studentId = Session.current().requireStudentId();

        courses = courseService.listCourses(true);
        instructors = instructorService.listActive();
        currentSemester = semesterService.getCurrentSemester();

        colCourse.setCellValueFactory(c -> new SimpleStringProperty(courseCodeOf(c.getValue().getCourseId())));
        colTitle.setCellValueFactory(c -> new SimpleStringProperty(courseTitleOf(c.getValue().getCourseId())));
        colCredits.setCellValueFactory(c -> new SimpleObjectProperty<>(courseCreditsOf(c.getValue().getCourseId())));
        colInstructor.setCellValueFactory(c -> new SimpleStringProperty(instructorNameOf(c.getValue().getInstructorId())));
        colSchedule.setCellValueFactory(c -> new SimpleStringProperty(scheduleTextOf(c.getValue().getSectionId())));
        colSeats.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getEnrolledCount() + " / " + c.getValue().getCapacity()));
        colStatus.setCellValueFactory(c -> {
            Section s = c.getValue();
            return new SimpleStringProperty(s.isFull() ? "Full"
                    : (s.getCapacity() - s.getEnrolledCount()) + " seat(s) left");
        });
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().remove("section-full");
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                setText(value);
                if ("Full".equals(value)) {
                    getStyleClass().add("section-full");
                }
            }
        });

        myColCourse.setCellValueFactory(c -> new SimpleStringProperty(courseCodeOf(c.getValue().getCourseId())));
        myColSection.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getSectionNumber()));
        myColTitle.setCellValueFactory(c -> new SimpleStringProperty(courseTitleOf(c.getValue().getCourseId())));
        myColCredits.setCellValueFactory(c -> new SimpleObjectProperty<>(courseCreditsOf(c.getValue().getCourseId())));
        myColSchedule.setCellValueFactory(c -> new SimpleStringProperty(scheduleTextOf(c.getValue().getSectionId())));
        myColRoom.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getRoom() == null ? "—" : c.getValue().getRoom()));

        availableTable.setItems(availableRows);
        availableTable.setPlaceholder(new Label("No sections are available to register for right now."));
        availableTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        myTable.setItems(myRows);
        myTable.setPlaceholder(new Label("You are not registered in any section this semester."));
        myTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Sized to show every current row without its own inner scrollbar, the same way the
        // Study Plan's per-semester tables are sized — the page-level ScrollPane in the FXML
        // is what scrolls the whole screen if these grow taller than the window.
        bindTableHeightToRowCount(availableTable, availableRows);
        bindTableHeightToRowCount(myTable, myRows);

        courseCodeSearchField.textProperty().addListener((obs, old, val) -> applyAvailableFilters());

        registerButton.disableProperty().bind(availableTable.getSelectionModel().selectedItemProperty().isNull());
        dropButton.disableProperty().bind(myTable.getSelectionModel().selectedItemProperty().isNull());

        updateSemesterBanner();
        reload();
    }

    private void bindTableHeightToRowCount(TableView<Section> table, ObservableList<Section> items) {
        double rowHeight = 28;
        double headerHeight = 32;
        table.setFixedCellSize(rowHeight);
        DoubleBinding height = Bindings.createDoubleBinding(
                () -> headerHeight + Math.max(1, items.size()) * rowHeight + 2,
                items);
        table.prefHeightProperty().bind(height);
        table.minHeightProperty().bind(height);
        table.maxHeightProperty().bind(height);
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

    private int courseCreditsOf(int courseId) {
        return courses.stream().filter(c -> c.getCourseId() == courseId).findFirst()
                .map(c -> c.getCredits()).orElse(0);
    }

    private String instructorNameOf(Integer instructorId) {
        if (instructorId == null) {
            return "TBA";
        }
        return instructors.stream().filter(i -> i.getInstructorId() == instructorId).findFirst()
                .map(i -> i.getFullName()).orElse("TBA");
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

    /**
     * The course IDs this student may register for right now: not passed, not currently
     * in progress, with prerequisites met and any credit threshold satisfied — regardless
     * of whether the course belongs to the student's current Study Plan semester or is
     * backlog from an earlier one (or a previously failed, legitimately repeatable attempt).
     * {@code NOT_YET_AVAILABLE} rows are included here on purpose: that status only means the
     * sequential Study Plan "stage" hasn't reached the course yet, not that anything actually
     * blocks it (that's what {@code LOCKED} is for) — registration eligibility looks at each
     * course's own prerequisites, not the student's overall plan sequencing.
     */
    private Set<Integer> eligibleCourseIds() {
        return transcriptService.getDegreeProgress(studentId).requirements.stream()
                .filter(row -> row.isEligible() || row.isFailed() || row.isNotYetAvailable())
                .map(row -> row.courseId)
                .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ data

    private void updateSemesterBanner() {
        if (currentSemester == null) {
            semesterLabel.setText("No current semester is set. Registration is unavailable.");
            return;
        }
        boolean open = currentSemester.isRegistrationOpen(LocalDateTime.now());
        semesterLabel.setText("Current semester: " + currentSemester.getSemesterName()
                + "  ·  Registration " + (open ? "OPEN" : "CLOSED"));
    }

    private void reload() {
        reloadAvailable();
        reloadMine();
        updateCreditsBadge();
        availableTable.refresh();
        myTable.refresh();
    }

    private void reloadAvailable() {
        if (currentSemester == null) {
            allAvailableSections = List.of();
            applyAvailableFilters();
            return;
        }
        try {
            Set<Integer> studyPlanCourseIds = eligibleCourseIds();

            // Belt-and-braces on top of the study-plan filter above: a course the student already
            // holds a non-dropped enrollment in this semester (ENROLLED, COMPLETED or WITHDRAWN)
            // must never appear as available to register for again, whatever section it is under —
            // this is what keeps the table (and a stale copy of it still open in the UI) from ever
            // offering a course the student is already registered in.
            Set<Integer> alreadyEnrolledCourseIds = registrationService
                    .myClassesForSemester(studentId, currentSemester.getSemesterId()).stream()
                    .map(e -> sectionService.findById(e.getSectionId()))
                    .filter(java.util.Objects::nonNull)
                    .map(Section::getCourseId)
                    .collect(Collectors.toSet());

            allAvailableSections = sectionService
                    .searchSections(currentSemester.getSemesterId(), null, null, SectionStatus.OPEN).stream()
                    .filter(s -> studyPlanCourseIds.contains(s.getCourseId()))
                    .filter(s -> !alreadyEnrolledCourseIds.contains(s.getCourseId()))
                    .toList();

            applyAvailableFilters();
        } catch (Exception e) {
            AlertUtil.error("Could not load sections", "The section list could not be loaded.", e);
        }
    }

    /**
     * Re-derives {@link #availableRows} from {@link #allAvailableSections} using the search field
     * (matched against both course code and course title) and the Hide Full Sections checkbox —
     * a display-only filter over sections already loaded for the current term/student. It never
     * re-queries the database and never changes which sections exist or how full they are.
     */
    private void applyAvailableFilters() {
        String query = courseCodeSearchField == null || courseCodeSearchField.getText() == null
                ? "" : courseCodeSearchField.getText().trim().toLowerCase();
        boolean hideFull = hideFullCheckBox != null && hideFullCheckBox.isSelected();

        List<Section> filtered = allAvailableSections.stream()
                .filter(s -> query.isEmpty()
                        || courseCodeOf(s.getCourseId()).toLowerCase().contains(query)
                        || courseTitleOf(s.getCourseId()).toLowerCase().contains(query))
                .filter(s -> !hideFull || !s.isFull())
                .toList();

        availableRows.setAll(filtered);
    }

    private void reloadMine() {
        if (currentSemester == null) {
            myRows.clear();
            return;
        }
        try {
            List<Section> sections = sectionService.listForStudent(studentId, currentSemester.getSemesterId());
            myRows.setAll(sections);
        } catch (Exception e) {
            AlertUtil.error("Could not load your registrations", "Your registrations could not be loaded.", e);
        }
    }

    private void updateCreditsBadge() {
        if (currentSemester == null || Session.current().getStudent() == null) {
            creditsBadge.setText("");
            return;
        }
        int cap = registrationService.creditCapFor(Session.current().getStudent());
        int current = registrationService.currentCreditLoad(studentId, currentSemester.getSemesterId());
        creditsBadge.setText(current + " / " + cap + " credits");
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void handleSearchAvailable() {
        applyAvailableFilters();
    }

    @FXML
    private void handleFiltersChanged() {
        applyAvailableFilters();
    }

    @FXML
    private void handleClearFilters() {
        courseCodeSearchField.clear();
        hideFullCheckBox.setSelected(false);
        applyAvailableFilters();
    }

    @FXML
    private void handleRefresh() {
        currentSemester = semesterService.getCurrentSemester();
        updateSemesterBanner();
        reload();
    }

    @FXML
    private void handleRegister() {
        Section selected = availableTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        errorLabel.setText("");
        try {
            registrationService.registerStudent(studentId, selected.getSectionId());
            AlertUtil.success("Registered", "You are now registered in "
                    + courseCodeOf(selected.getCourseId()) + "-" + selected.getSectionNumber() + ".");
            reload();
        } catch (RegistrationException re) {
            errorLabel.setText(re.getMessage());
            AlertUtil.warn("Cannot register", re.getMessage());
        } catch (ServiceException se) {
            errorLabel.setText(se.getMessage());
            AlertUtil.warn("Cannot register", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Registration failed", "The registration could not be completed.", e);
        }
    }

    @FXML
    private void handleDrop() {
        Section selected = myTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        String label = courseCodeOf(selected.getCourseId()) + "-" + selected.getSectionNumber();

        boolean freeDrop = currentSemester == null || registrationService.isWithinFreeDropWindow(currentSemester);
        boolean confirmed = freeDrop
                ? AlertUtil.confirm("Drop course",
                        "Drop " + label + "? It will not appear on your transcript and your seat will be released.")
                : AlertUtil.confirm("Withdraw from course",
                        "The drop deadline has passed. Dropping now records a grade of W on your transcript. "
                                + "The W does not affect your GPA. Withdraw from " + label + "?");
        if (!confirmed) {
            return;
        }

        try {
            RegistrationService.DropResult result = registrationService.dropSection(studentId, selected.getSectionId());
            String message = result.getResultMessage();
            if (result.isWithdrawal()) {
                AlertUtil.warn(result.getResultTitle(), message);
            } else {
                AlertUtil.info(result.getResultTitle(), message);
            }
            reload();
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot drop this course", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not drop the course", "The change could not be completed.", e);
        }
    }
}

