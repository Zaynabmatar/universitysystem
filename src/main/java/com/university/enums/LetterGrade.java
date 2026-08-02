package com.university.enums;

import java.math.BigDecimal;

/**
 * The letter grade and the grade points it is worth.
 *
 * <p>Mirrors {@code CK_grades_letter} on {@code dbo.grades.letter_grade}.
 * The points match {@code CK_grades_points}, which allows 0.00 to 4.00.</p>
 */
public enum LetterGrade {

    A("A", new BigDecimal("4.00")),
    B("B", new BigDecimal("3.00")),
    C("C", new BigDecimal("2.00")),
    D("D", new BigDecimal("1.00")),
    F("F", new BigDecimal("0.00"));

    private final String label;
    private final BigDecimal gradePoints;

    LetterGrade(String label, BigDecimal gradePoints) {
        this.label = label;
        this.gradePoints = gradePoints;
    }

    /** The text shown in the user interface. */
    public String getLabel() {
        return label;
    }

    /** The value written to {@code grades.grade_points}. */
    public BigDecimal getGradePoints() {
        return gradePoints;
    }

    /** True for every grade except {@link #F}. */
    public boolean isPassing() {
        return this != F;
    }

    /** The {@code result_status} that belongs with this letter. */
    public ResultStatus toResultStatus() {
        return isPassing() ? ResultStatus.PASSED : ResultStatus.FAILED;
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
    public static LetterGrade fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
