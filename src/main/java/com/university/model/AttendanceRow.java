package com.university.model;

import com.university.enums.AttendanceStatus;

/**
 * One line of an instructor's attendance sheet for one class date: one enrolled student, joined
 * with whatever attendance row already exists for them on that date (none, the common case before
 * the first save — {@link #getAttendanceId()} is then null and {@link #getStatus()} defaults to
 * {@link AttendanceStatus#PRESENT}).
 *
 * <p>Not a table of its own — a read/edit projection over {@code enrollments}, {@code students},
 * {@code sections}/{@code courses} and {@code attendance_records} together, the same shape as
 * {@link GradeSheetRow}.</p>
 */
public class AttendanceRow {

    private int enrollmentId;
    private int studentId;
    /** users.user_id — the Student ID shown on the sheet, not the internal students.student_id. */
    private int studentUserId;
    private String studentName;
    private String sectionLabel;
    private Integer attendanceId;
    private AttendanceStatus status = AttendanceStatus.PRESENT;
    private int totalAbsences;

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

    public String getSectionLabel() {
        return sectionLabel;
    }

    public void setSectionLabel(String sectionLabel) {
        this.sectionLabel = sectionLabel;
    }

    /** Null until the first save creates the row. */
    public Integer getAttendanceId() {
        return attendanceId;
    }

    public void setAttendanceId(Integer attendanceId) {
        this.attendanceId = attendanceId;
    }

    public int getTotalAbsences() {
        return totalAbsences;
    }

    public void setTotalAbsences(int totalAbsences) {
        this.totalAbsences = totalAbsences;
    }

    public AttendanceStatus getStatus() {
        return status;
    }

    public void setStatus(AttendanceStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return studentUserId + " " + studentName;
    }
}
