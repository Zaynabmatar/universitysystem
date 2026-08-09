package com.university.model;

import com.university.enums.AcademicRank;

import java.time.LocalDate;

/**
 * One row of {@code dbo.instructors}.
 */
public class Instructor {

    private int instructorId;
    private int userId;
    private String employeeNumber;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String address;
    private int departmentId;
    private AcademicRank academicRank;
    private LocalDate hireDate;
    private boolean active = true;

    public Instructor() {
    }

    public Instructor(int instructorId, int userId, String employeeNumber, String firstName,
                      String lastName, String email, String phone, int departmentId,
                      AcademicRank academicRank, LocalDate hireDate, boolean active) {
        this.instructorId = instructorId;
        this.userId = userId;
        this.employeeNumber = employeeNumber;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.departmentId = departmentId;
        this.academicRank = academicRank;
        this.hireDate = hireDate;
        this.active = active;
    }

    public int getInstructorId() {
        return instructorId;
    }

    public void setInstructorId(int instructorId) {
        this.instructorId = instructorId;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public void setEmployeeNumber(String employeeNumber) {
        this.employeeNumber = employeeNumber;
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

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(int departmentId) {
        this.departmentId = departmentId;
    }

    public AcademicRank getAcademicRank() {
        return academicRank;
    }

    public void setAcademicRank(AcademicRank academicRank) {
        this.academicRank = academicRank;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public void setHireDate(LocalDate hireDate) {
        this.hireDate = hireDate;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return getFullName() + " (" + academicRank + ")";
    }
}
