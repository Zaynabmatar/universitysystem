package com.university.model;

/**
 * One row of {@code dbo.program_requirements}, the degree plan in table form.
 */
public class ProgramRequirement {

    private int requirementId;
    private int programId;
    private int courseId;
    private boolean mandatory = true;
    private int recommendedSemester;

    public ProgramRequirement() {
    }

    public ProgramRequirement(int requirementId, int programId, int courseId,
                              boolean mandatory, int recommendedSemester) {
        this.requirementId = requirementId;
        this.programId = programId;
        this.courseId = courseId;
        this.mandatory = mandatory;
        this.recommendedSemester = recommendedSemester;
    }

    public int getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(int requirementId) {
        this.requirementId = requirementId;
    }

    public int getProgramId() {
        return programId;
    }

    public void setProgramId(int programId) {
        this.programId = programId;
    }

    public int getCourseId() {
        return courseId;
    }

    public void setCourseId(int courseId) {
        this.courseId = courseId;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public int getRecommendedSemester() {
        return recommendedSemester;
    }

    public void setRecommendedSemester(int recommendedSemester) {
        this.recommendedSemester = recommendedSemester;
    }

    @Override
    public String toString() {
        return "course " + courseId + " in semester " + recommendedSemester
                + (mandatory ? " (mandatory)" : " (elective)");
    }
}
