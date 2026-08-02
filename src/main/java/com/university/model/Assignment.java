package com.university.model;

import com.university.enums.AssignmentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of {@code dbo.assignments}.
 *
 * <p>An assignment belongs to a section rather than to a course, so two
 * sections of the same course can set different work.</p>
 */
public class Assignment {

    private int assignmentId;
    private int sectionId;
    private String title;
    private String description;
    private LocalDateTime assignedDate;
    private LocalDateTime dueDate;
    private BigDecimal maximumMark;
    private int createdBy;
    private AssignmentStatus status = AssignmentStatus.ACTIVE;
    private LocalDateTime createdAt;

    public Assignment() {
    }

    public Assignment(int assignmentId, int sectionId, String title, String description,
                      LocalDateTime assignedDate, LocalDateTime dueDate, BigDecimal maximumMark,
                      int createdBy, AssignmentStatus status, LocalDateTime createdAt) {
        this.assignmentId = assignmentId;
        this.sectionId = sectionId;
        this.title = title;
        this.description = description;
        this.assignedDate = assignedDate;
        this.dueDate = dueDate;
        this.maximumMark = maximumMark;
        this.createdBy = createdBy;
        this.status = status;
        this.createdAt = createdAt;
    }

    public int getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(int assignmentId) {
        this.assignmentId = assignmentId;
    }

    public int getSectionId() {
        return sectionId;
    }

    public void setSectionId(int sectionId) {
        this.sectionId = sectionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getAssignedDate() {
        return assignedDate;
    }

    public void setAssignedDate(LocalDateTime assignedDate) {
        this.assignedDate = assignedDate;
    }

    public LocalDateTime getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDateTime dueDate) {
        this.dueDate = dueDate;
    }

    /** Null when the assignment carries no mark. */
    public BigDecimal getMaximumMark() {
        return maximumMark;
    }

    public void setMaximumMark(BigDecimal maximumMark) {
        this.maximumMark = maximumMark;
    }

    /** The {@code user_id} of the instructor who created it. */
    public int getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(int createdBy) {
        this.createdBy = createdBy;
    }

    public AssignmentStatus getStatus() {
        return status;
    }

    public void setStatus(AssignmentStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** True when {@code moment} is past the due date. */
    public boolean isOverdue(LocalDateTime moment) {
        return dueDate != null && moment != null && moment.isAfter(dueDate);
    }

    @Override
    public String toString() {
        return title + " (due " + dueDate + ")";
    }
}
