package com.university.model;

import com.university.enums.Term;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of {@code dbo.semesters}, the clock of the whole system.
 *
 * <p>A filtered unique index guarantees that at most one row in the table has
 * {@code isCurrent} set.</p>
 */
public class Semester {

    private int semesterId;
    private String semesterName;
    private String academicYear;
    private Term term;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDateTime registrationStart;
    private LocalDateTime registrationEnd;
    private LocalDate dropDeadline;
    private LocalDate withdrawDeadline;
    private LocalDate gradeEntryStart;
    private LocalDate gradeEntryEnd;
    private LocalDateTime evaluationStart;
    private LocalDateTime evaluationEnd;
    private boolean current;

    public Semester() {
    }

    public Semester(int semesterId, String semesterName, String academicYear, Term term,
                    LocalDate startDate, LocalDate endDate,
                    LocalDateTime registrationStart, LocalDateTime registrationEnd,
                    LocalDate dropDeadline, LocalDate withdrawDeadline,
                    LocalDate gradeEntryStart, LocalDate gradeEntryEnd, boolean current) {
        this.semesterId = semesterId;
        this.semesterName = semesterName;
        this.academicYear = academicYear;
        this.term = term;
        this.startDate = startDate;
        this.endDate = endDate;
        this.registrationStart = registrationStart;
        this.registrationEnd = registrationEnd;
        this.dropDeadline = dropDeadline;
        this.withdrawDeadline = withdrawDeadline;
        this.gradeEntryStart = gradeEntryStart;
        this.gradeEntryEnd = gradeEntryEnd;
        this.current = current;
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

    public String getAcademicYear() {
        return academicYear;
    }

    public void setAcademicYear(String academicYear) {
        this.academicYear = academicYear;
    }

    public Term getTerm() {
        return term;
    }

    public void setTerm(Term term) {
        this.term = term;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public LocalDateTime getRegistrationStart() {
        return registrationStart;
    }

    public void setRegistrationStart(LocalDateTime registrationStart) {
        this.registrationStart = registrationStart;
    }

    public LocalDateTime getRegistrationEnd() {
        return registrationEnd;
    }

    public void setRegistrationEnd(LocalDateTime registrationEnd) {
        this.registrationEnd = registrationEnd;
    }

    public LocalDate getDropDeadline() {
        return dropDeadline;
    }

    public void setDropDeadline(LocalDate dropDeadline) {
        this.dropDeadline = dropDeadline;
    }

    public LocalDate getWithdrawDeadline() {
        return withdrawDeadline;
    }

    public void setWithdrawDeadline(LocalDate withdrawDeadline) {
        this.withdrawDeadline = withdrawDeadline;
    }

    public LocalDate getGradeEntryStart() {
        return gradeEntryStart;
    }

    public void setGradeEntryStart(LocalDate gradeEntryStart) {
        this.gradeEntryStart = gradeEntryStart;
    }

    public LocalDate getGradeEntryEnd() {
        return gradeEntryEnd;
    }

    public void setGradeEntryEnd(LocalDate gradeEntryEnd) {
        this.gradeEntryEnd = gradeEntryEnd;
    }

    public LocalDateTime getEvaluationStart() {
        return evaluationStart;
    }

    public void setEvaluationStart(LocalDateTime evaluationStart) {
        this.evaluationStart = evaluationStart;
    }

    public LocalDateTime getEvaluationEnd() {
        return evaluationEnd;
    }

    public void setEvaluationEnd(LocalDateTime evaluationEnd) {
        this.evaluationEnd = evaluationEnd;
    }

    /** True when the instructor-evaluation window is open at the given moment. */
    public boolean isEvaluationOpen(LocalDateTime moment) {
        if (evaluationStart == null || evaluationEnd == null || moment == null) {
            return false;
        }
        return !moment.isBefore(evaluationStart) && !moment.isAfter(evaluationEnd);
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    /** True when {@code moment} falls inside the registration window. */
    public boolean isRegistrationOpen(LocalDateTime moment) {
        if (registrationStart == null || registrationEnd == null || moment == null) {
            return false;
        }
        return !moment.isBefore(registrationStart) && !moment.isAfter(registrationEnd);
    }

    @Override
    public String toString() {
        return semesterName;
    }
}

