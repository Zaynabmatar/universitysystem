package com.university.service;

import com.university.dao.TranscriptDAO;
import com.university.enums.LetterGrade;
import com.university.util.GradeCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds the academic transcript and the degree-progress report for one student.
 *
 * <p>Design rules — phase-12/context/TRANSCRIPT_RULES.md:</p>
 * <ul>
 *   <li>{@link TranscriptDAO} decides <em>which</em> rows appear (never a DROPPED one,
 *       never an unsubmitted mark) and what a course's status is.</li>
 *   <li>This class only GROUPS and TOTALS them, and asks {@link GradeCalculator} to divide.</li>
 *   <li>There is <b>no GPA formula anywhere in this file</b>, and no JavaFX and no PDF import —
 *       so the screen and the paper can never disagree, and neither can this class and
 *       {@code GradeDAO}'s cumulative figure.</li>
 * </ul>
 *
 * <p><b>Adaptation note.</b> The phase document is written against
 * {@code vw_StudentTranscript}, {@code vw_StudentGPASummary} and
 * {@code sp_GetStudentDegreeProgress}. This database has no views and no stored
 * procedures — every rule was implemented in Java from Phase 09 onward — so those
 * three reads live in {@link TranscriptDAO} instead, with the same columns, the same
 * ordering and the same GPA filter that {@code GradeDAO.gpaQuery} already uses.</p>
 */
public class TranscriptService {

    /** Section 6.9 condition 3: a degree needs a cumulative average of at least this. */
    public static final BigDecimal GRADUATION_MIN_GPA = new BigDecimal("2.00");

    private final TranscriptDAO transcriptDao = new TranscriptDAO();

    /* ================================================================
       1. ONE LINE OF THE TRANSCRIPT
       ================================================================ */
    public static class TranscriptRow {
        public int        semesterId;
        public String     semesterName;
        public String     academicYear;
        public String     term;
        public LocalDate  semesterStart;
        public int        courseId;
        public String     courseCode;
        public String     courseTitle;
        public int        credits;
        public String     sectionNumber;
        public String     instructorName;
        public int        enrollmentId;
        public String     enrollmentStatus;   // ENROLLED / WITHDRAWN / COMPLETED
        public boolean    isRepeat;
        public boolean    countsInGpa;
        public BigDecimal totalMark;          // null = not graded yet
        public String     letterGrade;        // null = not graded yet
        public BigDecimal gradePoints;        // null = not graded yet
        public BigDecimal qualityPoints;      // gradePoints x credits, null if not graded

        /**
         * Exactly the WHERE clause the database uses for the cumulative GPA
         * ({@code GradeDAO.gpaQuery}), expressed in Java. If these two ever disagree the
         * running cumulative under the last term stops matching
         * {@code students.cumulative_gpa}, which is the one number the checklist stars.
         */
        public boolean countsTowardsGpa() {
            return "COMPLETED".equals(enrollmentStatus)
                && countsInGpa
                && gradePoints != null
                && letterGrade != null
                && !"W".equals(letterGrade)
                && !"I".equals(letterGrade);
        }

        /**
         * The label shown in the Note column — the row's real status, derived from the
         * enrollment status and the submitted letter grade (never from {@code gradePoints == 0},
         * which W, I and F all share and so cannot tell apart).
         */
        public String displayNote() {
            if ("ENROLLED".equals(enrollmentStatus))                  return "In Progress";
            if ("WITHDRAWN".equals(enrollmentStatus)
                    || "W".equals(letterGrade))                       return "Withdrawn";
            if ("I".equals(letterGrade))                              return "Incomplete";
            if (letterGrade != null)                                  return LetterGrade.fromDb(letterGrade).isPassing()
                    ? "Passed" : "Failed";
            return "—";
        }

        /** Section 5.5: an older, completed attempt whose GPA contribution was retired by a repeat. */
        public boolean isSupersededRepeat() {
            return "COMPLETED".equals(enrollmentStatus) && !countsInGpa;
        }

        public boolean isInProgress() {
            return "ENROLLED".equals(enrollmentStatus);
        }

        public String markText()    { return totalMark   == null ? "—" : totalMark.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
        public String letterText()  { return letterGrade == null ? "—" : letterGrade; }
        public String pointsText()  { return gradePoints == null ? "—" : GradeCalculator.formatGpa(gradePoints); }
        public String creditsText() { return String.valueOf(credits); }

        /** "CS301-01", or just the code when the section number is missing. */
        public String courseLabel() {
            return sectionNumber == null || sectionNumber.isBlank()
                    ? courseCode
                    : courseCode + "-" + sectionNumber;
        }
    }

