package com.university.enums;

/**
 * One student's attendance mark for one class meeting.
 *
 * <p>Mirrors {@code CK_attendance_records_status} on
 * {@code dbo.attendance_records.status}.</p>
 */
public enum AttendanceStatus {

    PRESENT("Present"),
    ABSENT("Absent");

    private final String label;

    AttendanceStatus(String label) {
        this.label = label;
    }

    /** The text shown in the user interface. */
    public String getLabel() {
        return label;
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
    public static AttendanceStatus fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
