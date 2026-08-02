package com.university.model;

/**
 * One row of {@code dbo.departments}, the root table of the academic
 * structure.
 */
public class Department {

    private int departmentId;
    private String departmentCode;
    private String departmentName;
    private boolean active = true;

    public Department() {
    }

    public Department(int departmentId, String departmentCode, String departmentName, boolean active) {
        this.departmentId = departmentId;
        this.departmentCode = departmentCode;
        this.departmentName = departmentName;
        this.active = active;
    }

    public int getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(int departmentId) {
        this.departmentId = departmentId;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public void setDepartmentCode(String departmentCode) {
        this.departmentCode = departmentCode;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return departmentCode + " - " + departmentName;
    }
}