    /* ================================================================
       2. ONE SEMESTER BLOCK
       ================================================================ */
    public static class TermBlock {
        public int       semesterId;
        public String    semesterName;
        public String    academicYear;
        public String    term;
        public LocalDate startDate;
        public final List<TranscriptRow> rows = new ArrayList<>();

        // this term only
        public int        termCredits;
        public BigDecimal termQualityPoints = BigDecimal.ZERO;
        public BigDecimal termGpa           = BigDecimal.ZERO;

        // everything up to AND INCLUDING this term (the running cumulative)
        public int        cumulativeCredits;
        public BigDecimal cumulativeQualityPoints = BigDecimal.ZERO;
        public BigDecimal cumulativeGpa           = BigDecimal.ZERO;

        public String heading() {
            return semesterName + "  (" + academicYear + ")";
        }

        /** A term in which nothing counted reads as a dash, never as 0.00. */
        public String termGpaText() {
            return termCredits == 0 ? "—" : GradeCalculator.formatGpa(termGpa);
        }

        public String cumulativeGpaText() {
            return cumulativeCredits == 0 ? "—" : GradeCalculator.formatGpa(cumulativeGpa);
        }

        public String summaryLine() {
            return "Term credits " + termCredits
                 + "     Term GPA " + termGpaText()
                 + "     Cumulative GPA " + cumulativeGpaText();
        }
    }

    /* ================================================================
       3. THE WHOLE TRANSCRIPT
       ================================================================ */
    public static class Transcript {
        public int       studentId;
        /** users.user_id — the Student ID printed on the transcript. */
        public int       studentUserId;
        public String    firstName        = "";
        public String    lastName         = "";
        public String    programName      = "";
        public String    academicStanding = "";
        public LocalDate admissionDate;
        public final List<TermBlock> terms = new ArrayList<>();

        public int           creditsCountedInGpa;                    // credits that actually formed the GPA
        public int           creditsEarned;                          // students.completed_credits (passed only)
        public int           creditsRequired;                        // programs.total_credits_required
        public BigDecimal    cumulativeGpa = BigDecimal.ZERO;        // students.cumulative_gpa (authoritative)
        public LocalDateTime generatedAt   = LocalDateTime.now();

        public String  fullName()          { return (firstName + " " + lastName).trim(); }
        public String  cumulativeGpaText() { return GradeCalculator.formatGpa(cumulativeGpa); }
        public boolean isEmpty()           { return terms.isEmpty(); }

        /** The running cumulative under the last term. Must equal {@link #cumulativeGpa}. */
        public BigDecimal computedCumulativeGpa() {
            return terms.isEmpty() ? BigDecimal.ZERO : terms.get(terms.size() - 1).cumulativeGpa;
        }
    }

    /* ================================================================
       4. DEGREE PROGRESS — the summary
       ================================================================ */
    public static class DegreeProgress {
        public int        studentId;
        /** users.user_id — the Student ID. */
        public int        studentUserId;
        public int        programId;
        public String     fullName      = "";
        public String     programName   = "";
        public int        creditsRequired;
        public int        creditsCompleted;
        public int        creditsRemaining;
        public BigDecimal percentComplete = BigDecimal.ZERO;   // 0.0 .. 100.0, one decimal
        public int        mandatoryTotal;
        public int        mandatoryPassed;
        public int        mandatoryMissing;
        public BigDecimal cumulativeGpa = BigDecimal.ZERO;
        public boolean    canGraduate;
        public final List<RequirementRow> requirements = new ArrayList<>();
        /** {@link #requirements}, grouped by recommended semester, in semester order — what
         *  Degree Progress' "Show Your Study Plan" section actually renders. */
        public final List<SemesterPlan> semesterPlans = new ArrayList<>();

        /* --- Section 6.9, condition by condition, so the UI can show each one --- */
        public boolean allMandatoryPassed() { return mandatoryMissing == 0; }
        public boolean creditsSatisfied()   { return creditsCompleted >= creditsRequired; }
        public boolean gpaSatisfied()       { return cumulativeGpa.compareTo(GRADUATION_MIN_GPA) >= 0; }

        public int outstandingConditions() {
            int n = 0;
            if (!allMandatoryPassed()) n++;
            if (!creditsSatisfied())   n++;
            if (!gpaSatisfied())       n++;
            return n;
        }

        /** A ProgressBar wants 0.0 .. 1.0 and must never be pushed past its end. */
        public double progressFraction() {
            double f = percentComplete.doubleValue() / 100.0;
            return Math.max(0.0, Math.min(1.0, f));
        }

