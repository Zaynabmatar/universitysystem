package com.university.util;

import com.university.enums.LetterGrade;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * project_details.md Section 5.1, 5.2 and 5.3 — the ONLY place a mark becomes a total, a total
 * becomes a letter, and grade points become a GPA.
 *
 * <p>Pure: no database, no JavaFX, no state. That is what makes it unit-testable, and what makes
 * the Section 5.3 worked example (25.90 quality points over 8 credits = 3.24) provable with
 * {@code mvn test} rather than by hand.</p>
 */
public final class GradeCalculator {

    private GradeCalculator() {
    }

    // ---- Section 5.1: the weights ---------------------------------------
    public static final BigDecimal COURSEWORK_WEIGHT = new BigDecimal("0.30");
    public static final BigDecimal MIDTERM_WEIGHT = new BigDecimal("0.30");
    public static final BigDecimal FINAL_WEIGHT = new BigDecimal("0.40");

    /**
     * Weights for a course with a lab component ({@code courses.has_lab}). Coursework and
     * Midterm are trimmed to make room for Lab; Final is unaffected. Applies to any course
     * flagged as having a lab, never one hardcoded course.
     */
    public static final BigDecimal LAB_COURSEWORK_WEIGHT = new BigDecimal("0.15");
    public static final BigDecimal LAB_MIDTERM_WEIGHT = new BigDecimal("0.15");
    public static final BigDecimal LAB_WEIGHT = new BigDecimal("0.20");
    public static final BigDecimal LAB_FINAL_WEIGHT = new BigDecimal("0.50");

    /** One row of the Section 5.2 conversion table, highest band first. */
    private record Band(BigDecimal minInclusive, LetterGrade letter) {
    }

    /**
     * project_details.md Section 5.2, highest band first.
     *
     * <p>The spec writes the bands as integer ranges (95-100, 90-94, ...). Marks are
     * DECIMAL(5,2), so a total of 94.50 must land somewhere. The rule used here — walk the bands
     * from highest to lowest and return the first whose lower bound the total is
     * greater-than-or-equal-to — reproduces the published table exactly for whole numbers and
     * resolves fractions upward-band-safe: 94.99 -&gt; A-, 95.00 -&gt; A.</p>
     */
    private static final Band[] SCALE = {
            new Band(new BigDecimal("95"), LetterGrade.A),
            new Band(new BigDecimal("90"), LetterGrade.A_MINUS),
            new Band(new BigDecimal("85"), LetterGrade.B_PLUS),
            new Band(new BigDecimal("80"), LetterGrade.B),
            new Band(new BigDecimal("75"), LetterGrade.B_MINUS),
            new Band(new BigDecimal("70"), LetterGrade.C_PLUS),
            new Band(new BigDecimal("65"), LetterGrade.C),
            new Band(new BigDecimal("60"), LetterGrade.C_MINUS),
            new Band(new BigDecimal("55"), LetterGrade.D_PLUS),
            new Band(new BigDecimal("50"), LetterGrade.D),
            new Band(BigDecimal.ZERO, LetterGrade.F),
    };

    // =====================================================================
    // Section 5.1 — total_mark = 0.30*CW + 0.30*MT + 0.40*F
    // =====================================================================

    /**
     * @return the weighted total for a course with no lab component, rounded HALF_UP to 2
     *         decimal places, or null if any component is still missing — a missing mark means
     *         "not marked yet", never zero.
     */
    public static BigDecimal totalMark(BigDecimal coursework, BigDecimal midterm, BigDecimal finalMark) {
        return totalMark(coursework, midterm, null, finalMark, false);
    }

    /**
     * @param lab    the lab mark; ignored when {@code hasLab} is false, required when it is true
     * @param hasLab whether this course has a lab component ({@code courses.has_lab}) — decides
     *               which weighting is applied, never which single course it is
     * @return the weighted total, rounded HALF_UP to 2 decimal places, or null if any required
     *         component is still missing — a missing mark means "not marked yet", never zero.
     */
    public static BigDecimal totalMark(BigDecimal coursework, BigDecimal midterm, BigDecimal lab,
                                        BigDecimal finalMark, boolean hasLab) {
        if (coursework == null || midterm == null || finalMark == null || (hasLab && lab == null)) {
            return null;
        }
        if (hasLab) {
            return coursework.multiply(LAB_COURSEWORK_WEIGHT)
                    .add(midterm.multiply(LAB_MIDTERM_WEIGHT))
                    .add(lab.multiply(LAB_WEIGHT))
                    .add(finalMark.multiply(LAB_FINAL_WEIGHT))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return coursework.multiply(COURSEWORK_WEIGHT)
                .add(midterm.multiply(MIDTERM_WEIGHT))
                .add(finalMark.multiply(FINAL_WEIGHT))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // =====================================================================
    // Section 5.2 — mark -> letter
    // =====================================================================

    /**
     * @return the letter the total earns; never {@link LetterGrade#W} or {@link LetterGrade#I} —
     *         those are set outside the marking process — or null when there is no total yet
     */
    public static LetterGrade letterGrade(BigDecimal totalMark) {
        if (totalMark == null) {
            return null;
        }
        for (Band band : SCALE) {
            if (totalMark.compareTo(band.minInclusive()) >= 0) {
                return band.letter();
            }
        }
        return LetterGrade.F; // negative marks cannot occur, but never return null here
    }

    /** True when the mark is a legal 0-100 value (rule G3). */
    public static boolean isValidMark(BigDecimal mark) {
        return mark != null
                && mark.compareTo(BigDecimal.ZERO) >= 0
                && mark.compareTo(new BigDecimal("100")) <= 0;
    }

    // =====================================================================
    // Section 5.3 — the GPA formula itself
    // =====================================================================

    /**
     * GPA = Sigma(grade_points x credits) / Sigma(credits), rounded HALF_UP to 2 decimal places.
     *
     * @param qualityPoints Sigma(grade_points x credits)
     * @param credits       Sigma(credits) — only credits that count in the GPA
     * @return the GPA, or 0.00 when there are no credits at all
     */
    public static BigDecimal gpa(BigDecimal qualityPoints, BigDecimal credits) {
        if (qualityPoints == null || credits == null || credits.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return qualityPoints.divide(credits, 2, RoundingMode.HALF_UP);
    }

    /** Formats any GPA for display: always two decimals, e.g. "3.24". */
    public static String formatGpa(BigDecimal gpa) {
        return (gpa == null ? BigDecimal.ZERO : gpa).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
