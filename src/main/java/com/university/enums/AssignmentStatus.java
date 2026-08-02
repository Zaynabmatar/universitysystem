package com.university.enums;

/**
 * Whether an assignment still accepts work.
 *
 * <p>Mirrors {@code CK_assignments_status} on
 * {@code dbo.assignments.status}.</p>
 */
public enum AssignmentStatus {

    ACTIVE("Active"),
    CLOSED("Closed"),
    CANCELLED("Cancelled");

    private final String label;

    AssignmentStatus(String label) {
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
    public static AssignmentStatus fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
