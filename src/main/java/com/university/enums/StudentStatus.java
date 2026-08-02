package com.university.enums;

/**
 * Enrolment state of a student record.
 *
 * <p>Mirrors {@code CK_students_status} on {@code dbo.students.status}.</p>
 */
public enum StudentStatus {

    ACTIVE("Active"),
    SUSPENDED("Suspended"),
    GRADUATED("Graduated"),
    WITHDRAWN("Withdrawn");

    private final String label;

    StudentStatus(String label) {
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

    /** True only for a student who may still register for sections. */
    public boolean canRegister() {
        return this == ACTIVE;
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static StudentStatus fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
