package com.university.enums;

/**
 * The degree a program awards.
 *
 * <p>Mirrors {@code CK_programs_degree_type} on
 * {@code dbo.programs.degree_type}.</p>
 */
public enum DegreeType {

    BACHELOR("Bachelor"),
    MASTER("Master");

    private final String label;

    DegreeType(String label) {
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
    public static DegreeType fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
