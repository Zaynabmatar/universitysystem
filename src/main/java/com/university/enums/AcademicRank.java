package com.university.enums;

/**
 * The academic rank of an instructor.
 *
 * <p>Mirrors {@code CK_instructors_rank} on
 * {@code dbo.instructors.academic_rank}.</p>
 */
public enum AcademicRank {

    PROFESSOR("Professor"),
    ASSOCIATE("Associate Professor"),
    ASSISTANT("Assistant Professor"),
    LECTURER("Lecturer");

    private final String label;

    AcademicRank(String label) {
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
    public static AcademicRank fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
