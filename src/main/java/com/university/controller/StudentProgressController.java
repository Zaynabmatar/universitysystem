package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.Session;
import com.university.service.TranscriptService;
import com.university.service.TranscriptService.DegreeProgress;
import com.university.service.TranscriptService.RequirementRow;
import com.university.service.TranscriptService.SemesterPlan;
import com.university.util.AlertUtil;
import com.university.util.GradeCalculator;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.util.Duration;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * "Can I graduate?", answered condition by condition — and, underneath, the complete
 * semester-by-semester Study Plan for the logged-in student's exact program.
 *
 * <p>project_details.md Section 6.9 has three conditions, and this screen shows all three
 * separately, each with its own ✓ or ✗ and its own numbers — never just the overall verdict.
 * A student who is refused must be able to read <em>which</em> condition failed.</p>
 *
 * <p>The Study Plan section below it is built entirely from
 * {@link TranscriptService.DegreeProgress#semesterPlans} — one {@link TableView} per semester,
 * created here rather than declared in FXML because the number of semesters is a property of the
 * student's own program (4 for the Master's, 8 for most Bachelor's, 9 for Law and Pharmacy), not
 * a fixed screen layout.</p>
 *
 * <p>Read-only: nothing here writes to the database, and {@code students.status} is never set
 * to {@code GRADUATED} — conferring a degree is a registrar action, not a screen.</p>
 */
public class StudentProgressController {

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
    @FXML private VBox        studyPlanContainer;

    private final TranscriptService transcriptService = new TranscriptService();

    private DegreeProgress progress;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
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
            renderStudyPlan();
        } catch (RuntimeException e) {
            AlertUtil.error("Degree progress", "Your degree progress could not be loaded.", e);
        }
    }

    private void renderSummary() {
        programLabel.setText(progress.fullName + "  •  " + progress.studentUserId
                           + "  •  " + progress.programName);
        double targetProgress = progress.progressFraction();
        progressBar.setProgress(0.0);

        if (!percentLabel.textProperty().isBound()) {
            percentLabel.textProperty().bind(
                    Bindings.format("%.1f%%", progressBar.progressProperty().multiply(100))
            );
        }

        Timeline progressAnimation = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(progressBar.progressProperty(), 0.0)),
                new KeyFrame(Duration.seconds(3),
                        new KeyValue(progressBar.progressProperty(), targetProgress))
        );
        progressAnimation.play();
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

    /**
     * One {@link TableView} per {@link SemesterPlan}, in order, each with its own "Semester N"
     * heading — the whole of the student's own program curriculum, nothing hard-coded.
     */
    private void renderStudyPlan() {
        studyPlanContainer.getChildren().clear();

        if (progress.semesterPlans.isEmpty()) {
            studyPlanContainer.getChildren().add(new Label("This program has no Study Plan yet."));
            return;
        }

        for (SemesterPlan plan : progress.semesterPlans) {
            VBox block = new VBox(6);
            Label heading = new Label(plan.heading());
            heading.getStyleClass().add("section-title");
            block.getChildren().addAll(heading, buildSemesterTable(plan));
            studyPlanContainer.getChildren().add(block);
        }
    }

    private TableView<RequirementRow> buildSemesterTable(SemesterPlan plan) {
        TableView<RequirementRow> table = new TableView<>();
        table.setItems(javafx.collections.FXCollections.observableArrayList(plan.courses));

        TableColumn<RequirementRow, String> colCode = new TableColumn<>("Course Code");
        colCode.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().courseCode));
        colCode.setPrefWidth(100);

        TableColumn<RequirementRow, String> colTitle = new TableColumn<>("Course Title");
        colTitle.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().courseTitle));
        colTitle.setPrefWidth(240);

        TableColumn<RequirementRow, String> colCredits = new TableColumn<>("Credits");
        colCredits.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().credits)));
        colCredits.setPrefWidth(65);

        TableColumn<RequirementRow, String> colType = new TableColumn<>("Requirement Type");
        colType.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().typeText()));
        colType.setPrefWidth(150);

        TableColumn<RequirementRow, String> colPrereq = new TableColumn<>("Prerequisites");
        colPrereq.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().prerequisitesText));
        colPrereq.setPrefWidth(260);

        TableColumn<RequirementRow, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().statusText()));
        colStatus.setPrefWidth(140);
        colStatus.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                getStyleClass().removeAll("status-passed", "status-inprogress", "status-failed",
                        "status-eligible", "status-locked", "status-notyetavailable");
                if (empty || status == null) {
                    setText(null);
                    return;
                }
                setText(status);
                RequirementRow row = getTableRow() == null ? null : (RequirementRow) getTableRow().getItem();
                String key = row == null || row.courseStatus == null ? "" : row.courseStatus;
                getStyleClass().add(switch (key) {
                    case "PASSED" -> "status-passed";
                    case "IN_PROGRESS" -> "status-inprogress";
                    case "FAILED" -> "status-failed";
                    case "ELIGIBLE" -> "status-eligible";
                    case "LOCKED" -> "status-locked";
                    case "NOT_YET_AVAILABLE" -> "status-notyetavailable";
                    default -> "status-nottaken";
                });
            }
        });

        // List.of(...) + the Collection overload of setAll, rather than the varargs one: passing
        // six TableColumn<RequirementRow, String> values as a T... varargs forces the compiler to
        // create a generic array of TableColumn<RequirementRow, ?> to hold them, which is what
        // produced the unchecked-varargs warning. A List sidesteps that array entirely.
        table.getColumns().setAll(List.of(colCode, colTitle, colCredits, colType, colPrereq, colStatus));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Sized to show every row of this one semester without its own inner scrollbar — the
        // page-level ScrollPane in the FXML is what scrolls the whole screen if the full
        // Study Plan runs taller than the window.
        double rowHeight = 28;
        double headerHeight = 32;
        table.setFixedCellSize(rowHeight);
        table.prefHeightProperty().bind(table.fixedCellSizeProperty()
                .multiply(Math.max(1, plan.courses.size())).add(headerHeight + 2));
        table.minHeightProperty().bind(table.prefHeightProperty());
        table.maxHeightProperty().bind(table.prefHeightProperty());

        return table;
    }
}


