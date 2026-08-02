package com.university.enums;

/**
 * Student gender.
 *
 * <p>Mirrors {@code CK_students_gender} on {@code dbo.students.gender}.
 * The column is nullable, so {@link #fromDb(String)} may return null.</p>
 */
public enum Gender {

    MALE("Male"),
    FEMALE("Female");

    private final String label;

    Gender(String label) {
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
    public static Gender fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
