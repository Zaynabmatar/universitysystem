package com.university.controller.dialog;

import com.university.enums.UserRole;
import com.university.model.EvaluationAnswer;
import com.university.model.EvaluationQuestion;
import com.university.service.Session;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Student instructor-evaluation dialog.
 *
 * <p>The form intentionally uses no ScrollPane. The 15 questions are split
 * across two compact columns so the whole form remains visible at once.</p>
 */
public final class InstructorEvaluationDialog extends Dialog<List<EvaluationAnswer>> {

    private final Map<Integer, ToggleGroup> ratingGroups = new LinkedHashMap<>();
    private final Label errorLabel = new Label();

    public InstructorEvaluationDialog(String courseCode,
                                      String instructorName,
                                      List<EvaluationQuestion> questions) {
        Session.current().requireRole(UserRole.STUDENT);

        setTitle("Instructor Evaluation");
        setHeaderText(null);
        setResizable(true);

        ButtonType submitType = new ButtonType("Submit Evaluation", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(submitType, ButtonType.CANCEL);

        var css = getClass().getResource("/css/app.css");
        if (css != null) {
            getDialogPane().getStylesheets().add(css.toExternalForm());
        }

        Label title = new Label("Instructor Evaluation");
        title.setStyle(
                "-fx-font-size: 22px;"
                + "-fx-font-weight: bold;"
                + "-fx-text-fill: #1E1B4B;"
        );

        Label course = new Label(
                (courseCode == null || courseCode.isBlank() ? "Course" : courseCode)
                + "  |  "
                + (instructorName == null || instructorName.isBlank() ? "Instructor" : instructorName)
        );
        course.setStyle("-fx-font-size: 13px; -fx-text-fill: #6B7280;");

        Label instruction = new Label("Rate each statement from 1 to 5.");
        instruction.setStyle("-fx-font-size: 12px; -fx-text-fill: #6B7280;");

        VBox heading = new VBox(4, title, course, instruction);

        GridPane questionsGrid = new GridPane();
        questionsGrid.setHgap(22);
        questionsGrid.setVgap(10);

        int split = (questions.size() + 1) / 2;

        for (int i = 0; i < questions.size(); i++) {
            EvaluationQuestion question = questions.get(i);

            int column = i < split ? 0 : 1;
            int row = i < split ? i : i - split;

            VBox questionBox = buildQuestionBox(question);
            questionsGrid.add(questionBox, column, row);
        }

        errorLabel.getStyleClass().add("error-text");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);

        VBox content = new VBox(14, heading, questionsGrid, errorLabel);
        content.setPadding(new Insets(18));
        content.setMinHeight(Region.USE_PREF_SIZE);
        content.setPrefWidth(1080);

        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(1120);

        Button submitButton = (Button) getDialogPane().lookupButton(submitType);

        submitButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!allQuestionsAnswered()) {
                errorLabel.setText("Please answer all 15 evaluation questions.");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                event.consume();
            }
        });

        setResultConverter(button -> {
            if (button != submitType) {
                return null;
            }
            return buildAnswers();
        });
    }

    private VBox buildQuestionBox(EvaluationQuestion question) {
        Label questionLabel = new Label(
                question.getDisplayOrder() + ". " + question.getQuestionText()
        );
        questionLabel.setWrapText(true);
        questionLabel.setMaxWidth(500);
        questionLabel.setStyle(
                "-fx-font-size: 12px;"
                + "-fx-font-weight: 600;"
                + "-fx-text-fill: #1F2937;"
        );

        ToggleGroup group = new ToggleGroup();
        ratingGroups.put(question.getQuestionId(), group);

        HBox choices = new HBox(10);
        choices.setAlignment(Pos.CENTER_LEFT);

        for (int rating = 1; rating <= 5; rating++) {
            RadioButton option = new RadioButton(String.valueOf(rating));
            option.setToggleGroup(group);
            option.setUserData(rating);
            choices.getChildren().add(option);
        }

        VBox box = new VBox(5, questionLabel, choices);
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setMinWidth(500);
        box.setPrefWidth(500);
        box.setStyle(
                "-fx-background-color: white;"
                + "-fx-background-radius: 8;"
                + "-fx-border-color: #E5E7EB;"
                + "-fx-border-radius: 8;"
        );

        return box;
    }

    private boolean allQuestionsAnswered() {
        for (ToggleGroup group : ratingGroups.values()) {
            if (group.getSelectedToggle() == null) {
                return false;
            }
        }
        return true;
    }

    private List<EvaluationAnswer> buildAnswers() {
        List<EvaluationAnswer> answers = new ArrayList<>();

        for (Map.Entry<Integer, ToggleGroup> entry : ratingGroups.entrySet()) {
            Toggle selected = entry.getValue().getSelectedToggle();
            int rating = (int) selected.getUserData();
            answers.add(new EvaluationAnswer(entry.getKey(), rating));
        }

        return answers;
    }
}