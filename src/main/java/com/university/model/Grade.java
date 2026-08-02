package com.university.model;

import com.university.enums.LetterGrade;
import com.university.enums.ResultStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of {@code dbo.grades}, paired one to one with an enrolment.
 *
 * <p>Every mark is nullable because an instructor fills them in gradually.
 * {@code labMark} stays null for a course that has no lab. {@code totalMark}
 * is written explicitly by the application, not computed by the database.</p>
 */
public class Grade {

    private int gradeId;
    private int enrollmentId;
    private BigDecimal partialMark;
    private BigDecimal labMark;
    private BigDecimal finalMark;
    private BigDecimal totalMark;
    private LetterGrade letterGrade;
    private BigDecimal gradePoints;
    private ResultStatus resultStatus;
    private boolean submitted;
    private Integer submittedBy;
    private LocalDateTime submittedAt;
    private Integer lastModifiedBy;
    private LocalDateTime lastModifiedAt;

    public Grade() {
    }

    public Grade(int gradeId, int enrollmentId, BigDecimal partialMark, BigDecimal labMark,
                 BigDecimal finalMark, BigDecimal totalMark, LetterGrade letterGrade,
                 BigDecimal gradePoints, ResultStatus resultStatus, boolean submitted,
                 Integer submittedBy, LocalDateTime submittedAt,
                 Integer lastModifiedBy, LocalDateTime lastModifiedAt) {
        this.gradeId = gradeId;
        this.enrollmentId = enrollmentId;
        this.partialMark = partialMark;
        this.labMark = labMark;
        this.finalMark = finalMark;
        this.totalMark = totalMark;
        this.letterGrade = letterGrade;
        this.gradePoints = gradePoints;
        this.resultStatus = resultStatus;
        this.submitted = submitted;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.lastModifiedBy = lastModifiedBy;
        this.lastModifiedAt = lastModifiedAt;
    }

    public int getGradeId() {
        return gradeId;
    }

    public void setGradeId(int gradeId) {
        this.gradeId = gradeId;
    }

    public int getEnrollmentId() {
        return enrollmentId;
    }

    public void setEnrollmentId(int enrollmentId) {
        this.enrollmentId = enrollmentId;
    }

    public BigDecimal getPartialMark() {
        return partialMark;
    }

    public void setPartialMark(BigDecimal partialMark) {
        this.partialMark = partialMark;
    }

    /** Null when the course has no lab component. */
    public BigDecimal getLabMark() {
        return labMark;
    }

    public void setLabMark(BigDecimal labMark) {
        this.labMark = labMark;
    }

    public BigDecimal getFinalMark() {
        return finalMark;
    }

    public void setFinalMark(BigDecimal finalMark) {
        this.finalMark = finalMark;
    }

    public BigDecimal getTotalMark() {
        return totalMark;
    }

    public void setTotalMark(BigDecimal totalMark) {
        this.totalMark = totalMark;
    }

    public LetterGrade getLetterGrade() {
        return letterGrade;
    }

    public void setLetterGrade(LetterGrade letterGrade) {
        this.letterGrade = letterGrade;
    }

    public BigDecimal getGradePoints() {
        return gradePoints;
    }

    public void setGradePoints(BigDecimal gradePoints) {
        this.gradePoints = gradePoints;
    }

    public ResultStatus getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(ResultStatus resultStatus) {
        this.resultStatus = resultStatus;
    }

    public boolean isSubmitted() {
        return submitted;
    }

    public void setSubmitted(boolean submitted) {
        this.submitted = submitted;
    }

    /** Null until the grade is submitted. */
    public Integer getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(Integer submittedBy) {
        this.submittedBy = submittedBy;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    /** Null until the grade is edited after submission. */
    public Integer getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(Integer lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public LocalDateTime getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(LocalDateTime lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }

    @Override
    public String toString() {
        return "enrolment " + enrollmentId + ": " + totalMark + " " + letterGrade;
    }
}
