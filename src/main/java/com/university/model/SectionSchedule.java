package com.university.model;

import com.university.enums.DayOfWeekCode;

import java.time.LocalTime;

/**
 * One row of {@code dbo.section_schedules}: one weekly meeting of a section.
 */
public class SectionSchedule {

    private int scheduleId;
    private int sectionId;
    private DayOfWeekCode dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;

    public SectionSchedule() {
    }

    public SectionSchedule(int scheduleId, int sectionId, DayOfWeekCode dayOfWeek,
                           LocalTime startTime, LocalTime endTime) {
        this.scheduleId = scheduleId;
        this.sectionId = sectionId;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public int getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(int scheduleId) {
        this.scheduleId = scheduleId;
    }

    public int getSectionId() {
        return sectionId;
    }

    public void setSectionId(int sectionId) {
        this.sectionId = sectionId;
    }

    public DayOfWeekCode getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(DayOfWeekCode dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    /**
     * True when this meeting overlaps another one on the same day.
     * Used by the timetable clash check.
     *
     * @param other the meeting to compare with, may be null
     */
    public boolean clashesWith(SectionSchedule other) {
        if (other == null || dayOfWeek == null || other.dayOfWeek != dayOfWeek) {
            return false;
        }
        if (startTime == null || endTime == null
                || other.startTime == null || other.endTime == null) {
            return false;
        }
        return startTime.isBefore(other.endTime) && other.startTime.isBefore(endTime);
    }

    @Override
    public String toString() {
        return dayOfWeek + " " + startTime + " - " + endTime;
    }
}
