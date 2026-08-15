package com.university.service;

import com.university.service.RecommendationService.Candidate;
import com.university.service.RecommendationService.Meeting;
import com.university.service.RecommendationService.PassedCourse;
import com.university.service.RecommendationService.PlanEntry;
import com.university.service.RecommendationService.StudentProfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Course Recommendation must list only courses the student still needs and can register for
 * right now. These prove the underlying rules — {@code isEligible}'s R3-R8 filter and the
 * Plan-of-Study priority tier — directly, without a database.
 */
class RecommendationEligibilityTest {

    private final RecommendationService service = new RecommendationService();

    private static StudentProfile student(int currentSemester, int currentCredits, int creditLimit) {
        StudentProfile me = new StudentProfile();
        me.studentId = 1;
        me.currentSemester = currentSemester;
        me.currentCredits = currentCredits;
        me.creditLimit = creditLimit;
        me.completedCredits = 0;
        me.programName = "Test Program";
        me.gpa = BigDecimal.ZERO;
        return me;
    }

    private static Candidate candidate(int sectionId, int courseId, int capacity, int enrolledCount) {
        Candidate c = new Candidate();
        c.sectionId = sectionId;
        c.courseId = courseId;
        c.credits = 3;
        c.capacity = capacity;
        c.enrolledCount = enrolledCount;
        c.courseCode = "CS" + courseId;
        c.courseTitle = "Course " + courseId;
        c.sectionNumber = "1";
        return c;
    }

    private static Map<Integer, List<Meeting>> scheduledFor(int sectionId) {
        Meeting m = new Meeting();
        m.sectionId = sectionId;
        m.day = "MON";
        m.start = LocalTime.of(9, 0);
        m.end = LocalTime.of(10, 0);
        Map<Integer, List<Meeting>> map = new HashMap<>();
        map.put(sectionId, List.of(m));
        return map;
    }

    @Test
    @DisplayName("Already enrolled in the course (any section) -> never eligible, checked by course not section")
    void alreadyEnrolledCourseNeverRecommended() {
        StudentProfile me = student(4, 12, 18);
        Candidate otherSection = candidate(202, 501, 30, 5);   // course 501, a DIFFERENT section
        Set<Integer> enrolledNow = Set.of(501);                // enrolled in course 501 (section 1)

        boolean eligible = service.isEligible(otherSection, me,
                Map.of(), Map.of(), enrolledNow, List.of(),
                scheduledFor(202), Map.of());

        assertFalse(eligible, "a second section of a course the student is already in must not be recommended");
        assertEquals("you have already passed or are currently enrolled in it", otherSection.blockedReason);
    }

    @Test
    @DisplayName("A previously failed required course with an open section is recommended, at the top priority tier")
    void failedRequiredCourseIsRecommendedAndTopPriority() {
        StudentProfile me = student(4, 12, 18);
        Candidate section = candidate(300, 600, 30, 10);       // open, seats free
        // no passed grade for 600 on file -> the student failed it and has not retaken it

        boolean eligible = service.isEligible(section, me,
                Map.of(), Map.of(), Set.of(), List.of(),
                scheduledFor(300), Map.of());
        assertTrue(eligible, "an open section of a previously failed required course must be recommendable");

        PlanEntry entry = new PlanEntry();
        entry.courseId = 600;
        entry.isMandatory = true;
        entry.recommendedSemester = 2;                          // due back in semester 2; student is now in 4

        assertEquals(3, service.planPriorityTier(entry, me),
                "an overdue repeat/old required course must outrank a current-semester one");
    }

    @Test
    @DisplayName("A course required by the current study-plan semester, with an open section, is recommended")
    void currentSemesterRequiredCourseIsRecommended() {
        StudentProfile me = student(4, 12, 18);
        Candidate section = candidate(301, 601, 30, 10);

        boolean eligible = service.isEligible(section, me,
                Map.of(), Map.of(), Set.of(), List.of(),
                scheduledFor(301), Map.of());
        assertTrue(eligible);

        PlanEntry entry = new PlanEntry();
        entry.courseId = 601;
        entry.isMandatory = true;
        entry.recommendedSemester = 4;                          // due exactly this semester

        assertEquals(2, service.planPriorityTier(entry, me),
                "a current-semester requirement must rank below an overdue repeat, above a future one");
    }

    @Test
    @DisplayName("A course the student already passed is never recommended, even with an open section")
    void alreadyPassedCourseNeverRecommended() {
        StudentProfile me = student(4, 12, 18);
        Candidate section = candidate(302, 602, 30, 10);
        PassedCourse passed = new PassedCourse();
        passed.courseId = 602;
        passed.letterGrade = "B";
        passed.points = new BigDecimal("3.00");
        Map<Integer, PassedCourse> myPassed = Map.of(602, passed);

        boolean eligible = service.isEligible(section, me,
                Map.of(), myPassed, Set.of(), List.of(),
                scheduledFor(302), Map.of());

        assertFalse(eligible);
        assertEquals("you have already passed or are currently enrolled in it", section.blockedReason);
    }

    @Test
    @DisplayName("A course whose only open section is full (closed) is never recommended")
    void closedSectionNeverRecommended() {
        StudentProfile me = student(4, 12, 18);
        Candidate full = candidate(303, 603, 20, 20);           // enrolledCount == capacity

        boolean eligible = service.isEligible(full, me,
                Map.of(), Map.of(), Set.of(), List.of(),
                scheduledFor(303), Map.of());

        assertFalse(eligible);
        assertEquals("all of its open sections are currently full", full.blockedReason);
    }
}
