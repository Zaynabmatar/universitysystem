package com.university.enums;

/**
 * The life cycle of one student in one section.
 *
 * <p>Mirrors {@code CK_enrollments_status} on
 * {@code dbo.enrollments.status}.</p>
 */
public enum EnrollmentStatus {

    ENROLLED("Enrolled"),
    DROPPED("Dropped"),
    WITHDRAWN("Withdrawn"),
    COMPLETED("Completed");

    private final String label;

    EnrollmentStatus(String label) {
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

    /** True while the row still occupies a seat in the section. */
    public boolean occupiesSeat() {
        return this == ENROLLED || this == COMPLETED;
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static EnrollmentStatus fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
