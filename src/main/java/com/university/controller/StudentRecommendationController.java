package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.RecommendationService;
import com.university.service.RecommendationService.Reason;
import com.university.service.RecommendationService.Recommendation;
import com.university.service.RecommendationService.RecommendationResult;
import com.university.service.RegistrationException;
import com.university.service.RegistrationService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.GradeCalculator;

import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The AI course-recommendation screen — project_details.md Section 9.
 *
 * <p>Every number on every card carries its own reason line, and the three block titles show the
 * running totals, so the addition is visible: the four degree-fit lines add up to the base score
 * in the block-1 title, and the base plus the bonus equals the score in the header. That is the
 * whole point of the screen — an unexplained number is not a recommendation, it is a guess.</p>
 *
 * <p>The <b>Register</b> button goes through the Phase 09 {@link RegistrationService}, which
 * re-runs all eight registration rules itself. Layer 1 is a filter for the list, never a
 * substitute for the real gate.</p>
 */
public class StudentRecommendationController {

    @FXML private Label subtitleLabel;
    @FXML private Label consideredLabel;
    @FXML private Label survivingLabel;
    @FXML private Label neighbourLabel;
    @FXML private Label scaleLabel;
    @FXML private Label bannerLabel;
    @FXML private VBox  warningsBox;
    @FXML private Label warningsLabel;
    @FXML private VBox  cardsBox;
    @FXML private HBox  statsStrip;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private Button runButton;
    @FXML private Button explainButton;

    private final RecommendationService recommendationService = new RecommendationService();
    private final RegistrationService registrationService = new RegistrationService();

