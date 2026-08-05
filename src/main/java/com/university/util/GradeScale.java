package com.university.util;

import java.math.BigDecimal;
import java.util.List;

/**
 * The ten-step minimum-grade scale offered when configuring a course
 * prerequisite. Kept separate from {@link com.university.enums.LetterGrade}
 * (the five whole letters a grade is actually recorded as): a prerequisite
 * threshold is an admin's policy choice, not a mark a student earned, and
 * the finer +/- steps let that choice be precise.
 */
public final class GradeScale {

    private record Step(String letter, BigDecimal points) { }

    private static final List<Step> STEPS = List.of(
            new Step("D",  new BigDecimal("1.00")),
            new Step("D+", new BigDecimal("1.30")),
            new Step("C-", new BigDecimal("1.70")),
            new Step("C",  new BigDecimal("2.00")),
            new Step("C+", new BigDecimal("2.30")),
            new Step("B-", new BigDecimal("2.70")),
            new Step("B",  new BigDecimal("3.00")),
            new Step("B+", new BigDecimal("3.30")),
            new Step("A-", new BigDecimal("3.70")),
            new Step("A",  new BigDecimal("4.00"))
    );

    /** The default threshold a new prerequisite starts with — a D or better. */
    public static final String DEFAULT_LABEL = format(STEPS.get(0));

    private GradeScale() { }

    /** Dropdown items, e.g. "D (1.00)" through "A (4.00)". */
    public static List<String> labels() {
        return STEPS.stream().map(GradeScale::format).toList();
    }

    /** Parses a dropdown item, e.g. "C (2.00)", back into its grade points. */
    public static BigDecimal pointsFor(String label) {
        return STEPS.stream()
                .filter(s -> format(s).equals(label))
                .findFirst()
                .map(step -> step.points())
                .orElseGet(() -> STEPS.get(0).points());
    }

    /** The dropdown label whose points are closest to (at or below) the given value. */
    public static String labelFor(BigDecimal points) {
        if (points == null) return DEFAULT_LABEL;
        Step closest = STEPS.get(0);
        for (Step s : STEPS) {
            if (s.points().compareTo(points) <= 0) closest = s;
        }
        return format(closest);
    }

    /** "2.00 (C)" — the minimum grade shown in a way a human understands. */
    public static String display(BigDecimal points) {
        if (points == null) return display(STEPS.get(0).points());
        Step closest = STEPS.get(0);
        for (Step s : STEPS) {
            if (s.points().compareTo(points) <= 0) closest = s;
        }
        return points.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " (" + closest.letter() + ")";
    }

    private static String format(Step s) {
        return s.letter() + " (" + s.points().toPlainString() + ")";
    }
}
