package com.university.model;

/**
 * One answer in an instructor evaluation.
 */
public class EvaluationAnswer {

    private int questionId;
    private int rating;

    public EvaluationAnswer() {
    }

    public EvaluationAnswer(int questionId, int rating) {
        this.questionId = questionId;
        this.rating = rating;
    }

    public int getQuestionId() {
        return questionId;
    }

    public void setQuestionId(int questionId) {
        this.questionId = questionId;
    }

    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = rating;
    }
}
