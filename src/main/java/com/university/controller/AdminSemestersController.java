package com.university.controller;

import com.university.controller.dialog.SemesterFormDialog;
import com.university.enums.UserRole;
import com.university.model.Semester;
import com.university.service.EvaluationWindowOpenException;
import com.university.service.SemesterService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * The admin's "Manage Semesters" screen: every date window a semester owns,
 * plus the single-current-semester switch.
 */
public class AdminSemestersController {

    private static final DateTimeFormatter D_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    @FXML private Label currentLabel;
    @FXML private Label countLabel;
    @FXML private TableView<Semester> semesterTable;
    @FXML private TableColumn<Semester, String> colCurrent;
    @FXML private TableColumn<Semester, String> colName;
    @FXML private TableColumn<Semester, String> colYear;
    @FXML private TableColumn<Semester, String> colTerm;
    @FXML private TableColumn<Semester, String> colTermDates;
    @FXML private TableColumn<Semester, String> colRegWindow;
    @FXML private TableColumn<Semester, String> colRegState;
    @FXML private TableColumn<Semester, String> colDropWindow;
    @FXML private TableColumn<Semester, String> colGradeWindow;
    @FXML private TableColumn<Semester, String> colSections;
    @FXML private Button editButton;
    @FXML private Button setCurrentButton;
    @FXML private Button closeCurrentButton;

    private final SemesterService semesterService = new SemesterService();

