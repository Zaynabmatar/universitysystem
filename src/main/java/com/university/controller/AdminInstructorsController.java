package com.university.controller;

import com.university.controller.dialog.InstructorFormDialog;
import com.university.enums.AcademicRank;
import com.university.enums.NotificationType;
import com.university.enums.UserRole;
import com.university.model.Department;
import com.university.model.Instructor;
import com.university.service.CourseService;
import com.university.service.EvaluationService;
import com.university.service.InstructorService;
import com.university.service.AccountService;
import com.university.service.NotificationService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.ValidationUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

import java.util.List;

/** The admin's "Manage Instructors" screen: search/filter table plus add, edit, deactivate, reset-password and notification actions. */
public class AdminInstructorsController {

    @FXML private TextField searchField;
    @FXML private ComboBox<Department> departmentFilter;
    @FXML private ComboBox<AcademicRank> rankFilter;
    @FXML private Label countLabel;
    @FXML private TableView<Instructor> instructorTable;
    @FXML private TableColumn<Instructor, String> colInstructorId;
    @FXML private TableColumn<Instructor, String> colName;
    @FXML private TableColumn<Instructor, String> colEmail;
    @FXML private TableColumn<Instructor, String> colPhone;
    @FXML private TableColumn<Instructor, String> colDepartment;
    @FXML private TableColumn<Instructor, String> colRank;
    @FXML private TableColumn<Instructor, String> colHireDate;
    @FXML private TableColumn<Instructor, String> colActive;
    @FXML private TableColumn<Instructor, String> colEvaluationRate;
    @FXML private Button editButton;
    @FXML private Button deactivateButton;
    @FXML private Button reactivateButton;
    @FXML private Button resetPwdButton;

    // ------------------------------------------------------------ notification section
    @FXML private RadioButton notifyAllRadio;
    @FXML private RadioButton notifyDeptRadio;
    @FXML private RadioButton notifySpecificRadio;
    @FXML private HBox notifyDeptBox;
    @FXML private HBox notifySpecificBox;
    @FXML private ComboBox<Department> notifyDepartmentBox;
    @FXML private TextField notifySearchField;
    @FXML private ComboBox<Instructor> notifyResultsBox;
    @FXML private Label notifyRecipientCountLabel;
    @FXML private TextField notifySubjectField;
    @FXML private TextArea notifyMessageField;

    private final InstructorService instructorService = new InstructorService();
    private final EvaluationService evaluationService = new EvaluationService();
    private final CourseService courseService = new CourseService();
    private final NotificationService notificationService = new NotificationService();

