package com.university.model;

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

    @Override
    public String toString() {
        return courseCode + " - " + courseTitle;
    }
}