    private final ObservableList<Semester> rows = FXCollections.observableArrayList();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);

        colCurrent.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isCurrent() ? "★ CURRENT" : ""));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getSemesterName()));
        colYear.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getAcademicYear()));
        colTerm.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTerm().toString()));
        colTermDates.setCellValueFactory(c -> new SimpleStringProperty(termDatesOf(c.getValue())));
        colRegWindow.setCellValueFactory(c -> new SimpleStringProperty(registrationWindowOf(c.getValue())));
        colRegState.setCellValueFactory(c -> new SimpleStringProperty(registrationStateOf(c.getValue())));
        colDropWindow.setCellValueFactory(c -> new SimpleStringProperty(dropWindowOf(c.getValue())));
        colGradeWindow.setCellValueFactory(c -> new SimpleStringProperty(gradeWindowOf(c.getValue())));
        colSections.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(semesterService.countSections(c.getValue().getSemesterId()))));

        colRegState.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("reg-open", "reg-closed", "muted-text");
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                setText(value);
                if ("OPEN".equals(value)) getStyleClass().add("reg-open");
                else if ("Closed".equals(value)) getStyleClass().add("reg-closed");
                else getStyleClass().add("muted-text");
            }
        });

        semesterTable.setItems(rows);
        semesterTable.setPlaceholder(new Label("No semesters yet."));

        var selected = semesterTable.getSelectionModel().selectedItemProperty();
        editButton.disableProperty().bind(selected.isNull());
        // A different semester can never become current while one is already open — it must be
        // closed first (Section 8) — greyed out here, and refused by SemesterService/the database
        // even if this were somehow bypassed.
        selected.addListener((obs, oldSel, newSel) -> setCurrentButton.setDisable(!canBecomeCurrent(newSel)));
        setCurrentButton.setDisable(!canBecomeCurrent(selected.get()));

        reload();
    }

    // ------------------------------------------------------------------ formatting

    private String termDatesOf(Semester s) {
        return D_FMT.format(s.getStartDate()) + " → " + D_FMT.format(s.getEndDate());
    }

    private String registrationWindowOf(Semester s) {
        return DT_FMT.format(s.getRegistrationStart()) + " → " + DT_FMT.format(s.getRegistrationEnd());
    }

    private String registrationStateOf(Semester s) {
        if (s.getRegistrationStart() == null || s.getRegistrationEnd() == null) return "—";
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(s.getRegistrationStart())) return "Opens " + DT_FMT.format(s.getRegistrationStart());
        if (now.isAfter(s.getRegistrationEnd())) return "Closed";
        return "OPEN";
    }

    private String dropWindowOf(Semester s) {
        return "Drop " + D_FMT.format(s.getDropDeadline()) + " · Withdraw " + D_FMT.format(s.getWithdrawDeadline());
    }

    private String gradeWindowOf(Semester s) {
        return D_FMT.format(s.getGradeEntryStart()) + " → " + D_FMT.format(s.getGradeEntryEnd());
    }

    // ------------------------------------------------------------------ data

    private void reload() {
        try {
            rows.setAll(semesterService.listAll());
            countLabel.setText(rows.size() + " semester" + (rows.size() == 1 ? "" : "s"));

            Semester current = semesterService.getCurrentSemester();
            currentLabel.setText(current == null
                    ? "⚠ No current semester is set. Students cannot register until one is chosen."
                    : "Current semester: " + current.getSemesterName() + "  ·  Registration " + registrationStateOf(current));
            closeCurrentButton.setDisable(current == null);
            // Add Semester stays clickable even while a semester is open (Section 8 still refuses
            // the create in SemesterService/the database either way) - handleAdd() explains why
            // instead of silently graying the button out.
            setCurrentButton.setDisable(!canBecomeCurrent(semesterTable.getSelectionModel().getSelectedItem()));
        } catch (Exception e) {
            AlertUtil.error("Could not load semesters", "The semester list could not be loaded.", e);
        }
    }

    @FXML private void handleRefresh() { reload(); }

    /**
     * False when {@code candidate} is null, or a different semester is already open — the two
     * cases {@link SemesterService#setCurrent} always refuses. Re-selecting the semester that is
     * already current stays enabled (it just shows "already current").
     */
    private boolean canBecomeCurrent(Semester candidate) {
        if (candidate == null) return false;
        if (candidate.isCurrent()) return true;
        Semester current = semesterService.getCurrentSemester();
        return current == null;
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void handleAdd() {
        Semester current = semesterService.getCurrentSemester();
        if (current != null) {
            AlertUtil.warn("Cannot add a semester", "You cannot add a new semester while "
                    + current.getSemesterName() + " is still open. Please close the current semester first.");
            return;
        }

        Optional<Semester> result = new SemesterFormDialog(null).showAndWait();
        if (result.isEmpty()) return;

        try {
            semesterService.create(result.get());
            AlertUtil.success("Semester added", "The semester was created.");
            reload();
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot save the semester", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The semester could not be created.", e);
        }
    }

    @FXML
    private void handleEdit() {
        Semester selected = semesterTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<Semester> result = new SemesterFormDialog(selected).showAndWait();
        if (result.isEmpty()) return;

        try {
            semesterService.update(result.get());
            AlertUtil.success("Saved", "The semester's details were updated.");
            reload();
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot save the semester", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The semester could not be updated.", e);
        }
    }

    @FXML
    private void handleSetCurrent() {
        Semester selected = semesterTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (selected.isCurrent()) {
            AlertUtil.info("Already current", selected.getSemesterName() + " is already the current semester.");
            return;
        }
        boolean ok = AlertUtil.confirm("Change the current semester",
                "Make " + selected.getSemesterName() + " the current semester?\n\n"
                + "Exactly one semester may be current. This clears the flag on every other semester in the same "
                + "transaction. Students will now register for sections in " + selected.getSemesterName() + ".");
        if (!ok) return;

        try {
            semesterService.setCurrent(selected.getSemesterId());
            reload();
            AlertUtil.success("Current semester changed", selected.getSemesterName() + " is now the current semester.");
        } catch (EvaluationWindowOpenException ewe) {
            handleEvaluationWindowStillOpen(selected, ewe);
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot change the current semester", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not change the current semester", "The current semester could not be changed.", e);
        }
    }

    /**
     * The "warn, then let the Admin confirm closing it" step: a different semester's evaluation
     * period is still open, so {@link SemesterService#setCurrent(int)} refused to activate
     * {@code selected} silently. Offers to close that window automatically and retry — there must
     * never be an old semester's evaluation period still open once a new semester becomes current.
     */
    private void handleEvaluationWindowStillOpen(Semester selected, EvaluationWindowOpenException ewe) {
        boolean closeAndProceed = AlertUtil.confirm("Evaluation period still open",
                ewe.getMessage() + "\n\nClose it now and make " + selected.getSemesterName()
                + " the current semester?");
        if (!closeAndProceed) return;

        try {
            semesterService.setCurrent(selected.getSemesterId(), true);
            reload();
            AlertUtil.success("Current semester changed", selected.getSemesterName()
                    + " is now the current semester. The previous evaluation period was closed.");
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot change the current semester", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not change the current semester", "The current semester could not be changed.", e);
        }
    }

    @FXML
    private void handleCloseCurrent() {
        Semester current = semesterService.getCurrentSemester();
        if (current == null) {
            AlertUtil.info("No open semester", "There is no open semester to close.");
            return;
        }
        boolean ok = AlertUtil.confirm("Close the current semester",
                "Close " + current.getSemesterName() + "?\n\n"
                + "No semester will be open until another one is explicitly opened. "
                + "Students will not be able to register in the meantime.");
        if (!ok) return;

        try {
            semesterService.closeCurrent();
            reload();
            AlertUtil.success("Semester closed", current.getSemesterName() + " is now closed.");
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot close the semester", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not close the semester", "The semester could not be closed.", e);
        }
    }
}