    private RecommendationResult result;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        scaleLabel.setText(String.valueOf(RecommendationService.MAX_FINAL_SCORE));   // 115
        run();
    }

    @FXML
    private void handleRun() {
        run();
    }

    private void run() {
        setBusy(true);
        try {
            result = recommendationService.recommend(Session.current().requireStudentId());
            render();
        } catch (RuntimeException e) {
            AlertUtil.error("Recommendations", "Your recommendations could not be built.", e);
        } finally {
            setBusy(false);
        }
    }

    private void setBusy(boolean busy) {
        busyIndicator.setVisible(busy);
        busyIndicator.setManaged(busy);
        runButton.setDisable(busy);
    }

    private void render() {
        cardsBox.getChildren().clear();
        showBanner(null);
        showWarnings(List.of());

        if (result.isBlocked()) {
            showBanner(result.blockedReason);
            statsStrip.setVisible(false);
            statsStrip.setManaged(false);
            return;
        }
        statsStrip.setVisible(true);
        statsStrip.setManaged(true);

        consideredLabel.setText(String.valueOf(result.sectionsConsidered));
        survivingLabel.setText(String.valueOf(result.sectionsSurviving));
        neighbourLabel.setText(String.valueOf(result.neighbourCount));
        subtitleLabel.setText("Semester " + result.currentSemester + " of your "
                + result.programName
                + "  ·  GPA " + GradeCalculator.formatGpa(result.cumulativeGpa)
                + "  ·  " + result.currentCredits + " of " + result.creditLimit
                + " credits taken this semester");

        showWarnings(result.warnings);

        if (result.recommendations.isEmpty()) {
            showBanner("No course passed all of the eligibility rules right now. That usually "
                    + "means your credit limit is reached, every suitable section is full, or "
                    + "your timetable has no free slot.");
            return;
        }
        for (Recommendation recommendation : result.recommendations) {
            cardsBox.getChildren().add(buildCard(recommendation));
        }
    }

    /** One line per Study Plan course that is due but could not be recommended right now. */
    private void showWarnings(List<String> warnings) {
        boolean show = warnings != null && !warnings.isEmpty();
        warningsBox.setVisible(show);
        warningsBox.setManaged(show);
        warningsLabel.setText(show ? String.join("\n", warnings) : "");
    }

    private void showBanner(String text) {
        boolean show = text != null;
        bannerLabel.setText(show ? text : "");
        bannerLabel.setVisible(show);
        bannerLabel.setManaged(show);
    }

    /* ================= ONE CARD ================= */

    private VBox buildCard(Recommendation r) {
        Label rank = new Label("#" + r.rank);
        rank.getStyleClass().add("reco-rank");

        Label title = new Label(r.headerTitle());
        title.getStyleClass().add("reco-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label score = new Label(r.scoreText());          // "Score: 104 / 115"
        score.getStyleClass().add("reco-score");

        HBox header = new HBox(10, rank, title, spacer, score);
        header.setAlignment(Pos.CENTER_LEFT);

        Label sub = new Label(r.subHeader());
        sub.getStyleClass().add("reco-sub");
        sub.setWrapText(true);

        /* the three labelled blocks, exactly as Section 9 shows them */
        VBox degreeFit   = buildBlock(r.degreeFitTitle(),   r.degreeFitReasons);
        VBox peers       = buildBlock(r.peerTitle(),        r.peerReasons);
        VBox eligibility = buildBlock(r.eligibilityTitle(), r.eligibilityReasons);

        Button register = new Button("Register");
        register.setOnAction(event -> handleRegister(r));

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox actions = new HBox(10, gap, register);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(8, header, sub, degreeFit, peers, eligibility, actions);
        card.getStyleClass().add("reco-card");
        card.setPadding(new Insets(14));
        return card;
    }

    private VBox buildBlock(String title, List<Reason> reasons) {
        Label heading = new Label(title);
        heading.getStyleClass().add("reco-block-title");

        VBox box = new VBox(3, heading);
        for (Reason reason : reasons) {
            box.getChildren().add(buildReasonLine(reason));
        }
        box.getStyleClass().add("reco-block");
        return box;
    }

    private HBox buildReasonLine(Reason reason) {
        Label icon = new Label(reason.icon());
        icon.getStyleClass().add(reason.positive ? "reason-yes" : "reason-zero");

        Label text = new Label(reason.text);
        text.getStyleClass().add("reason-text");
        text.setWrapText(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox line = new HBox(8, icon, text, spacer);
        line.setAlignment(Pos.CENTER_LEFT);

        if (reason.points != null) {
            Label points = new Label(reason.pointsText());     // "+40"
            points.getStyleClass().add("reason-points");
            line.getChildren().add(points);
        }
        if (reason.note != null) {
            Label note = new Label(reason.note);               // "(avg B+)"
            note.getStyleClass().add("reason-note");
            line.getChildren().add(note);
        }
        if (reason.tooltip != null) {
            Tooltip.install(line, new Tooltip(reason.tooltip));
        }
        return line;
    }

    /* ================= ACTIONS ================= */

    /**
     * Registers through Phase 09. That service re-runs all eight rules itself, so a card that
     * slipped past Layer 1 still cannot produce an illegal registration.
     */
    private void handleRegister(Recommendation r) {
        if (!AlertUtil.confirm("Register", "Register for " + r.courseCode + " — " + r.courseTitle
                + " (section " + r.sectionNumber + ")?")) {
            return;
        }
        try {
            registrationService.registerStudent(Session.current().requireStudentId(), r.sectionId);
            AlertUtil.success("Registered", "You are now registered for " + r.courseCode + ".");
            run();      // the list must refresh: R4 and R7 have both changed
        } catch (RegistrationException e) {
            AlertUtil.warn("Registration refused", e.getMessage());
        } catch (RuntimeException e) {
            AlertUtil.error("Registration refused", "That registration could not be completed.", e);
        }
    }

    /** The model, not the result — and every count in it comes from the run that just happened. */
    @FXML
    private void handleExplain() {
        if (result == null) {
            return;
        }
        String similarity = result.neighbourCount == 0
                ? "none found yet — you need at least one passed course in common with somebody"
                : String.format("%.2f to %.2f", result.highestSimilarity, result.lowestSimilarity);

        String text =
              "LAYER 1 — Eligibility (a rule-based expert system)\n"
            + "  Every open section this semester is tested against registration rules R3–R8:\n"
            + "  prerequisites, not already passed, credit-based eligibility thresholds, a free\n"
            + "  seat, no timetable clash, and your credit limit. Anything that fails any rule is\n"
            + "  removed — and if it was due in your Study Plan, a Warning explains why.\n"
            + "  " + result.sectionsConsidered + " sections were considered; "
                   + result.sectionsSurviving + " survived.\n\n"
            + "LAYER 2 — Degree fit (content-based scoring, 100 points)\n"
            + "  Mandatory for your degree                up to 40\n"
            + "  How far behind schedule it is            up to 25\n"
            + "  How many later courses it unlocks        up to 20\n"
            + "  How well its difficulty suits your GPA   up to 15\n\n"
            + "LAYER 3 — Students like you (item-based k-nearest-neighbours, up to +15)\n"
            + "  Your passed courses form a set. Every other student's passed courses form a set.\n"
            + "  The Jaccard index |A ∩ B| / |A ∪ B| measures how much they overlap.\n"
            + "  The " + RecommendationService.K_NEIGHBOURS + " most similar students are your "
            + "neighbours (" + result.neighbourCount + " found, similarity " + similarity + ").\n\n"
            + "TOTAL = base (out of 100) + bonus (out of 15) = out of 115.\n\n"
            + "Everything runs offline against your own database. No external service is called,\n"
            + "and no random numbers are used: the same data always produces the same list.";

        AlertUtil.info("How these recommendations are built", text);
    }
}