        public String percentText() {
            return percentComplete.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
        }
    }

    /**
     * One course of the Study Plan (Degree Progress' lower section).
     *
     * <p>{@code courseStatus} is one of six values, computed by {@link #buildStudyPlan}
     * from the student's actual enrollments/grades plus the course's own prerequisite
     * and credit-threshold requirements — never stored, never guessed from
     * {@code recommendedSemester} alone:</p>
     * <ul>
     *   <li>{@code PASSED} — a completed, submitted, passing attempt exists (even if an
     *       earlier attempt failed — history is never overwritten, only superseded).</li>
     *   <li>{@code IN_PROGRESS} — an active ENROLLED attempt exists right now (including a
     *       repeat of a previously failed course).</li>
     *   <li>{@code FAILED} — a completed, submitted, failing attempt exists, there is no
     *       later pass, and the student is not currently repeating it.</li>
     *   <li>{@code ELIGIBLE} — not yet attempted, every mandatory prerequisite is
     *       {@code PASSED}, any credit threshold is met, and the student's progression
     *       has reached this course's semester.</li>
     *   <li>{@code LOCKED} — not yet attempted, and a specific, nameable requirement is
     *       unmet (a prerequisite not passed, or a credit threshold not met).</li>
     *   <li>{@code NOT_YET_AVAILABLE} — not yet attempted, nothing specific blocks it, but
     *       it belongs to a later stage of the plan than the student has reached.</li>
     * </ul>
     */
    public static class RequirementRow {
        public int     courseId;
        public String  courseCode;
        public String  courseTitle;
        public int     credits;
        public boolean isMandatory;
        public String  requirementType;       // Mandatory / Program Elective / University Requirement / ...
        public Integer recommendedSemester;   // may be null
        public Integer minCompletedCredits;   // credit-based eligibility threshold, may be null
        public String  prerequisitesText;     // "None", "MATH101 - Calculus I", "A - B, C - D", ...
        public String  courseStatus;          // PASSED / IN_PROGRESS / FAILED / ELIGIBLE / LOCKED / NOT_YET_AVAILABLE

        public String  typeText()     { return requirementType != null ? requirementType : (isMandatory ? "Mandatory" : "Elective"); }
        public String  semesterText() { return recommendedSemester == null ? "—" : "Sem " + recommendedSemester; }
        public String  statusText()   { return courseStatus == null ? "—" : courseStatus.replace('_', ' '); }
        public boolean isPassed()            { return "PASSED".equals(courseStatus); }
        public boolean isInProgress()        { return "IN_PROGRESS".equals(courseStatus); }
        public boolean isFailed()            { return "FAILED".equals(courseStatus); }
        public boolean isEligible()          { return "ELIGIBLE".equals(courseStatus); }
        public boolean isLocked()            { return "LOCKED".equals(courseStatus); }
        public boolean isNotYetAvailable()   { return "NOT_YET_AVAILABLE".equals(courseStatus); }
        public boolean isNotTaken()          { return !isPassed() && !isInProgress(); }
    }

    /** All of one program's curriculum rows that share a {@code recommendedSemester}. */
    public static class SemesterPlan {
        public final int semesterNumber;
        public final List<RequirementRow> courses = new ArrayList<>();

        public SemesterPlan(int semesterNumber) {
            this.semesterNumber = semesterNumber;
        }

        public String heading() {
            return "Semester " + semesterNumber;
        }
    }

    /* ================================================================
       5. BUILD THE TRANSCRIPT
       ================================================================ */

    /**
     * The whole academic record of one student, grouped by semester, oldest first.
     *
     * @throws ServiceException when the student does not exist
     */
    public Transcript getTranscript(int studentId) {
        ValidationException.requireId(studentId, "Student");

        Transcript transcript = transcriptDao.findHeader(studentId)
                .orElseThrow(() -> new ServiceException("That student record was not found."));

        // LinkedHashMap keeps the SQL order, which is semester_start_date — never semester_name.
        Map<Integer, TermBlock> blocks = new LinkedHashMap<>();
        for (TranscriptRow row : transcriptDao.findTranscriptRows(studentId)) {
            TermBlock block = blocks.computeIfAbsent(row.semesterId, id -> {
                TermBlock fresh = new TermBlock();
                fresh.semesterId   = row.semesterId;
                fresh.semesterName = row.semesterName;
                fresh.academicYear = row.academicYear;
                fresh.term         = row.term;
                fresh.startDate    = row.semesterStart;
                return fresh;
            });
            block.rows.add(row);
        }
        transcript.terms.addAll(blocks.values());

        computeTermAndRunningGpa(transcript);
        return transcript;
    }

