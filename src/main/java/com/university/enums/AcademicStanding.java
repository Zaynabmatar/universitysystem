package com.university.enums;

/**
 * Academic standing derived from the cumulative GPA.
 *
 * <p>Mirrors {@code CK_students_standing} on
 * {@code dbo.students.academic_standing}.</p>
 */
public enum AcademicStanding {

    NEW("New"),
    GOOD("Good Standing"),
    DEANS_LIST("Dean's List"),
    PROBATION("Probation"),
    SUSPENDED("Suspended");

    private final String label;

    AcademicStanding(String label) {
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
    public static AcademicStanding fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
