package com.university.util;

import com.university.enums.LetterGrade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * project_details.md Section 5, proved rather than asserted in a comment.
 *
 * <p>The scale lives in {@link GradeCalculator} and the points live on {@link LetterGrade}; both
 * are pure, so the whole of Section 5 can be checked without a database, a semester or a running
 * application. If {@link #section5_3_workedExample_producesExactly_3_24()} ever fails, the
 * grading scale is wrong — fix the calculator, never the test.</p>
 */
class GradeCalculatorTest {

    // ---- Section 5.1 : the weighted total ------------------------------

    @Test
    @DisplayName("Section 5.1 — total = 0.30*coursework + 0.30*midterm + 0.40*final")
    void weightedTotalMatchesTheSpecFormula() {
        // 0.30*80 + 0.30*90 + 0.40*95 = 24.00 + 27.00 + 38.00 = 89.00
        assertEquals(new BigDecimal("89.00"), GradeCalculator.totalMark(
                new BigDecimal("80"), new BigDecimal("90"), new BigDecimal("95")));
    }

    @Test
    @DisplayName("A missing mark means 'not marked yet', never zero")
    void aMissingMarkLeavesTheTotalNull() {
        assertNull(GradeCalculator.totalMark(new BigDecimal("80"), new BigDecimal("90"), null));
        assertNull(GradeCalculator.totalMark(new BigDecimal("80"), null, new BigDecimal("95")));
        assertNull(GradeCalculator.totalMark(null, new BigDecimal("90"), new BigDecimal("95")));
        assertNull(GradeCalculator.letterGrade(null));
    }

    // ---- Section 5.2 : every band, at both ends -------------------------

    @Test
    @DisplayName("Section 5.2 — all eleven bands, at their lower and upper bound")
    void everyBandOfTheConversionTable() {
        assertEquals(LetterGrade.A, GradeCalculator.letterGrade(new BigDecimal("100.00")));
        assertEquals(LetterGrade.A, GradeCalculator.letterGrade(new BigDecimal("95.00")));
        assertEquals(LetterGrade.A_MINUS, GradeCalculator.letterGrade(new BigDecimal("94.00")));
        assertEquals(LetterGrade.A_MINUS, GradeCalculator.letterGrade(new BigDecimal("90.00")));
        assertEquals(LetterGrade.B_PLUS, GradeCalculator.letterGrade(new BigDecimal("89.00")));
        assertEquals(LetterGrade.B_PLUS, GradeCalculator.letterGrade(new BigDecimal("85.00")));
        assertEquals(LetterGrade.B, GradeCalculator.letterGrade(new BigDecimal("84.00")));
        assertEquals(LetterGrade.B, GradeCalculator.letterGrade(new BigDecimal("80.00")));
        assertEquals(LetterGrade.B_MINUS, GradeCalculator.letterGrade(new BigDecimal("79.00")));
        assertEquals(LetterGrade.B_MINUS, GradeCalculator.letterGrade(new BigDecimal("75.00")));
        assertEquals(LetterGrade.C_PLUS, GradeCalculator.letterGrade(new BigDecimal("74.00")));
        assertEquals(LetterGrade.C_PLUS, GradeCalculator.letterGrade(new BigDecimal("70.00")));
        assertEquals(LetterGrade.C, GradeCalculator.letterGrade(new BigDecimal("69.00")));
        assertEquals(LetterGrade.C, GradeCalculator.letterGrade(new BigDecimal("65.00")));
        assertEquals(LetterGrade.C_MINUS, GradeCalculator.letterGrade(new BigDecimal("64.00")));
        assertEquals(LetterGrade.C_MINUS, GradeCalculator.letterGrade(new BigDecimal("60.00")));
        assertEquals(LetterGrade.D_PLUS, GradeCalculator.letterGrade(new BigDecimal("59.00")));
        assertEquals(LetterGrade.D_PLUS, GradeCalculator.letterGrade(new BigDecimal("55.00")));
        assertEquals(LetterGrade.D, GradeCalculator.letterGrade(new BigDecimal("54.00")));
        assertEquals(LetterGrade.D, GradeCalculator.letterGrade(new BigDecimal("50.00")));
        assertEquals(LetterGrade.F, GradeCalculator.letterGrade(new BigDecimal("49.00")));
        assertEquals(LetterGrade.F, GradeCalculator.letterGrade(new BigDecimal("0.00")));
    }

    @Test
    @DisplayName("The boundary rule — bands are lower-bound inclusive, so 89.5 is a B+")
    void bandsAreLowerBoundInclusive() {
        assertEquals(LetterGrade.B_PLUS, GradeCalculator.letterGrade(new BigDecimal("89.99")));
        assertEquals(LetterGrade.A_MINUS, GradeCalculator.letterGrade(new BigDecimal("90.00")));
        assertEquals(LetterGrade.A_MINUS, GradeCalculator.letterGrade(new BigDecimal("94.99")));
        assertEquals(LetterGrade.A, GradeCalculator.letterGrade(new BigDecimal("95.00")));
        assertEquals(LetterGrade.F, GradeCalculator.letterGrade(new BigDecimal("49.99")));
        assertEquals(LetterGrade.D, GradeCalculator.letterGrade(new BigDecimal("50.00")));
    }

    @Test
    @DisplayName("Section 5.2 — all eleven point values")
    void pointsMatchTheConversionTable() {
        assertEquals(new BigDecimal("4.00"), LetterGrade.A.getGradePoints());
        assertEquals(new BigDecimal("3.70"), LetterGrade.A_MINUS.getGradePoints());
        assertEquals(new BigDecimal("3.30"), LetterGrade.B_PLUS.getGradePoints());
        assertEquals(new BigDecimal("3.00"), LetterGrade.B.getGradePoints());
        assertEquals(new BigDecimal("2.70"), LetterGrade.B_MINUS.getGradePoints());
        assertEquals(new BigDecimal("2.30"), LetterGrade.C_PLUS.getGradePoints());
        assertEquals(new BigDecimal("2.00"), LetterGrade.C.getGradePoints());
        assertEquals(new BigDecimal("1.70"), LetterGrade.C_MINUS.getGradePoints());
        assertEquals(new BigDecimal("1.30"), LetterGrade.D_PLUS.getGradePoints());
        assertEquals(new BigDecimal("1.00"), LetterGrade.D.getGradePoints());
        assertEquals(new BigDecimal("0.00"), LetterGrade.F.getGradePoints());
    }

    @Test
    @DisplayName("Passing starts at D; W and I never pass and never count")
    void passingStartsAtD_andWandIneverPass() {
        assertTrue(LetterGrade.D.isPassing());
        assertFalse(LetterGrade.F.isPassing());
        assertFalse(LetterGrade.W.isPassing());
        assertFalse(LetterGrade.I.isPassing());

        assertFalse(LetterGrade.W.countsInGpa());
        assertFalse(LetterGrade.I.countsInGpa());
        assertTrue(LetterGrade.F.countsInGpa()); // an F DOES count, at 0.00
    }

    @Test
    @DisplayName("Rule G3 — a mark is legal only between 0 and 100")
    void marksOutsideZeroToHundredAreRejected() {
        assertTrue(GradeCalculator.isValidMark(new BigDecimal("0")));
        assertTrue(GradeCalculator.isValidMark(new BigDecimal("100")));
        assertTrue(GradeCalculator.isValidMark(new BigDecimal("87.50")));
        assertFalse(GradeCalculator.isValidMark(new BigDecimal("150")));
        assertFalse(GradeCalculator.isValidMark(new BigDecimal("-5")));
        assertFalse(GradeCalculator.isValidMark(null));
    }

    // ---- Section 5.3 : THE WORKED EXAMPLE. This must print 3.24. -------

    @Test
    @DisplayName("Section 5.3 worked example — 25.90 quality points over 8 credits is exactly 3.24")
    void section5_3_workedExample_producesExactly_3_24() {
        // CS201   3 credits  B+  3.30  ->  9.90
        // MATH201 3 credits  A   4.00  -> 12.00
        // ENG101  2 credits  C   2.00  ->  4.00
        // Total   8 credits            -> 25.90   GPA = 25.90 / 8 = 3.2375 -> 3.24 (HALF_UP)
        BigDecimal qualityPoints = LetterGrade.B_PLUS.getGradePoints().multiply(new BigDecimal("3"))
                .add(LetterGrade.A.getGradePoints().multiply(new BigDecimal("3")))
                .add(LetterGrade.C.getGradePoints().multiply(new BigDecimal("2")));

        assertEquals(new BigDecimal("25.90"), qualityPoints.setScale(2));

        BigDecimal gpa = GradeCalculator.gpa(qualityPoints, new BigDecimal("8"));
        assertEquals(new BigDecimal("3.24"), gpa);
        assertEquals("3.24", GradeCalculator.formatGpa(gpa));
    }

    @Test
    @DisplayName("A student with no credits has a GPA of 0.00, not a divide-by-zero")
    void gpaOfAStudentWithNoCreditsIsZeroAndDoesNotDivideByZero() {
        assertEquals(new BigDecimal("0.00"), GradeCalculator.gpa(BigDecimal.ZERO, BigDecimal.ZERO));
        assertEquals(new BigDecimal("0.00"), GradeCalculator.gpa(null, null));
        assertEquals("0.00", GradeCalculator.formatGpa(null));
    }

    @Test
    @DisplayName("The GPA is rounded HALF_UP to two decimals, never truncated")
    void gpaRoundsHalfUp() {
        // 3.2375 -> 3.24, not 3.23
        assertEquals(new BigDecimal("3.24"),
                GradeCalculator.gpa(new BigDecimal("25.90"), new BigDecimal("8")));
        // 2.005 -> 2.01
        assertEquals(new BigDecimal("2.01"),
                GradeCalculator.gpa(new BigDecimal("4.01"), new BigDecimal("2")));
    }
}
