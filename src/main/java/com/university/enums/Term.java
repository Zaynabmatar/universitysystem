package com.university.enums;

/**
 * The term part of a semester name.
 *
 * <p>Mirrors {@code CK_semesters_term} on {@code dbo.semesters.term}.</p>
 */
public enum Term {

    FALL("Fall"),
    SPRING("Spring"),
    SUMMER("Summer");

    private final String label;

    Term(String label) {
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
    public static Term fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
