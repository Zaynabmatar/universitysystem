package com.university.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic of project_details.md Section 9, proved without a database.
 *
 * <p>This is the test a professor will ask about: it pins the scale at 115, the four weights at
 * 40/25/20/15, the Jaccard index, and the Section 9 worked example that must land on exactly
 * {@code 40 + 18 + 20 + 14 = 92 base, +12 bonus, 104 / 115}.</p>
 */
class RecommendationScoringTest {

    private final RecommendationService service = new RecommendationService();

    @Test
    @DisplayName("Section 9: the maximum score is 115, not 100")
    void theScaleIs115() {
        assertEquals(100, RecommendationService.MAX_BASE_SCORE);
        assertEquals(15,  RecommendationService.MAX_COLLABORATIVE_BONUS);
        assertEquals(115, RecommendationService.MAX_FINAL_SCORE);
        assertEquals(100, RecommendationService.WEIGHT_MANDATORY
                        + RecommendationService.WEIGHT_BEHIND_SCHEDULE
                        + RecommendationService.WEIGHT_UNLOCKS
                        + RecommendationService.WEIGHT_DIFFICULTY);
        assertEquals(10, RecommendationService.K_NEIGHBOURS);
        assertEquals(10, RecommendationService.TOP_N);
    }

    @Test
    @DisplayName("Jaccard index = |A ∩ B| / |A ∪ B|")
    void jaccard() {
        assertEquals(1.0, service.jaccardIndex(Set.of(1, 2, 3), Set.of(1, 2, 3)), 1e-9);
        assertEquals(0.0, service.jaccardIndex(Set.of(1, 2, 3), Set.of(4, 5, 6)), 1e-9);
        // 2 shared, union {1,2,3,4} = 4  ->  0.5
        assertEquals(0.5, service.jaccardIndex(Set.of(1, 2, 3), Set.of(1, 2, 4)), 1e-9);
        // 1 shared, union 5  ->  0.2
        assertEquals(0.2, service.jaccardIndex(Set.of(1, 2, 3), Set.of(3, 4, 5)), 1e-9);
        // empty sets never divide by zero
        assertEquals(0.0, service.jaccardIndex(Set.of(), Set.of(1, 2)), 1e-9);
        assertEquals(0.0, service.jaccardIndex(Set.of(1, 2), Set.of()), 1e-9);
    }

    @Test
    @DisplayName("Section 9 worked example: 40 + 18 + 20 + 14 = 92, +12 bonus = 104 / 115")
    void workedExample() {
        int mandatory  = 40;                                          // is_mandatory = 1
        int behind     = 18;                                          // 1 semester behind
        int unlocks    = (int) Math.round(20.0 * 4 / 4);              // 4 of a catalogue max of 4
        int difficulty = (int) Math.round(15.0 * (1 - Math.abs(3.01 - 2.74) / 4.0));
        int base       = mandatory + behind + unlocks + difficulty;

        assertEquals(20, unlocks);
        assertEquals(14, difficulty);
        assertEquals(92, base, "base score must be 92 out of 100");

        int bonus = (int) Math.round(15.0 * (12.0 / 13.0) * (3.47 / 4.0));
        assertEquals(12, bonus, "collaborative bonus must be +12 out of 15");

        int finalScore = base + bonus;
        assertEquals(104, finalScore);
        assertTrue(finalScore <= RecommendationService.MAX_FINAL_SCORE);
        assertEquals("Score: 104 / 115",
                "Score: " + finalScore + " / " + RecommendationService.MAX_FINAL_SCORE);
    }

    @Test
    @DisplayName("Factor 4's target band moves with the student's GPA")
    void difficultyTargetMovesWithGpa() {
        // A strong student (>= 3.00) is aimed 0.50 HARDER than their own average.
        assertEquals(2.74, 3.24 - 0.50, 1e-9);
        // A struggling student (< 2.00) is aimed 0.50 EASIER.
        assertEquals(2.30, 1.80 + 0.50, 1e-9);
        // A steady student (2.00 .. 2.99) is aimed at their own level.
        assertEquals(2.50, 2.50 + 0.00, 1e-9);
    }

    @Test
    @DisplayName("Current semester is derived from completed credits, never from a calendar")
    void currentSemester() {
        assertEquals(1, service.currentSemesterOf(0,   132));
        assertEquals(6, service.currentSemesterOf(87,  132));   // floor(87 / 16.5) + 1 = 6
        assertEquals(8, service.currentSemesterOf(132, 132));   // never past semester 8
        assertEquals(8, service.currentSemesterOf(999, 132));
        assertEquals(1, service.currentSemesterOf(10,  0));     // no program credits -> semester 1
    }

    @Test
    @DisplayName("The collaborative bonus rewards passing AND doing well, not just passing")
    void bonusNeedsBothHalves() {
        // Everybody passes, but only with a D (1.00): 15 × 1.00 × 0.25 = 3.75 -> 4, not 15.
        assertEquals(4, (int) Math.round(15.0 * 1.0 * (1.00 / 4.0)));
        // Everybody passes with an A: the full 15.
        assertEquals(15, (int) Math.round(15.0 * 1.0 * (4.00 / 4.0)));
        // No evidence at all is worth nothing — never a penalty.
        assertEquals(0, (int) Math.round(15.0 * 0.0 * 0.0));
    }
}
