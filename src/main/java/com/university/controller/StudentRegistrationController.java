package com.university.controller;

import com.university.enums.DayOfWeekCode;
import com.university.enums.SectionStatus;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.Department;
import com.university.model.Enrollment;
import com.university.model.Instructor;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.model.Waitlist;
import com.university.service.CourseService;
import com.university.service.InstructorService;
import com.university.service.RegistrationException;
import com.university.service.RegistrationService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.service.WaitlistService;
import com.university.util.AlertUtil;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The student's "Register for Courses" screen: browse open sections in the current semester,
 * register (enforcing rules R1-R8), and drop or withdraw from a current registration.
 */
public class StudentRegistrationController {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    @FXML private ComboBox<Department> departmentFilter;
    @FXML private TextField searchField;
    @FXML private ComboBox<DayOfWeekCode> dayFilter;
    @FXML private CheckBox freeSeatOnly;
    @FXML private Label countLabel;
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

    @FXML private TableView<Section> myTable;
    @FXML private TableColumn<Section, String> myColCourse;
    @FXML private TableColumn<Section, String> myColTitle;
    @FXML private TableColumn<Section, Integer> myColCredits;
    @FXML private TableColumn<Section, String> myColSchedule;
    @FXML private TableColumn<Section, String> myColRoom;
    @FXML private Button dropButton;

    @FXML private TableView<Waitlist> waitlistTable;
    @FXML private TableColumn<Waitlist, String> wlColCourse;
    @FXML private TableColumn<Waitlist, String> wlColSection;
    @FXML private TableColumn<Waitlist, Integer> wlColPosition;
    @FXML private Button leaveWaitlistButton;

    private final RegistrationService registrationService = new RegistrationService();
    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();
    private final InstructorService instructorService = new InstructorService();
    private final WaitlistService waitlistService = new WaitlistService();

    private final ObservableList<Section> availableRows = FXCollections.observableArrayList();
    private final ObservableList<Section> myRows = FXCollections.observableArrayList();
    private final ObservableList<Waitlist> waitlistRows = FXCollections.observableArrayList();

    private List<Course> courses = List.of();
    private List<Department> departments = List.of();
    private List<Instructor> instructors = List.of();
    private Semester currentSemester;
    private int studentId;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        studentId = Session.current().requireStudentId();

