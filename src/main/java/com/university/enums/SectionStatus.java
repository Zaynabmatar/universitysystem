package com.university.enums;

/**
 * Whether a section is still accepting registrations.
 *
 * <p>Mirrors {@code CK_sections_status} on {@code dbo.sections.status}.</p>
 */
public enum SectionStatus {

    OPEN("Open"),
    CLOSED("Closed"),
    CANCELLED("Cancelled");

    private final String label;

    SectionStatus(String label) {
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

    /** True only while the section can still take new enrolments. */
    public boolean acceptsEnrolment() {
        return this == OPEN;
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static SectionStatus fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
