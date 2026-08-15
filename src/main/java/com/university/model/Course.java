package com.university.model;

import java.math.BigDecimal;

/**
 * One row of {@code dbo.courses}, a permanent catalogue entry.
 *
 * <p>A course is not tied to a semester. The offering of a course in one
 * semester is a {@link Section}.</p>
 */
public class Course {

    private int courseId;
    private String courseCode;
    private String courseTitle;
    private String description;
    private int credits;
    private int departmentId;
    private int levelYear;
    private boolean active = true;
    private boolean hasLab;
    /** Percent weight of the Coursework mark (no lab) or the Lab mark (has_lab); with
     *  {@link #midtermWeight} and {@link #finalWeight}, always sums to 100. */
    private BigDecimal courseworkWeight = new BigDecimal("20.00");
    private BigDecimal midtermWeight = new BigDecimal("30.00");
    private BigDecimal finalWeight = new BigDecimal("50.00");
    /** Max mark a component is out of; the instructor's raw entry is validated against this and
     *  {@link com.university.util.GradeCalculator} divides by it before applying the weight. */
    private BigDecimal courseworkMaxMark = new BigDecimal("100.00");
    private BigDecimal midtermMaxMark = new BigDecimal("100.00");
    private BigDecimal finalMaxMark = new BigDecimal("100.00");

    public Course() {
    }

    public Course(int courseId, String courseCode, String courseTitle, String description,
                  int credits, int departmentId, int levelYear, boolean active) {
        this.courseId = courseId;
        this.courseCode = courseCode;
        this.courseTitle = courseTitle;
        this.description = description;
        this.credits = credits;
        this.departmentId = departmentId;
        this.levelYear = levelYear;
        this.active = active;
    }

    public int getCourseId() {
        return courseId;
    }

    public void setCourseId(int courseId) {
        this.courseId = courseId;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getCredits() {
        return credits;
    }

    public void setCredits(int credits) {
        this.credits = credits;
    }

    public int getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(int departmentId) {
        this.departmentId = departmentId;
    }

    public int getLevelYear() {
        return levelYear;
    }

    public void setLevelYear(int levelYear) {
        this.levelYear = levelYear;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** True when this course has a lab component (any course, not one hardcoded course). */
    public boolean isHasLab() {
        return hasLab;
    }

    public void setHasLab(boolean hasLab) {
        this.hasLab = hasLab;
    }

    /** Weight (%) of the Coursework mark on a no-lab course, or the Lab mark when {@link #isHasLab()}. */
    public BigDecimal getCourseworkWeight() {
        return courseworkWeight;
    }

    public void setCourseworkWeight(BigDecimal courseworkWeight) {
        this.courseworkWeight = courseworkWeight;
    }

    public BigDecimal getMidtermWeight() {
        return midtermWeight;
    }

    public void setMidtermWeight(BigDecimal midtermWeight) {
        this.midtermWeight = midtermWeight;
    }

    public BigDecimal getFinalWeight() {
        return finalWeight;
    }

    public void setFinalWeight(BigDecimal finalWeight) {
        this.finalWeight = finalWeight;
    }

    /** Max mark of the Coursework mark on a no-lab course, or the Lab mark when {@link #isHasLab()}. */
    public BigDecimal getCourseworkMaxMark() {
        return courseworkMaxMark;
    }

    public void setCourseworkMaxMark(BigDecimal courseworkMaxMark) {
        this.courseworkMaxMark = courseworkMaxMark;
    }

    public BigDecimal getMidtermMaxMark() {
        return midtermMaxMark;
    }

    public void setMidtermMaxMark(BigDecimal midtermMaxMark) {
        this.midtermMaxMark = midtermMaxMark;
    }

    public BigDecimal getFinalMaxMark() {
        return finalMaxMark;
    }

    public void setFinalMaxMark(BigDecimal finalMaxMark) {
        this.finalMaxMark = finalMaxMark;
    }

    @Override
    public String toString() {
        return courseCode + " - " + courseTitle;
    }
}
