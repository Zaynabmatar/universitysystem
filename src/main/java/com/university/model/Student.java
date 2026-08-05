package com.university.model;

import com.university.enums.AcademicStanding;
import com.university.enums.Gender;
import com.university.enums.StudentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of {@code dbo.students}.
 *
 * <p>{@code cumulativeGpa} and {@code completedCredits} are cached values
 * refreshed by {@code sp_RecalculateStudentGPA}. They are read here, never
 * computed here.</p>
 */
public class Student {

    private int studentId;
    private int userId;
    private String studentNumber;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private LocalDate dateOfBirth;
    private Gender gender;
    private String address;
    private int programId;
    private LocalDate admissionDate;
    private StudentStatus status = StudentStatus.ACTIVE;
    private AcademicStanding academicStanding = AcademicStanding.NEW;
    private BigDecimal cumulativeGpa = BigDecimal.ZERO;
    private int completedCredits;
    private int probationCount;

    public Student() {
    }

    public Student(int studentId, int userId, String studentNumber, String firstName, String lastName,
                   String email, String phone, LocalDate dateOfBirth, Gender gender, String address,
                   int programId, LocalDate admissionDate, StudentStatus status,
                   AcademicStanding academicStanding, BigDecimal cumulativeGpa,
                   int completedCredits, int probationCount) {
        this.studentId = studentId;
        this.userId = userId;
        this.studentNumber = studentNumber;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.dateOfBirth = dateOfBirth;
        this.gender = gender;
        this.address = address;
        this.programId = programId;
        this.admissionDate = admissionDate;
        this.status = status;
        this.academicStanding = academicStanding;
        this.cumulativeGpa = cumulativeGpa;
        this.completedCredits = completedCredits;
        this.probationCount = probationCount;
    }

    public int getStudentId() {
        return studentId;
    }

    public void setStudentId(int studentId) {
        this.studentId = studentId;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getStudentNumber() {
        return studentNumber;
    }

    public void setStudentNumber(String studentNumber) {
        this.studentNumber = studentNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /** First and last name joined for display. */
    public String getFullName() {
        return firstName + " " + lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getProgramId() {
        return programId;
    }

    public void setProgramId(int programId) {
        this.programId = programId;
    }

    public LocalDate getAdmissionDate() {
        return admissionDate;
    }

    public void setAdmissionDate(LocalDate admissionDate) {
        this.admissionDate = admissionDate;
    }

    public StudentStatus getStatus() {
        return status;
    }

    public void setStatus(StudentStatus status) {
        this.status = status;
    }

    public AcademicStanding getAcademicStanding() {
        return academicStanding;
    }

    public void setAcademicStanding(AcademicStanding academicStanding) {
        this.academicStanding = academicStanding;
    }

    public BigDecimal getCumulativeGpa() {
        return cumulativeGpa;
    }

    public void setCumulativeGpa(BigDecimal cumulativeGpa) {
        this.cumulativeGpa = cumulativeGpa;
    }

    public int getCompletedCredits() {
        return completedCredits;
    }

    public void setCompletedCredits(int completedCredits) {
        this.completedCredits = completedCredits;
    }

    public int getProbationCount() {
        return probationCount;
    }

    public void setProbationCount(int probationCount) {
        this.probationCount = probationCount;
    }

    /** How a student reads in a picker: "1042 - Zaynab Matar" — the Student ID
     *  is {@code users.user_id}, the same number shown everywhere else. */
    @Override
    public String toString() {
        return userId + " - " + getFullName();
    }
}