    /**
     * Per-term GPA and the RUNNING cumulative GPA.
     *
     * <p>Accumulate quality points and credits, then divide ONCE. Never average the term
     * GPAs together — that is wrong whenever the terms carry different credit loads, and it
     * is the mistake TRANSCRIPT_RULES.md Section 4 exists to prevent.</p>
     */
    private void computeTermAndRunningGpa(Transcript transcript) {
        BigDecimal runningQualityPoints = BigDecimal.ZERO;
        int        runningCredits       = 0;

        for (TermBlock block : transcript.terms) {
            BigDecimal termQualityPoints = BigDecimal.ZERO;
            int        termCredits       = 0;

            for (TranscriptRow row : block.rows) {
                if (!row.countsTowardsGpa()) {
                    continue;   // W, I, in progress, or a superseded repeat
                }
                BigDecimal qualityPoints = row.qualityPoints != null
                        ? row.qualityPoints
                        : row.gradePoints.multiply(BigDecimal.valueOf(row.credits));
                termQualityPoints = termQualityPoints.add(qualityPoints);
                termCredits += row.credits;
            }

            block.termQualityPoints = termQualityPoints;
            block.termCredits       = termCredits;
            block.termGpa           = GradeCalculator.gpa(termQualityPoints, BigDecimal.valueOf(termCredits));

            runningQualityPoints = runningQualityPoints.add(termQualityPoints);
            runningCredits      += termCredits;

            block.cumulativeQualityPoints = runningQualityPoints;
            block.cumulativeCredits       = runningCredits;
            block.cumulativeGpa           = GradeCalculator.gpa(runningQualityPoints,
                                                                BigDecimal.valueOf(runningCredits));
        }

        transcript.creditsCountedInGpa = runningCredits;
    }

    /* ================================================================
       6. DEGREE PROGRESS
       ================================================================ */

    /**
     * Everything the Degree Progress screen shows: the credit summary, the mandatory-course
     * counts, the three Section 6.9 conditions and the full degree plan.
     *
     * <p>Read-only. Section 6.9 is about <em>showing</em> eligibility — conferring a degree is
     * a registrar action and this phase never writes {@code students.status = 'GRADUATED'}.</p>
     */
    public DegreeProgress getDegreeProgress(int studentId) {
        ValidationException.requireId(studentId, "Student");

        DegreeProgress progress = transcriptDao.findProgressSummary(studentId)
                .orElseThrow(() -> new ServiceException("That student record was not found."));

        progress.requirements.addAll(transcriptDao.findDegreePlan(studentId));

        progress.creditsRemaining = Math.max(0, progress.creditsRequired - progress.creditsCompleted);
        progress.percentComplete  = percentOf(progress.creditsCompleted, progress.creditsRequired);
        progress.mandatoryMissing = Math.max(0, progress.mandatoryTotal - progress.mandatoryPassed);

        // The overall verdict is exactly the three conditions, never a fourth opinion.
        progress.canGraduate = progress.allMandatoryPassed()
                            && progress.creditsSatisfied()
                            && progress.gpaSatisfied();

        buildStudyPlan(progress);
        return progress;
    }

    /* ================================================================
       7. STUDY PLAN — Degree Progress' "Show Your Study Plan" section
       ================================================================ */

