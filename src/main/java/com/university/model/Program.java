package com.university.model;

import com.university.enums.DegreeType;

/**
 * One row of {@code dbo.programs}, the degree plan a student follows.
 */
public class Program {

    private int programId;
    private int departmentId;
    private String programCode;
    private String programName;
    private DegreeType degreeType;
    private int totalCreditsRequired;
    private boolean active = true;

    public Program() {
    }

    public Program(int programId, int departmentId, String programCode, String programName,
                   DegreeType degreeType, int totalCreditsRequired, boolean active) {
        this.programId = programId;
        this.departmentId = departmentId;
        this.programCode = programCode;
        this.programName = programName;
        this.degreeType = degreeType;
        this.totalCreditsRequired = totalCreditsRequired;
        this.active = active;
    }

    public int getProgramId() {
        return programId;
    }

    public void setProgramId(int programId) {
        this.programId = programId;
    }

    public int getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(int departmentId) {
        this.departmentId = departmentId;
    }

    public String getProgramCode() {
        return programCode;
    }

    public void setProgramCode(String programCode) {
        this.programCode = programCode;
    }

    public String getProgramName() {
        return programName;
    }

    public void setProgramName(String programName) {
        this.programName = programName;
    }

    public DegreeType getDegreeType() {
        return degreeType;
    }

    public void setDegreeType(DegreeType degreeType) {
        this.degreeType = degreeType;
    }

    public int getTotalCreditsRequired() {
        return totalCreditsRequired;
    }

    public void setTotalCreditsRequired(int totalCreditsRequired) {
        this.totalCreditsRequired = totalCreditsRequired;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return programCode + " - " + programName;
    }
}
