package com.university.model;

import com.university.enums.SectionStatus;

/**
 * One row of {@code dbo.sections}: one offering of one course in one
 * semester.
 *
 * <p>{@code instructorId} is null while the section is published as TBA, so
 * it is an {@link Integer} rather than an {@code int}. {@code enrolledCount}
 * is a cached counter maintained by the database.</p>
 */
public class Section {

    private int sectionId;
    private int courseId;
    private int semesterId;
    private Integer instructorId;
    private int campusId;
    private String sectionNumber;
    private int capacity;
    private int enrolledCount;
    private String room;
    private SectionStatus status = SectionStatus.OPEN;

    public Section() {
    }

    public Section(int sectionId, int courseId, int semesterId, Integer instructorId, int campusId,
                   String sectionNumber, int capacity, int enrolledCount, String room,
                   SectionStatus status) {
        this.sectionId = sectionId;
        this.courseId = courseId;
        this.semesterId = semesterId;
        this.instructorId = instructorId;
        this.campusId = campusId;
        this.sectionNumber = sectionNumber;
        this.capacity = capacity;
        this.enrolledCount = enrolledCount;
        this.room = room;
        this.status = status;
    }

    public int getSectionId() {
        return sectionId;
    }

    public void setSectionId(int sectionId) {
        this.sectionId = sectionId;
    }

    public int getCourseId() {
        return courseId;
    }

    public void setCourseId(int courseId) {
        this.courseId = courseId;
    }

    public int getSemesterId() {
        return semesterId;
    }

    public void setSemesterId(int semesterId) {
        this.semesterId = semesterId;
    }

    /** Null when the section is published without an instructor. */
    public Integer getInstructorId() {
        return instructorId;
    }

    public void setInstructorId(Integer instructorId) {
        this.instructorId = instructorId;
    }

    public int getCampusId() {
        return campusId;
    }

    public void setCampusId(int campusId) {
        this.campusId = campusId;
    }

    public String getSectionNumber() {
        return sectionNumber;
    }

    public void setSectionNumber(String sectionNumber) {
        this.sectionNumber = sectionNumber;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getEnrolledCount() {
        return enrolledCount;
    }

    public void setEnrolledCount(int enrolledCount) {
        this.enrolledCount = enrolledCount;
    }

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    public SectionStatus getStatus() {
        return status;
    }

    public void setStatus(SectionStatus status) {
        this.status = status;
    }

    /** Seats still available, never negative. */
    public int getAvailableSeats() {
        return Math.max(0, capacity - enrolledCount);
    }

    /** True when every seat is taken. */
    public boolean isFull() {
        return enrolledCount >= capacity;
    }

    @Override
    public String toString() {
        return "Section " + sectionNumber + " (" + enrolledCount + "/" + capacity + ")";
    }
}
