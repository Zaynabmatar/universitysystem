package com.university.model;

import java.math.BigDecimal;

/**
 * One row of {@code dbo.course_prerequisites}.
 *
 * <p>Both identifiers point at {@code dbo.courses}: the course being taken
 * and the course that must already be passed. {@code minGradePoints} is the
 * lowest grade that counts as passed for this link.</p>
 */
public class CoursePrerequisite {

    private int prerequisiteId;
    private int courseId;
    private int prerequisiteCourseId;
    private BigDecimal minGradePoints = BigDecimal.ONE;

    public CoursePrerequisite() {
    }

    public CoursePrerequisite(int prerequisiteId, int courseId, int prerequisiteCourseId,
                              BigDecimal minGradePoints) {
        this.prerequisiteId = prerequisiteId;
        this.courseId = courseId;
        this.prerequisiteCourseId = prerequisiteCourseId;
        this.minGradePoints = minGradePoints;
    }

    public int getPrerequisiteId() {
        return prerequisiteId;
    }

    public void setPrerequisiteId(int prerequisiteId) {
        this.prerequisiteId = prerequisiteId;
    }

    public int getCourseId() {
        return courseId;
    }

    public void setCourseId(int courseId) {
        this.courseId = courseId;
    }

    public int getPrerequisiteCourseId() {
        return prerequisiteCourseId;
    }

    public void setPrerequisiteCourseId(int prerequisiteCourseId) {
        this.prerequisiteCourseId = prerequisiteCourseId;
    }

    public BigDecimal getMinGradePoints() {
        return minGradePoints;
    }

    public void setMinGradePoints(BigDecimal minGradePoints) {
        this.minGradePoints = minGradePoints;
    }

    @Override
    public String toString() {
        return "course " + courseId + " requires course " + prerequisiteCourseId
                + " with at least " + minGradePoints + " points";
    }
}
