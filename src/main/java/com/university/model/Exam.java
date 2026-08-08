package com.university.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * One row of {@code dbo.exams}: one scheduled exam for one section.
 *
 * <p>{@code dayOfWeek} is not stored — callers derive it from
 * {@link #getExamDate()}.</p>
 */
public class Exam {

    private int examId;
    private int sectionId;
    private LocalDate examDate;
    private LocalTime startTime;
    private int durationMinutes;
    private String room;
    private Integer createdBy;
    private LocalDateTime createdAt;

    public Exam() {
    }

    public Exam(int examId, int sectionId, LocalDate examDate, LocalTime startTime,
                int durationMinutes, String room, Integer createdBy, LocalDateTime createdAt) {
        this.examId = examId;
        this.sectionId = sectionId;
        this.examDate = examDate;
        this.startTime = startTime;
        this.durationMinutes = durationMinutes;
        this.room = room;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public int getExamId() {
        return examId;
    }

    public void setExamId(int examId) {
        this.examId = examId;
    }

    public int getSectionId() {
        return sectionId;
    }

    public void setSectionId(int sectionId) {
        this.sectionId = sectionId;
    }

    public LocalDate getExamDate() {
        return examDate;
    }

    public void setExamDate(LocalDate examDate) {
        this.examDate = examDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    /** Null when the exam was created before this column existed, or by no particular user. */
    public Integer getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