    private final ObservableList<Instructor> rows = FXCollections.observableArrayList();
    private List<Department> departments = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);

        // The Instructor ID is users.user_id — the account's own id.
        colInstructorId.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getUserId())));
        colName      .setCellValueFactory(new PropertyValueFactory<>("fullName"));
        colEmail     .setCellValueFactory(new PropertyValueFactory<>("email"));
        colPhone     .setCellValueFactory(new PropertyValueFactory<>("phone"));
        colDepartment.setCellValueFactory(c -> new SimpleStringProperty(departmentCodeFor(c.getValue().getDepartmentId())));
        colRank      .setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getAcademicRank().toString()));
        colHireDate  .setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getHireDate() == null ? "—" : c.getValue().getHireDate().toString()));
        colActive    .setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActive() ? "Active" : "Deactivated"));

        colEvaluationRate.setCellValueFactory(c -> {
            int instructorId = c.getValue().getInstructorId();

            return new SimpleStringProperty(
                    evaluationService.averageForInstructor(instructorId)
                            .map(avg -> avg.toPlainString() + "/5")
                            .orElse("—")
            );
        });

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
        initNotificationSection();
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
        // orElse(null) plus an explicit check rather than isEmpty()/get(): the
        // null analysis cannot see that the get() is guarded, so it treats the
        // unwrapped value as possibly null. A plain local carries that proof.
        Instructor entered = dialog.showAndWait().orElse(null);
        if (entered == null) return;

        try {
            // Shown only after the transaction has committed.
            AccountService.NewAccount account = instructorService.create(entered);
            AlertUtil.success("Instructor added",
                    "Instructor created successfully.\n\n"
                    + "Instructor ID: " + account.userId() + "\n"
                    + "University Email: " + account.email() + "\n"
                    + "Temporary Password: " + account.temporaryPassword());
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

        Instructor edited = new InstructorFormDialog(selected, departments).showAndWait().orElse(null);
        if (edited == null) return;

        try {
            instructorService.update(edited);
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
                "Deactivate " + selected.getFullName() + " (Instructor ID " + selected.getUserId() + ")?\n\n"
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
                "Reset the password for " + selected.getFullName()
                + " (Instructor ID " + selected.getUserId() + ")?")) {
            return;
        }
        try {
            String newPassword = instructorService.resetPasswordToDefault(selected.getInstructorId());
            AlertUtil.success("Password reset",
                    "Instructor ID: " + selected.getUserId() + "\nNew password: " + newPassword
                    + "\n\nTell the instructor to change it after signing in.");
        } catch (Exception e) {
            AlertUtil.error("Could not reset password", "The password could not be reset.", e);
        }
    }

    // ------------------------------------------------------------------ send notification

    private void initNotificationSection() {
        ToggleGroup recipientGroup = new ToggleGroup();
        notifyAllRadio.setToggleGroup(recipientGroup);
        notifyDeptRadio.setToggleGroup(recipientGroup);
        notifySpecificRadio.setToggleGroup(recipientGroup);
        recipientGroup.selectedToggleProperty().addListener((o, a, b) -> updateNotifySectionVisibility());

        notifyDepartmentBox.getItems().add(null);
        notifyDepartmentBox.getItems().addAll(departments);
        notifyDepartmentBox.setConverter(new StringConverter<>() {
            @Override public String toString(Department d) { return d == null ? "Select department" : d.toString(); }
            @Override public Department fromString(String s) { return null; }
        });
        notifyDepartmentBox.valueProperty().addListener((o, a, b) -> updateRecipientCount());

        notifyResultsBox.setConverter(new StringConverter<>() {
            @Override public String toString(Instructor i) { return i == null ? "" : i.getUserId() + " - " + i.getFullName(); }
            @Override public Instructor fromString(String s) { return null; }
        });
        notifyResultsBox.valueProperty().addListener((o, a, b) -> updateRecipientCount());

        notifySearchField.textProperty().addListener((o, a, b) -> refreshNotifySearchResults());

        updateNotifySectionVisibility();
    }

    private void updateNotifySectionVisibility() {
        boolean deptMode = notifyDeptRadio.isSelected();
        boolean specificMode = notifySpecificRadio.isSelected();
        notifyDeptBox.setVisible(deptMode);
        notifyDeptBox.setManaged(deptMode);
        notifySpecificBox.setVisible(specificMode);
        notifySpecificBox.setManaged(specificMode);
        updateRecipientCount();
    }

    private void refreshNotifySearchResults() {
        notifyResultsBox.setValue(null);
        String term = notifySearchField.getText();
        if (ValidationUtil.isBlank(term)) {
            notifyResultsBox.getItems().clear();
            return;
        }
        List<Instructor> matches = instructorService.search(term.trim()).stream()
                .filter(Instructor::isActive)
                .toList();
        notifyResultsBox.getItems().setAll(matches);
        if (!matches.isEmpty()) {
            notifyResultsBox.show();
        }
    }

    /** Every account this send would reach, active accounts only. */
    private List<Instructor> resolveNotifyRecipients() {
        if (notifySpecificRadio.isSelected()) {
            Instructor selected = notifyResultsBox.getValue();
            return selected == null ? List.of() : List.of(selected);
        }

        List<Instructor> activeInstructors = instructorService.search("").stream()
                .filter(Instructor::isActive)
                .toList();

        if (notifyAllRadio.isSelected()) {
            return activeInstructors;
        }

        // By department.
        Department dept = notifyDepartmentBox.getValue();
        if (dept == null) {
            return List.of();
        }
        return activeInstructors.stream()
                .filter(i -> i.getDepartmentId() == dept.getDepartmentId())
                .toList();
    }

    private void updateRecipientCount() {
        int count = resolveNotifyRecipients().size();
        notifyRecipientCountLabel.setText(count + " instructor" + (count == 1 ? "" : "s") + " will receive this notification.");
    }

    private String describeNotifyRecipients(List<Instructor> recipients) {
        if (notifySpecificRadio.isSelected()) {
            Instructor i = recipients.get(0);
            return i.getFullName() + " (Instructor ID " + i.getUserId() + ")";
        }
        if (notifyAllRadio.isSelected()) {
            return "all " + recipients.size() + " active instructor" + (recipients.size() == 1 ? "" : "s");
        }
        Department dept = notifyDepartmentBox.getValue();
        String scope = dept != null ? dept.toString() : "the selected department";
        return recipients.size() + " instructor" + (recipients.size() == 1 ? "" : "s") + " in " + scope;
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
        if (notifyDeptRadio.isSelected() && notifyDepartmentBox.getValue() == null) {
            AlertUtil.warn("Cannot send notification", "Select a department.");
            return;
        }
        if (notifySpecificRadio.isSelected() && notifyResultsBox.getValue() == null) {
            AlertUtil.warn("Cannot send notification", "Search for and select an instructor.");
            return;
        }

        List<Instructor> recipients = resolveNotifyRecipients();
        if (recipients.isEmpty()) {
            AlertUtil.warn("No recipients", "No active instructors match the selected recipients.");
            return;
        }

        boolean ok = AlertUtil.confirm("Send notification",
                "Send this notification to " + describeNotifyRecipients(recipients) + "?");
        if (!ok) return;

        try {
            List<Integer> userIds = recipients.stream().map(Instructor::getUserId).toList();
            notificationService.notifyAll(userIds, NotificationType.GENERAL, subject, message);
            AlertUtil.success("Notification sent",
                    "Sent to " + recipients.size() + " instructor" + (recipients.size() == 1 ? "" : "s") + ".");
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
}
