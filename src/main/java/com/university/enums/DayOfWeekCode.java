package com.university.enums;

import java.time.DayOfWeek;

/**
 * The three-letter weekday code used by the weekly timetable.
 *
 * <p>Mirrors {@code CK_section_schedules_day} on
 * {@code dbo.section_schedules.day_of_week}. The database stores the short
 * code, so the constant name is the code itself.</p>
 */
public enum DayOfWeekCode {

    SUN("Sunday",    DayOfWeek.SUNDAY),
    MON("Monday",    DayOfWeek.MONDAY),
    TUE("Tuesday",   DayOfWeek.TUESDAY),
    WED("Wednesday", DayOfWeek.WEDNESDAY),
    THU("Thursday",  DayOfWeek.THURSDAY),
    FRI("Friday",    DayOfWeek.FRIDAY),
    SAT("Saturday",  DayOfWeek.SATURDAY);

    private final String label;
    private final DayOfWeek javaDay;

    DayOfWeekCode(String label, DayOfWeek javaDay) {
        this.label = label;
        this.javaDay = javaDay;
    }

    /** The full day name shown in the user interface. */
    public String getLabel() {
        return label;
    }

    /** The matching constant from {@code java.time}. */
    public DayOfWeek toJavaDayOfWeek() {
        return javaDay;
    }

    /** The exact string stored in the database. */
    public String toDb() {
        return name();
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static DayOfWeekCode fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    /**
     * Converts a {@code java.time} weekday into the code used by the schema.
     *
     * @param day the weekday, may be null
     * @return the matching constant, or null when {@code day} is null
     */
    public static DayOfWeekCode fromJavaDayOfWeek(DayOfWeek day) {
        if (day == null) {
            return null;
        }
        for (DayOfWeekCode code : values()) {
            if (code.javaDay == day) {
                return code;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return label;
    }
}
