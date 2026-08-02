package com.university.model;

import java.time.LocalDateTime;

/**
 * One row of {@code dbo.advisors_directory}, a read-only contact list shown
 * inside My Account.
 *
 * <p>The table is deliberately standalone: no student is linked to an
 * advisor.</p>
 */
public class AdvisorDirectory {

    private int advisorId;
    private String fullName;
    private String department;
    private String universityEmail;
    private String office;
    private String phone;
    private boolean active = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AdvisorDirectory() {
    }

    public AdvisorDirectory(int advisorId, String fullName, String department, String universityEmail,
                            String office, String phone, boolean active,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.advisorId = advisorId;
        this.fullName = fullName;
        this.department = department;
        this.universityEmail = universityEmail;
        this.office = office;
        this.phone = phone;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public int getAdvisorId() {
        return advisorId;
    }

    public void setAdvisorId(int advisorId) {
        this.advisorId = advisorId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    /** Free text, not a foreign key to {@code dbo.departments}. */
    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getUniversityEmail() {
        return universityEmail;
    }

    public void setUniversityEmail(String universityEmail) {
        this.universityEmail = universityEmail;
    }

    public String getOffice() {
        return office;
    }

    public void setOffice(String office) {
        this.office = office;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return fullName + " - " + universityEmail;
    }
}
