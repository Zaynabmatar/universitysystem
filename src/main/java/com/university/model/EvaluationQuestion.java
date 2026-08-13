package com.university.model;

/**
 * One instructor-evaluation question shown to students.
 */
public class EvaluationQuestion {

    private int questionId;
    private String questionText;
    private int displayOrder;
    private boolean active;

    public EvaluationQuestion() {
    }

    public EvaluationQuestion(int questionId, String questionText, int displayOrder, boolean active) {
        this.questionId = questionId;
        this.questionText = questionText;
        this.displayOrder = displayOrder;
        this.active = active;
    }

    public int getQuestionId() {
        return questionId;
    }

    public void setQuestionId(int questionId) {
        this.questionId = questionId;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return displayOrder + ". " + questionText;
    }
}