    /**
     * Turns the raw PASSED / IN_PROGRESS / FAILED / NONE attempt fact already on every
     * {@link RequirementRow} (from {@link TranscriptDAO#findDegreePlan}) into the six Study Plan
     * statuses the screen shows, fills in {@code prerequisitesText}, and groups the rows into
     * {@link SemesterPlan}s in semester order.
     *
     * <p>A {@code NONE} row becomes {@code ELIGIBLE} only once every mandatory prerequisite is
     * {@code PASSED} and any credit threshold is met; otherwise it is {@code LOCKED} (a concrete,
     * nameable reason exists) if the student's progression has already reached it, or
     * {@code NOT_YET_AVAILABLE} (nothing specific blocks it — it is simply a later stage of the
     * plan) if it has not. {@code PASSED}/{@code IN_PROGRESS}/{@code FAILED} rows are real facts
     * already and are never second-guessed here.</p>
     */
    private void buildStudyPlan(DegreeProgress progress) {
        List<RequirementRow> rows = progress.requirements;
        if (rows.isEmpty()) {
            return;
        }

        Map<Integer, RequirementRow> rowByCourseId = new LinkedHashMap<>();
        for (RequirementRow row : rows) {
            rowByCourseId.put(row.courseId, row);
        }

        Map<Integer, List<TranscriptDAO.PrerequisiteLink>> prereqsByCourseId = new LinkedHashMap<>();
        for (TranscriptDAO.PrerequisiteLink link : transcriptDao.findProgramPrerequisites(progress.programId)) {
            prereqsByCourseId.computeIfAbsent(link.courseId, id -> new ArrayList<>()).add(link);
        }

        int stage = studentStage(rows);

        for (RequirementRow row : rows) {
            List<TranscriptDAO.PrerequisiteLink> prereqs =
                    prereqsByCourseId.getOrDefault(row.courseId, List.of());
            row.prerequisitesText = prerequisitesText(prereqs, row.minCompletedCredits);

            if (!"NONE".equals(row.courseStatus)) {
                continue; // PASSED / IN_PROGRESS / FAILED — already a real fact, not recomputed
            }

            boolean unmetPrerequisite = prereqs.stream().anyMatch(link -> {
                RequirementRow prereqRow = rowByCourseId.get(link.prerequisiteCourseId);
                // A prerequisite outside this program's own plan cannot be verified as passed,
                // so it blocks the same as an unmet one — Phase 25 makes sure this does not
                // happen for any course actually in a program's curriculum.
                return prereqRow == null || !"PASSED".equals(prereqRow.courseStatus);
            });
            boolean creditsShort = row.minCompletedCredits != null
                    && progress.creditsCompleted < row.minCompletedCredits;

            int semester = row.recommendedSemester == null ? Integer.MAX_VALUE : row.recommendedSemester;
            if (unmetPrerequisite || creditsShort) {
                row.courseStatus = "LOCKED";
            } else if (semester <= stage) {
                row.courseStatus = "ELIGIBLE";
            } else {
                row.courseStatus = "NOT_YET_AVAILABLE";
            }
        }

        Map<Integer, SemesterPlan> plansBySemester = new TreeMap<>();
        for (RequirementRow row : rows) {
            int semester = row.recommendedSemester == null ? 0 : row.recommendedSemester;
            plansBySemester.computeIfAbsent(semester, SemesterPlan::new).courses.add(row);
        }
        progress.semesterPlans.addAll(plansBySemester.values());
    }

    /**
     * The first semester (1-based) in which the student still has an incomplete mandatory
     * course. Everything before it is fully cleared (passed or in progress); this is where the
     * student actually stands, and it is what separates a {@code LOCKED} course (something
     * concrete is missing right now) from one that is merely {@code NOT_YET_AVAILABLE} (its own
     * requirements are satisfied, it is just later in the plan than the student has reached). A
     * student who has cleared every mandatory semester is placed one past the plan's last one, so
     * nothing is left artificially unavailable.
     */
    private int studentStage(List<RequirementRow> rows) {
        int maxSemester = rows.stream()
                .map(row -> row.recommendedSemester)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(1);

        for (int semester = 1; semester <= maxSemester; semester++) {
            int current = semester;
            boolean semesterCleared = rows.stream()
                    .filter(row -> row.isMandatory
                            && row.recommendedSemester != null && row.recommendedSemester == current)
                    .allMatch(row -> "PASSED".equals(row.courseStatus) || "IN_PROGRESS".equals(row.courseStatus));
            if (!semesterCleared) {
                return semester;
            }
        }
        return maxSemester + 1;
    }

    /**
     * The exact Prerequisites column text (Section 19): {@code "None"}, one or more
     * {@code "CODE - Title"} entries, a credit threshold, or both combined — never a vague
     * placeholder.
     */
    private String prerequisitesText(List<TranscriptDAO.PrerequisiteLink> prerequisites, Integer minCompletedCredits) {
        String courseList = prerequisites.stream()
                .map(link -> link.prerequisiteCode + " - " + link.prerequisiteTitle)
                .collect(Collectors.joining(", "));
        boolean hasCourses = !courseList.isEmpty();
        boolean hasCreditFloor = minCompletedCredits != null;

        if (hasCourses && hasCreditFloor) {
            return courseList + " passed and minimum " + minCompletedCredits + " completed credits";
        }
        if (hasCourses) {
            return courseList;
        }
        if (hasCreditFloor) {
            return "Minimum " + minCompletedCredits + " completed credits";
        }
        return "None";
    }

    /** A percentage from 0 to 100 with one decimal, never negative and never past 100. */
    private BigDecimal percentOf(int completed, int required) {
        if (required <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.max(0, completed))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(required), 1, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100));
    }
}
