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
    /** True once this component has been released to the student on its own, ahead of the whole
     *  row being submitted -- see {@link com.university.service.GradeService#publishComponents}. */
    private boolean courseworkPublished;
    private boolean midtermPublished;
    private boolean labPublished;
    private boolean finalPublished;
    /** True when this row's course has a lab component ({@code courses.has_lab}). */
    private boolean hasLab;
    /** True once a submitted row has been edited in memory and needs to be re-saved. */
    private boolean editedAfterSubmit;
    /** The course's own weights ({@code dbo.courses}), used to compute {@link #totalMark}. */
    private BigDecimal courseworkWeight;
    private BigDecimal midtermWeight;
    private BigDecimal finalWeight;
    /** The course's own max marks ({@code dbo.courses}), used to validate marks and compute {@link #totalMark}. */
    private BigDecimal courseworkMaxMark;
    private BigDecimal midtermMaxMark;
    private BigDecimal finalMaxMark;

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

    /** True once a submitted row has been edited in memory and needs to be re-saved. */
    public boolean isEditedAfterSubmit() {
        return editedAfterSubmit;
    }

    public void setEditedAfterSubmit(boolean editedAfterSubmit) {
        this.editedAfterSubmit = editedAfterSubmit;
    }

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

    /** Weight (%) of the Coursework mark (no lab) or the Lab mark (has lab). */
    public BigDecimal getCourseworkWeight() {
        return courseworkWeight;
    }

    public void setCourseworkWeight(BigDecimal courseworkWeight) {
        this.courseworkWeight = courseworkWeight;
    }

    public BigDecimal getMidtermWeight() {
        return midtermWeight;
    }

    public void setMidtermWeight(BigDecimal midtermWeight) {
        this.midtermWeight = midtermWeight;
    }

    public BigDecimal getFinalWeight() {
        return finalWeight;
    }

    public void setFinalWeight(BigDecimal finalWeight) {
        this.finalWeight = finalWeight;
    }

    /** Max mark of the Coursework mark (no lab) or the Lab mark (has lab). */
    public BigDecimal getCourseworkMaxMark() {
        return courseworkMaxMark;
    }

    public void setCourseworkMaxMark(BigDecimal courseworkMaxMark) {
        this.courseworkMaxMark = courseworkMaxMark;
    }

    public BigDecimal getMidtermMaxMark() {
        return midtermMaxMark;
    }

    public void setMidtermMaxMark(BigDecimal midtermMaxMark) {
        this.midtermMaxMark = midtermMaxMark;
    }

    public BigDecimal getFinalMaxMark() {
        return finalMaxMark;
    }

    public void setFinalMaxMark(BigDecimal finalMaxMark) {
        this.finalMaxMark = finalMaxMark;
    }

    /** True when a stored mark now exceeds its component's configured max — e.g. an admin lowered
     *  the max mark after this mark was entered. Never auto-corrected; the instructor must re-enter it. */
    public boolean isCourseworkMarkInvalid() {
        return courseworkMark != null && !GradeCalculator.isValidMark(courseworkMark, courseworkMaxMark);
    }

    public boolean isMidtermMarkInvalid() {
        return midtermMark != null && !GradeCalculator.isValidMark(midtermMark, midtermMaxMark);
    }

    public boolean isLabMarkInvalid() {
        return labMark != null && !GradeCalculator.isValidMark(labMark, courseworkMaxMark);
    }

    public boolean isFinalMarkInvalid() {
        return finalMark != null && !GradeCalculator.isValidMark(finalMark, finalMaxMark);
    }

    /** True when any mark on this row needs the instructor's correction before it can be saved/submitted. */
    public boolean hasInvalidMark() {
        return isCourseworkMarkInvalid() || isMidtermMarkInvalid() || isLabMarkInvalid() || isFinalMarkInvalid();
    }

    /**
     * Recomputes total/letter/points from the three marks — Section 5.1 and 5.2.
     *
     * <p>A row with a mark above its component's max ({@link #hasInvalidMark()}) shows no
     * total/letter/points at all until the instructor corrects it — never a value computed from
     * the out-of-range mark, and never a silently clamped one.</p>
     */
    public void recompute() {
        if (hasInvalidMark()) {
            this.totalMark = null;
            this.letterGrade = null;
            this.gradePoints = null;
            return;
        }
        this.totalMark = GradeCalculator.totalMark(courseworkMark, midtermMark, labMark, finalMark, hasLab,
                courseworkWeight, midtermWeight, finalWeight,
                courseworkMaxMark, midtermMaxMark, finalMaxMark);
        this.letterGrade = GradeCalculator.letterGrade(totalMark);
        this.gradePoints = letterGrade == null ? null : letterGrade.getGradePoints();
    }

    @Override
    public String toString() {
        return studentUserId + " " + studentName;
    }
}
