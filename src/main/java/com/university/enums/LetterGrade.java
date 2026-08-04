package com.university.enums;

import java.math.BigDecimal;

/**
 * The letter grade and the grade points it is worth.
 *
 * <p>Mirrors {@code CK_grades_letter} on {@code dbo.grades.letter_grade}:
 * project_details.md Section 5.2's eleven bands (A down to F) plus the two
 * special letters {@code W} (withdrawn) and {@code I} (incomplete), neither
 * of which counts towards the GPA. Java identifiers cannot contain {@code +}
 * or {@code -}, so the constant names spell them out (e.g. {@link #A_MINUS})
 * while {@link #getLabel()} / {@link #toDb()} return the real two-character
 * string the database and the UI use.</p>
 */
public enum LetterGrade {

    A      ("A",  "4.00"),
    A_MINUS("A-", "3.70"),
    B_PLUS ("B+", "3.30"),
    B      ("B",  "3.00"),
    B_MINUS("B-", "2.70"),
    C_PLUS ("C+", "2.30"),
    C      ("C",  "2.00"),
    C_MINUS("C-", "1.70"),
    D_PLUS ("D+", "1.30"),
    D      ("D",  "1.00"),
    F      ("F",  "0.00"),
    W      ("W",  "0.00"),
    I      ("I",  "0.00");

    /** Section 5.2: "Passing = D (1.00 points) or higher." */
    private static final BigDecimal PASS_THRESHOLD = new BigDecimal("1.00");

    private final String label;
    private final BigDecimal gradePoints;

    LetterGrade(String label, String gradePoints) {
        this.label = label;
        this.gradePoints = new BigDecimal(gradePoints);
    }

    /** The text shown in the user interface — the real letter, e.g. "A-". */
    public String getLabel() {
        return label;
    }

    /** The value written to {@code grades.grade_points}. W and I read 0.00; see {@link #countsInGpa()}. */
    public BigDecimal getGradePoints() {
        return gradePoints;
    }

    /** Section 5.2: passing is D or better. F, W and I are not passes. */
    public boolean isPassing() {
        return this != F && this != W && this != I && gradePoints.compareTo(PASS_THRESHOLD) >= 0;
    }

    /** Section 5.2: W and I are "not counted in GPA". An F counts, at 0.00 points. */
    public boolean countsInGpa() {
        return this != W && this != I;
    }

    /**
     * The {@code result_status} that belongs with this letter.
     *
     * @return null for {@link #W} and {@link #I} — neither passed nor failed, and
     *         {@code result_status} is nullable for exactly this reason
     */
    public ResultStatus toResultStatus() {
        if (this == W || this == I) {
            return null;
        }
        return isPassing() ? ResultStatus.PASSED : ResultStatus.FAILED;
    }

    /** The exact string stored in the database, e.g. "B+". Not {@code name()} — that would be "B_PLUS". */
    public String toDb() {
        return label;
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static LetterGrade fromDb(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toUpperCase();
        for (LetterGrade grade : values()) {
            if (grade.label.equals(trimmed)) {
                return grade;
            }
        }
        throw new IllegalArgumentException("Unknown letter grade: " + value);
    }

    @Override
    public String toString() {
        return label;
    }
}
