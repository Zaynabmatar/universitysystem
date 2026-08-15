package com.university.model;

import com.university.enums.LetterGrade;
import com.university.enums.ResultStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of {@code dbo.grades}, paired one to one with an enrolment.
 *
 * <p>Every mark is nullable because an instructor fills them in gradually — a
 * missing mark means "not marked yet", not "scored nothing" (project_details.md
 * Section 5.1). {@code totalMark} is written explicitly by the application,
 * not computed by the database.</p>
 */
public class Grade {

    private int gradeId;
    private int enrollmentId;
    private BigDecimal courseworkMark;
    private BigDecimal midtermMark;
    private BigDecimal labMark;
    private BigDecimal finalMark;
    private BigDecimal totalMark;
    private LetterGrade letterGrade;
    private BigDecimal gradePoints;
    private ResultStatus resultStatus;
    private boolean submitted;
    private boolean courseworkPublished;
    private boolean midtermPublished;
    private boolean labPublished;
    private boolean finalPublished;
    private Integer submittedBy;
    private LocalDateTime submittedAt;
    private Integer lastModifiedBy;
    private LocalDateTime lastModifiedAt;

    public Grade() {
    }

    public Grade(int gradeId, int enrollmentId, BigDecimal courseworkMark, BigDecimal midtermMark,
                 BigDecimal finalMark, BigDecimal totalMark, LetterGrade letterGrade,
                 BigDecimal gradePoints, ResultStatus resultStatus, boolean submitted,
                 Integer submittedBy, LocalDateTime submittedAt,
                 Integer lastModifiedBy, LocalDateTime lastModifiedAt) {
        this.gradeId = gradeId;
        this.enrollmentId = enrollmentId;
        this.courseworkMark = courseworkMark;
        this.midtermMark = midtermMark;
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

    /** Weight 30% (Section 5.1). */
    public BigDecimal getCourseworkMark() {
        return courseworkMark;
    }

    public void setCourseworkMark(BigDecimal courseworkMark) {
        this.courseworkMark = courseworkMark;
    }

    /** Weight 30% (Section 5.1). */
    public BigDecimal getMidtermMark() {
        return midtermMark;
    }

    public void setMidtermMark(BigDecimal midtermMark) {
        this.midtermMark = midtermMark;
    }

    /** Only set for courses with a lab component ({@code courses.has_lab}); null otherwise. */
    public BigDecimal getLabMark() {
        return labMark;
    }

    public void setLabMark(BigDecimal labMark) {
        this.labMark = labMark;
    }

    /** Weight 40% (Section 5.1). */
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

    /** True once the Coursework/Lab-replacement mark has been released to the student on its own,
     *  ahead of the whole row being submitted. */
    public boolean isCourseworkPublished() {
        return courseworkPublished;
    }

    public void setCourseworkPublished(boolean courseworkPublished) {
        this.courseworkPublished = courseworkPublished;
    }

    public boolean isMidtermPublished() {
        return midtermPublished;
    }

    public void setMidtermPublished(boolean midtermPublished) {
        this.midtermPublished = midtermPublished;
    }

    public boolean isLabPublished() {
        return labPublished;
    }

    public void setLabPublished(boolean labPublished) {
        this.labPublished = labPublished;
    }

    public boolean isFinalPublished() {
        return finalPublished;
    }

    public void setFinalPublished(boolean finalPublished) {
        this.finalPublished = finalPublished;
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
