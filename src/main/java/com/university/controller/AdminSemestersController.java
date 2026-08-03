package com.university.controller;

import com.university.controller.dialog.SemesterFormDialog;
import com.university.enums.UserRole;
import com.university.model.Semester;
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
        setCurrentButton.disableProperty().bind(selected.isNull());

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
        } catch (Exception e) {
            AlertUtil.error("Could not load semesters", "The semester list could not be loaded.", e);
        }
    }

    @FXML private void handleRefresh() { reload(); }

    // ------------------------------------------------------------------ actions

    @FXML
    private void handleAdd() {
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
        } catch (Exception e) {
            AlertUtil.error("Could not change the current semester", "The current semester could not be changed.", e);
        }
    }
}
