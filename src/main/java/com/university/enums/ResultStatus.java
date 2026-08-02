package com.university.enums;

/**
 * The pass or fail wording shown next to the numeric total.
 *
 * <p>Mirrors {@code CK_grades_result} on {@code dbo.grades.result_status}.</p>
 */
public enum ResultStatus {

    PASSED("Passed"),
    FAILED("Failed");

    private final String label;

    ResultStatus(String label) {
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
    public static ResultStatus fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
