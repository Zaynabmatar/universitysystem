package com.university.model;

import com.university.enums.LetterGrade;
import com.university.util.GradeCalculator;

import java.math.BigDecimal;

/**
 * One line of an instructor's grade sheet: one enrolled student, joined with whatever grade row
 * already exists for them (none, the common case before the first Save Draft).
 *
 * <p>Not a table of its own — a read/edit projection over {@code enrollments}, {@code students}
 * and {@code grades} together. {@link #recompute()} is what keeps total/letter/points in step
 * with the three marks on every keystroke, before anything is saved (project_details.md Section
 * 6.6's live-computation requirement).</p>
 */
public class GradeSheetRow {

    private int enrollmentId;
    private Integer gradeId;
    private int studentId;
    /** users.user_id — the Student ID shown on the sheet, not the internal students.student_id. */
    private int studentUserId;
    private String studentName;
    private BigDecimal courseworkMark;
    private BigDecimal midtermMark;
    private BigDecimal labMark;
    private BigDecimal finalMark;
    private BigDecimal totalMark;
    private LetterGrade letterGrade;
    private BigDecimal gradePoints;
    private boolean submitted;
    /** True when this row's course has a lab component ({@code courses.has_lab}). */
    private boolean hasLab;

    public int getEnrollmentId() {
        return enrollmentId;
    }

    public void setEnrollmentId(int enrollmentId) {
        this.enrollmentId = enrollmentId;
    }

    /** Null until the first Save Draft creates the row. */
    public Integer getGradeId() {
        return gradeId;
    }

    public void setGradeId(Integer gradeId) {
        this.gradeId = gradeId;
    }

    public int getStudentId() {
        return studentId;
    }

    public void setStudentId(int studentId) {
        this.studentId = studentId;
    }

    /** The Student ID the sheet shows: users.user_id. */
    public int getStudentUserId() {
        return studentUserId;
    }

    public void setStudentUserId(int studentUserId) {
        this.studentUserId = studentUserId;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

    public BigDecimal getCourseworkMark() {
        return courseworkMark;
    }

    public void setCourseworkMark(BigDecimal courseworkMark) {
        this.courseworkMark = courseworkMark;
    }

    public BigDecimal getMidtermMark() {
        return midtermMark;
    }

    public void setMidtermMark(BigDecimal midtermMark) {
        this.midtermMark = midtermMark;
    }

    /** Only meaningful when {@link #isHasLab()} is true; null for a course with no lab. */
    public BigDecimal getLabMark() {
        return labMark;
    }

    public void setLabMark(BigDecimal labMark) {
        this.labMark = labMark;
    }

    public boolean isHasLab() {
        return hasLab;
    }

    public void setHasLab(boolean hasLab) {
        this.hasLab = hasLab;
    }

    public BigDecimal getFinalMark() {
        return finalMark;
    }

    public void setFinalMark(BigDecimal finalMark) {
        this.finalMark = finalMark;
    }

    /** Computed by {@link #recompute()}; null while any of the three marks is missing. */
    public BigDecimal getTotalMark() {
        return totalMark;
    }

    /** Computed by {@link #recompute()}. */
    public LetterGrade getLetterGrade() {
        return letterGrade;
    }

    /** Computed by {@link #recompute()}. */
    public BigDecimal getGradePoints() {
        return gradePoints;
    }

    public boolean isSubmitted() {
        return submitted;
    }

    public void setSubmitted(boolean submitted) {
        this.submitted = submitted;
    }

    /** Recomputes total/letter/points from the three marks — Section 5.1 and 5.2. */
    public void recompute() {
        this.totalMark = GradeCalculator.totalMark(courseworkMark, midtermMark, labMark, finalMark, hasLab);
        this.letterGrade = GradeCalculator.letterGrade(totalMark);
        this.gradePoints = letterGrade == null ? null : letterGrade.getGradePoints();
    }

    @Override
    public String toString() {
        return studentUserId + " " + studentName;
    }
}