        courses = courseService.listCourses(true);
        departments = courseService.listDepartments(true);
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
        myColTitle.setCellValueFactory(c -> new SimpleStringProperty(courseTitleOf(c.getValue().getCourseId())));
        myColCredits.setCellValueFactory(c -> new SimpleObjectProperty<>(courseCreditsOf(c.getValue().getCourseId())));
        myColSchedule.setCellValueFactory(c -> new SimpleStringProperty(scheduleTextOf(c.getValue().getSectionId())));
        myColRoom.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getRoom() == null ? "—" : c.getValue().getRoom()));

        wlColCourse.setCellValueFactory(c -> new SimpleStringProperty(
                courseCodeOf(sectionOf(c.getValue()).map(s -> s.getCourseId()).orElse(-1))));
        wlColSection.setCellValueFactory(c -> new SimpleStringProperty(
                sectionOf(c.getValue()).map(s -> s.getSectionNumber()).orElse("—")));
        wlColPosition.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getPosition()));

        availableTable.setItems(availableRows);
        availableTable.setPlaceholder(new Label("No sections match your filters."));
        myTable.setItems(myRows);
        myTable.setPlaceholder(new Label("You are not registered in any section this semester."));
        waitlistTable.setItems(waitlistRows);
        waitlistTable.setPlaceholder(new Label("You are not on any waiting list."));

        departmentFilter.getItems().add(null);
        departmentFilter.getItems().addAll(departments);
        departmentFilter.setConverter(nullableConverter(d -> d.getDepartmentName(), "All departments"));
        departmentFilter.getSelectionModel().selectFirst();

        dayFilter.getItems().add(null);
        dayFilter.getItems().addAll(DayOfWeekCode.values());
        dayFilter.setConverter(nullableConverter(d -> d.toString(), "All days"));
        dayFilter.getSelectionModel().selectFirst();

        searchField.textProperty().addListener((o, a, b) -> reloadAvailable());
        departmentFilter.valueProperty().addListener((o, a, b) -> reloadAvailable());
        dayFilter.valueProperty().addListener((o, a, b) -> reloadAvailable());
        freeSeatOnly.selectedProperty().addListener((o, a, b) -> reloadAvailable());

        registerButton.disableProperty().bind(availableTable.getSelectionModel().selectedItemProperty().isNull());
        dropButton.disableProperty().bind(myTable.getSelectionModel().selectedItemProperty().isNull());
        leaveWaitlistButton.disableProperty().bind(waitlistTable.getSelectionModel().selectedItemProperty().isNull());

        updateSemesterBanner();
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

    private int courseCreditsOf(int courseId) {
        return courses.stream().filter(c -> c.getCourseId() == courseId).findFirst()
                .map(c -> c.getCredits()).orElse(0);
    }

    private Integer courseDepartmentOf(int courseId) {
        return courses.stream().filter(c -> c.getCourseId() == courseId).findFirst()
                .map(c -> c.getDepartmentId()).orElse(null);
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

    private boolean meetsOnDay(int sectionId, DayOfWeekCode day) {
        return sectionService.listMeetings(sectionId).stream().anyMatch(m -> m.getDayOfWeek() == day);
    }

    private java.util.Optional<Section> sectionOf(Waitlist waitlist) {
        try {
            return java.util.Optional.ofNullable(sectionService.findById(waitlist.getSectionId()));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
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
        reloadWaitlist();
        updateCreditsBadge();
    }

    private void reloadWaitlist() {
        try {
            waitlistRows.setAll(waitlistService.getMyWaitlist(studentId));
        } catch (Exception e) {
            AlertUtil.error("Could not load your waitlist", "Your waitlist entries could not be loaded.", e);
        }
    }

    private void reloadAvailable() {
        if (currentSemester == null) {
            availableRows.clear();
            countLabel.setText("0 sections");
            return;
        }
        try {
            Department dept = departmentFilter.getValue();
            DayOfWeekCode day = dayFilter.getValue();
            String term = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
            boolean onlyFree = freeSeatOnly.isSelected();

            List<Section> result = sectionService
                    .searchSections(currentSemester.getSemesterId(), null, null, SectionStatus.OPEN).stream()
                    .filter(s -> dept == null
                            || dept.getDepartmentId() == java.util.Objects.requireNonNullElse(
                                    courseDepartmentOf(s.getCourseId()), -1))
                    .filter(s -> term.isEmpty()
                            || courseCodeOf(s.getCourseId()).toLowerCase().contains(term)
                            || courseTitleOf(s.getCourseId()).toLowerCase().contains(term))
                    .filter(s -> day == null || meetsOnDay(s.getSectionId(), day))
                    .filter(s -> !onlyFree || !s.isFull())
                    .toList();

            availableRows.setAll(result);
            countLabel.setText(result.size() + " section" + (result.size() == 1 ? "" : "s"));
        } catch (Exception e) {
            AlertUtil.error("Could not load sections", "The section list could not be loaded.", e);
        }
    }

    private void reloadMine() {
        if (currentSemester == null) {
            myRows.clear();
            return;
        }
        try {
            List<Enrollment> mine = registrationService.currentRegistrations(studentId, currentSemester.getSemesterId());
            List<Section> sections = mine.stream()
                    .map(e -> sectionService.findById(e.getSectionId()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
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
    private void handleRefresh() {
        currentSemester = semesterService.getCurrentSemester();
        updateSemesterBanner();
        reload();
    }

    @FXML
    private void handleClearFilters() {
        searchField.clear();
        departmentFilter.getSelectionModel().selectFirst();
        dayFilter.getSelectionModel().selectFirst();
        freeSeatOnly.setSelected(false);
        reloadAvailable();
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
            if (re.isWaitlistOffer()) {
                offerWaitlist(re);
            } else {
                AlertUtil.warn("Cannot register", re.getMessage());
            }
        } catch (ServiceException se) {
            errorLabel.setText(se.getMessage());
            AlertUtil.warn("Cannot register", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Registration failed", "The registration could not be completed.", e);
        }
    }

    private void offerWaitlist(RegistrationException ex) {
        boolean join = AlertUtil.confirm("Join the waiting list",
                "This section is full. You would be #" + ex.getWaitlistPosition()
                        + " in line. Join the waiting list?");
        if (!join) {
            return;
        }
        try {
            registrationService.joinWaitlist(studentId, ex.getSectionId());
            AlertUtil.success("Added to the waiting list",
                    "You are #" + ex.getWaitlistPosition()
                            + " in line. You will be notified if a seat opens.");
            errorLabel.setText("");
            reload();
        } catch (Exception e) {
            AlertUtil.error("Could not join the waiting list", "The waiting list could not be updated.", e);
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
            if (result.getPromotionMessage() != null) {
                message += "\n\nThe next student on the waiting list has been enrolled automatically.";
            }
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

    @FXML
    private void handleLeaveWaitlist() {
        Waitlist selected = waitlistTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        String label = sectionOf(selected)
                .map(s -> courseCodeOf(s.getCourseId()) + "-" + s.getSectionNumber())
                .orElse("this section");

        if (!AlertUtil.confirm("Leave the waiting list",
                "Leave the waiting list for " + label + "?")) {
            return;
        }
        try {
            waitlistService.leaveWaitlist(studentId, selected.getSectionId());
            AlertUtil.success("Removed from the waiting list",
                    "You are no longer on the waitlist for " + label + ".");
            reload();
        } catch (Exception e) {
            AlertUtil.error("Could not leave the waiting list", "The waiting list could not be updated.", e);
        }
    }
}
