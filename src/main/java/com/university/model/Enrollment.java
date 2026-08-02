package com.university.model;

import com.university.enums.EnrollmentStatus;

import java.time.LocalDateTime;

/**
 * One row of {@code dbo.enrollments}, the bridge between a student and a
 * section.
 *
 * <p>{@code countsInGpa} is cleared when a repeated course should no longer
 * affect the cumulative GPA.</p>
 */
public class Enrollment {

    private int enrollmentId;
    private int studentId;
    private int sectionId;
    private LocalDateTime enrollmentDate;
    private EnrollmentStatus status = EnrollmentStatus.ENROLLED;
    private boolean repeat;
    private boolean countsInGpa = true;

    public Enrollment() {
    }

    public Enrollment(int enrollmentId, int studentId, int sectionId, LocalDateTime enrollmentDate,
                      EnrollmentStatus status, boolean repeat, boolean countsInGpa) {
        this.enrollmentId = enrollmentId;
        this.studentId = studentId;
        this.sectionId = sectionId;
        this.enrollmentDate = enrollmentDate;
        this.status = status;
        this.repeat = repeat;
        this.countsInGpa = countsInGpa;
    }

    public int getEnrollmentId() {
        return enrollmentId;
    }

    public void setEnrollmentId(int enrollmentId) {
        this.enrollmentId = enrollmentId;
    }

    public int getStudentId() {
        return studentId;
    }

    public void setStudentId(int studentId) {
        this.studentId = studentId;
    }

    public int getSectionId() {
        return sectionId;
    }

    public void setSectionId(int sectionId) {
        this.sectionId = sectionId;
    }

    public LocalDateTime getEnrollmentDate() {
        return enrollmentDate;
    }

    public void setEnrollmentDate(LocalDateTime enrollmentDate) {
        this.enrollmentDate = enrollmentDate;
    }

    public EnrollmentStatus getStatus() {
        return status;
    }

    public void setStatus(EnrollmentStatus status) {
        this.status = status;
    }

    public boolean isRepeat() {
        return repeat;
    }

    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
    }

    public boolean isCountsInGpa() {
        return countsInGpa;
    }

    public void setCountsInGpa(boolean countsInGpa) {
        this.countsInGpa = countsInGpa;
    }

    @Override
    public String toString() {
        return "student " + studentId + " in section " + sectionId + " (" + status + ")";
    }
}
