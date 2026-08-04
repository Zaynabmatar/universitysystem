package com.university.model;

import com.university.enums.EnrollmentStatus;
import com.university.enums.LetterGrade;

import java.math.BigDecimal;

/**
 * One line of a student's My Grades screen: one enrollment, the course it is for, and the grade
 * if — and only if — the instructor has submitted it.
 *
 * <p>Not a table of its own, a read-only projection over {@code enrollments}, {@code sections},
 * {@code courses}, {@code semesters} and {@code grades}. The marks stay null while
 * {@code is_submitted = 0}: project_details.md Section 6.6 is explicit that a student must never
 * see a draft, so the DAO does not read the columns at all until the grade is published.</p>
 */
public class StudentGradeRow {

    private int enrollmentId;
    private int semesterId;
    private String semesterName;
    private String courseCode;
    private String courseTitle;
    private int credits;
    private EnrollmentStatus enrollmentStatus;
    private boolean countsInGpa;
    private boolean submitted;
    private BigDecimal courseworkMark;
    private BigDecimal midtermMark;
    private BigDecimal finalMark;
    private BigDecimal totalMark;
    private LetterGrade letterGrade;
    private BigDecimal gradePoints;

    public int getEnrollmentId() {
        return enrollmentId;
    }

    public void setEnrollmentId(int enrollmentId) {
        this.enrollmentId = enrollmentId;
    }

    public int getSemesterId() {
        return semesterId;
    }

    public void setSemesterId(int semesterId) {
        this.semesterId = semesterId;
    }

    public String getSemesterName() {
        return semesterName;
    }

    public void setSemesterName(String semesterName) {
        this.semesterName = semesterName;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getCourseTitle() {
        return courseTitle;
    }

    public void setCourseTitle(String courseTitle) {
        this.courseTitle = courseTitle;
    }

    public int getCredits() {
        return credits;
    }

    public void setCredits(int credits) {
        this.credits = credits;
    }

    public EnrollmentStatus getEnrollmentStatus() {
        return enrollmentStatus;
    }

    public void setEnrollmentStatus(EnrollmentStatus enrollmentStatus) {
        this.enrollmentStatus = enrollmentStatus;
    }

    /** False on an earlier attempt that a repeat has retired — Section 5.5. */
    public boolean isCountsInGpa() {
        return countsInGpa;
    }

    public void setCountsInGpa(boolean countsInGpa) {
        this.countsInGpa = countsInGpa;
    }

    /** Only a submitted grade is ever shown; a draft reads as "Not graded yet". */
    public boolean isSubmitted() {
        return submitted;
    }

    public void setSubmitted(boolean submitted) {
        this.submitted = submitted;
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

    /** True for a withdrawal, which stays on the record but never touches the average. */
    public boolean isWithdrawn() {
        return letterGrade == LetterGrade.W || enrollmentStatus == EnrollmentStatus.WITHDRAWN;
    }

    /** True for an earlier attempt that a later one has replaced — Section 5.5. */
    public boolean isRepeated() {
        return !countsInGpa && enrollmentStatus == EnrollmentStatus.COMPLETED;
    }

    /**
     * The Status column of {@code student_grades.fxml}.
     *
     * <p>Order matters: a repeated attempt is still completed, and a withdrawal is neither, so
     * the more specific answers come first.</p>
     */
    public String statusLabel() {
        if (isWithdrawn()) {
            return "Withdrawn (W) — does not affect GPA";
        }
        if (isRepeated()) {
            return "Repeated — does not affect GPA";
        }
        if (!submitted) {
            return "In progress — not graded yet";
        }
        return "Completed";
    }

    @Override
    public String toString() {
        return courseCode + " " + courseTitle;
    }
}
