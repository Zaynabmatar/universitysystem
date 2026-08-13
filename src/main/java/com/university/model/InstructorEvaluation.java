package com.university.model;

import java.time.LocalDateTime;

/**
 * One submitted instructor evaluation for one enrollment.
 */
public class InstructorEvaluation {

    private int evaluationId;
    private int enrollmentId;
    private LocalDateTime submittedAt;

    public InstructorEvaluation() {
    }

    public InstructorEvaluation(int evaluationId, int enrollmentId, LocalDateTime submittedAt) {
        this.evaluationId = evaluationId;
        this.enrollmentId = enrollmentId;
        this.submittedAt = submittedAt;
    }

    public int getEvaluationId() {
        return evaluationId;
    }

    public void setEvaluationId(int evaluationId) {
        this.evaluationId = evaluationId;
    }

    public int getEnrollmentId() {
        return enrollmentId;
    }

    public void setEnrollmentId(int enrollmentId) {
        this.enrollmentId = enrollmentId;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }
}
