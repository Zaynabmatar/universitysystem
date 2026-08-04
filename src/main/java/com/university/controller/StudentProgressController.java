package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.Session;
import com.university.service.TranscriptService;
import com.university.service.TranscriptService.DegreeProgress;
import com.university.service.TranscriptService.RequirementRow;
import com.university.util.AlertUtil;
import com.university.util.GradeCalculator;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.util.List;
import java.util.function.Predicate;

/**
 * "Can I graduate?", answered condition by condition.
 *
 * <p>project_details.md Section 6.9 has three conditions, and this screen shows all three
 * separately, each with its own ✓ or ✗ and its own numbers — never just the overall verdict.
 * A student who is refused must be able to read <em>which</em> condition failed.</p>
 *
 * <p>Read-only: nothing here writes to the database, and {@code students.status} is never set
 * to {@code GRADUATED} — conferring a degree is a registrar action, not a screen.</p>
 */
public class StudentProgressController {

    private static final String ALL_COURSES   = "All courses";
    private static final String MANDATORY     = "Mandatory only";
    private static final String NOT_TAKEN     = "Not taken";
    private static final String IN_PROGRESS   = "In progress";
    private static final String PASSED        = "Passed";

    @FXML private Label       programLabel;
    @FXML private Label       percentLabel;
    @FXML private Label       creditsSummaryLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label       completedLabel;
    @FXML private Label       remainingLabel;
    @FXML private Label       mandatoryLabel;
    @FXML private Label       condition1Label;
    @FXML private Label       condition2Label;
    @FXML private Label       condition3Label;
    @FXML private Label       verdictLabel;
    @FXML private ComboBox<String> filterCombo;
    @FXML private Label       planCountLabel;
    @FXML private TableView<RequirementRow> requirementsTable;
    @FXML private TableColumn<RequirementRow, String> colCode;
    @FXML private TableColumn<RequirementRow, String> colTitle;
    @FXML private TableColumn<RequirementRow, String> colCredits;
    @FXML private TableColumn<RequirementRow, String> colType;
    @FXML private TableColumn<RequirementRow, String> colSemester;
    @FXML private TableColumn<RequirementRow, String> colStatus;

    private final TranscriptService transcriptService = new TranscriptService();
    private final ObservableList<RequirementRow> shown = FXCollections.observableArrayList();

    private DegreeProgress progress;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);

        configureColumns();
        requirementsTable.setItems(shown);
        requirementsTable.setPlaceholder(new Label("This program has no degree plan yet."));

        filterCombo.getItems().addAll(ALL_COURSES, MANDATORY, NOT_TAKEN, IN_PROGRESS, PASSED);
        filterCombo.getSelectionModel().selectFirst();
        filterCombo.valueProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        load();
    }

    @FXML
    private void handleRefresh() {
        load();
    }

    private void load() {
        try {
            progress = transcriptService.getDegreeProgress(Session.current().requireStudentId());
            renderSummary();
            renderEligibility();
            applyFilter();
        } catch (RuntimeException e) {
            AlertUtil.error("Degree progress", "Your degree progress could not be loaded.", e);
        }
    }

    private void configureColumns() {
        colCode.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().courseCode));
        colTitle.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().courseTitle));
        colCredits.setCellValueFactory(data ->
                new SimpleStringProperty(String.valueOf(data.getValue().credits)));
        colType.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().typeText()));
        colSemester.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().semesterText()));
        colStatus.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().courseStatus));

        // Colour-coded through style classes, never an inline -fx- string.
        colStatus.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                getStyleClass().removeAll("status-passed", "status-inprogress", "status-nottaken");
                if (empty || status == null) {
                    setText(null);
                    return;
                }
                setText(status);
                switch (status) {
                    case "PASSED"      -> getStyleClass().add("status-passed");
                    case "IN PROGRESS" -> getStyleClass().add("status-inprogress");
                    default            -> getStyleClass().add("status-nottaken");
                }
            }
        });
    }

    private void renderSummary() {
        programLabel.setText(progress.fullName + "  •  " + progress.studentNumber
                           + "  •  " + progress.programName);
        percentLabel.setText(progress.percentText());
        progressBar.setProgress(progress.progressFraction());
        creditsSummaryLabel.setText(progress.creditsCompleted + " of "
                                  + progress.creditsRequired + " credits");
        completedLabel.setText("Completed: " + progress.creditsCompleted + " credits");
        remainingLabel.setText("Remaining: " + progress.creditsRemaining + " credits");
        mandatoryLabel.setText("Mandatory courses: " + progress.mandatoryPassed
                             + " / " + progress.mandatoryTotal + " passed");
    }

    /** Section 6.9, one line per condition, each carrying the numbers it was judged on. */
    private void renderEligibility() {
        boolean mandatoryOk = progress.allMandatoryPassed();
        boolean creditsOk   = progress.creditsSatisfied();
        boolean gpaOk       = progress.gpaSatisfied();

        condition1Label.setText(tick(mandatoryOk) + (mandatoryOk
                ? " All mandatory courses passed (" + progress.mandatoryPassed
                  + " / " + progress.mandatoryTotal + ")"
                : " " + progress.mandatoryMissing + " mandatory course(s) still missing ("
                  + progress.mandatoryPassed + " / " + progress.mandatoryTotal + " passed)"));

        condition2Label.setText(tick(creditsOk) + (creditsOk
                ? " Credits: " + progress.creditsCompleted + " / " + progress.creditsRequired
                : " Credits: " + progress.creditsCompleted + " / " + progress.creditsRequired
                  + " — " + progress.creditsRemaining + " remaining"));

        String gpa = GradeCalculator.formatGpa(progress.cumulativeGpa);
        condition3Label.setText(tick(gpaOk) + (gpaOk
                ? " Cumulative GPA " + gpa + " ≥ 2.00"
                : " Cumulative GPA " + gpa + " is below the required 2.00"));

        styleCondition(condition1Label, mandatoryOk);
        styleCondition(condition2Label, creditsOk);
        styleCondition(condition3Label, gpaOk);

        boolean eligible = progress.canGraduate;
        verdictLabel.setText(eligible
                ? "Eligible to graduate"
                : "Not yet eligible — " + progress.outstandingConditions()
                  + " requirement(s) outstanding");
        verdictLabel.getStyleClass().removeAll("verdict-ok", "verdict-warn");
        verdictLabel.getStyleClass().add(eligible ? "verdict-ok" : "verdict-warn");
    }

    private String tick(boolean ok) {
        return ok ? "✓" : "✗";
    }

    private void styleCondition(Label label, boolean ok) {
        label.getStyleClass().removeAll("condition-ok", "condition-fail");
        label.getStyleClass().add(ok ? "condition-ok" : "condition-fail");
    }

    private void applyFilter() {
        if (progress == null) {
            return;
        }
        List<RequirementRow> all = progress.requirements;
        shown.setAll(all.stream().filter(predicateFor(filterCombo.getValue())).toList());
        planCountLabel.setText(shown.size() + " of " + all.size() + " courses");
    }

    private Predicate<RequirementRow> predicateFor(String filter) {
        if (filter == null) {
            return row -> true;
        }
        return switch (filter) {
            case MANDATORY   -> row -> row.isMandatory;
            case NOT_TAKEN   -> RequirementRow::isNotTaken;
            case IN_PROGRESS -> RequirementRow::isInProgress;
            case PASSED      -> RequirementRow::isPassed;
            default          -> row -> true;
        };
    }
}
