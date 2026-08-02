package com.university.model;

import com.university.enums.WaitlistStatus;

import java.time.LocalDateTime;

/**
 * One row of {@code dbo.waitlist}, the queue for a section that is full.
 *
 * <p>{@code position} starts at one and is unique per section in practice.</p>
 */
public class Waitlist {

    private int waitlistId;
    private int sectionId;
    private int studentId;
    private int position;
    private WaitlistStatus status = WaitlistStatus.WAITING;
    private LocalDateTime createdAt;

    public Waitlist() {
    }

    public Waitlist(int waitlistId, int sectionId, int studentId, int position,
                    WaitlistStatus status, LocalDateTime createdAt) {
        this.waitlistId = waitlistId;
        this.sectionId = sectionId;
        this.studentId = studentId;
        this.position = position;
        this.status = status;
        this.createdAt = createdAt;
    }

    public int getWaitlistId() {
        return waitlistId;
    }

    public void setWaitlistId(int waitlistId) {
        this.waitlistId = waitlistId;
    }

    public int getSectionId() {
        return sectionId;
    }

    public void setSectionId(int sectionId) {
        this.sectionId = sectionId;
    }

    public int getStudentId() {
        return studentId;
    }

    public void setStudentId(int studentId) {
        this.studentId = studentId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public WaitlistStatus getStatus() {
        return status;
    }

    public void setStatus(WaitlistStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "position " + position + " for student " + studentId + " (" + status + ")";
    }
}
