package com.university.service;

import com.university.dao.RecommendationDAO;
import com.university.dao.StudentDAO;
import com.university.enums.DayOfWeekCode;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.util.GradeCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * THE AI ENGINE — project_details.md Section 9.
 *
 * <pre>
 *   LAYER 1  rule-based expert system        -> eligibility filter (rules R3, R4, R5, R6, R7)
 *   LAYER 2  content-based scoring           -> baseScore           0 .. 100
 *   LAYER 3  item-based k-NN, Jaccard, k=10  -> collaborativeBonus  0 .. +15
 *
 *   finalScore = baseScore + collaborativeBonus      MAXIMUM 115, NOT 100
 * </pre>
 *
 * <p><b>Why 115 and not capped at 100?</b> The base score is content-based — it comes only from
 * this student's own degree plan and record. The bonus is collaborative — it comes from other
 * students. Keeping them on separate scales means the two sources never contaminate each other:
 * you can always read off how much of a recommendation is "your degree needs this" and how much
 * is "people like you did well in it". Capping the total at 100 would force a course that scored
 * 100 on degree fit to show a collaborative bonus of zero, which would be a lie. 115 is a
 * deliberate design decision, not an off-by-one bug.</p>
 *
 * <p>Offline. Deterministic. No external API, no ML library, no random numbers — two runs on
 * unchanged data produce byte-identical output. Read-only: this class never writes.</p>
 *
 * <p><b>Adaptation note.</b> The phase document reads {@code vw_CourseStatistics} and
 * {@code vw_SectionDetails} and cross-checks against {@code sp_GetCourseRecommendations}. This
 * database has no views and no stored procedures, so the reads live in {@link RecommendationDAO}
 * and the SQL-side cross-check does not exist here. The credit limit comes from
 * {@link RegistrationService#creditCapFor(Student)} — Section 6.2's rule, which this project
 * implemented there rather than in the {@code GpaService} the document names.</p>
 */
public class RecommendationService {

    /* =====================================================================
       0. THE SCALE — the constants the whole model hangs on
       ===================================================================== */
    public static final int WEIGHT_MANDATORY       = 40;
    public static final int WEIGHT_BEHIND_SCHEDULE = 25;
    public static final int WEIGHT_UNLOCKS         = 20;
    public static final int WEIGHT_DIFFICULTY      = 15;

    /** 40 + 25 + 20 + 15. */
    public static final int MAX_BASE_SCORE          = 100;
    /** Layer 3's bonus. NOT folded into the base. */
    public static final int MAX_COLLABORATIVE_BONUS = 15;
    /** 100 + 15. The number printed on every card. */
    public static final int MAX_FINAL_SCORE = MAX_BASE_SCORE + MAX_COLLABORATIVE_BONUS;

    public static final int K_NEIGHBOURS         = 10;
    public static final int TOP_N                = 10;
    public static final int SEMESTERS_IN_PROGRAM = 8;

    /** Factor 1's elective fallback — Section 9: "40 if mandatory, else 10". */
    private static final int ELECTIVE_POINTS = 10;
    /** Factor 4's neutral value for a course nobody has ever completed — half of 15. */
    private static final int NEUTRAL_DIFFICULTY_POINTS = 8;

    private static final BigDecimal PASS_POINTS  = new BigDecimal("1.00");   // D or better
    private static final BigDecimal FOUR         = new BigDecimal("4.00");
    private static final BigDecimal GPA_STRUGGLE = new BigDecimal("2.00");
    private static final BigDecimal GPA_STRONG   = new BigDecimal("3.00");

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final RecommendationDAO dao = new RecommendationDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final RegistrationService registrationService = new RegistrationService();

    /* =====================================================================
       1. WHAT THE UI RECEIVES
       ===================================================================== */

    public enum Block { DEGREE_FIT, PEERS, ELIGIBILITY }

    /** One printed line of a card. */
    public static class Reason {
        public final Block   block;
        public final boolean positive;   // true -> green check, false -> grey dot
        public final String  text;
        public final Integer points;     // null = this line carries no points (eligibility)
        public final String  note;       // e.g. "(avg B+)", may be null
        public final String  tooltip;    // may be null

        public Reason(Block block, boolean positive, String text, Integer points,
                      String note, String tooltip) {
            this.block = block;
            this.positive = positive;
            this.text = text;
            this.points = points;
            this.note = note;
            this.tooltip = tooltip;
        }

        public static Reason scored(Block block, String text, int points) {
            return new Reason(block, points > 0, text, points, null, null);
        }

        public static Reason check(String text) {
            return new Reason(Block.ELIGIBILITY, true, text, null, null, null);
        }

        public String icon()       { return positive ? "✓" : "·"; }
        public String pointsText() { return points == null ? "" : "+" + points; }
    }

    /** One recommended course. */
    public static class Recommendation {
        public int    rank;
        public int    courseId;
        public int    sectionId;
        public int    credits;
        public String courseCode = "";
        public String courseTitle = "";
        public String sectionNumber = "";
        public String instructorName = "";
        public String room = "";
        public String scheduleText = "";
        public int    seatsTaken;
        public int    capacity;

        /* Layer 2 — each factor kept separately so the UI can print it */
        public int mandatoryPoints;
        public int behindSchedulePoints;
        public int unlockPoints;
        public int difficultyPoints;
        public int baseScore;

        /* Layer 3 */
        public int        collaborativeBonus;
        public int        peersTook;
        public int        peersPassed;
        public BigDecimal peersAvgPoints = BigDecimal.ZERO;

        /* Total */
        public int finalScore;

        public final List<Reason> degreeFitReasons   = new ArrayList<>();
        public final List<Reason> peerReasons        = new ArrayList<>();
        public final List<Reason> eligibilityReasons = new ArrayList<>();

        /* ---- the exact strings Section 9 prints ---- */
        public String headerTitle() { return courseCode + " — " + courseTitle; }

        public String scoreText() { return "Score: " + finalScore + " / " + MAX_FINAL_SCORE; }

        public String degreeFitTitle() {
            return "Degree fit (base score " + baseScore + "/" + MAX_BASE_SCORE + ")";
        }

        public String peerTitle() {
            return "Students like you (bonus +" + collaborativeBonus + "/"
                 + MAX_COLLABORATIVE_BONUS + ")";
        }

        public String eligibilityTitle() { return "Eligibility (all checks passed)"; }

        public String subHeader() {
            return credits + " credits  ·  Section " + sectionNumber
                 + "  ·  " + (instructorName == null || instructorName.isBlank()
                                ? "TBA" : instructorName)
                 + "  ·  " + (scheduleText == null || scheduleText.isBlank()
                                ? "No schedule" : scheduleText);
        }
    }

    /** The whole run: the list plus the numbers the "Why this order?" dialog needs. */
    public static class RecommendationResult {
        public final List<Recommendation> recommendations = new ArrayList<>();
        public String        blockedReason;          // non-null -> show a banner, not cards
        public int           sectionsConsidered;
        public int           sectionsSurviving;
        public int           neighbourCount;
        public double        highestSimilarity;
        public double        lowestSimilarity;
        public BigDecimal    cumulativeGpa = BigDecimal.ZERO;
        public int           completedCredits;
        public int           currentSemester;
        public int           currentCredits;
        public int           creditLimit;
        public String        programName = "";
        public LocalDateTime generatedAt = LocalDateTime.now();

        public boolean isBlocked() { return blockedReason != null; }
    }

    /* =====================================================================
       2. INTERNAL TYPES — public so RecommendationDAO can fill them, exactly as
          TranscriptService/TranscriptDAO do in Phase 12.
       ===================================================================== */

    public static class StudentProfile {
        public int        studentId;
        public int        programId;
        public int        completedCredits;
        public int        currentSemester;
        public int        creditsRequired;
        public int        currentCredits;
        public int        creditLimit;
        public String     status = "";
        public String     programName = "";
        public BigDecimal gpa = BigDecimal.ZERO;
    }

    public static class Prereq {
        public int        courseId;
        public int        prerequisiteCourseId;
        public String     prerequisiteCode;
        public BigDecimal minPoints = BigDecimal.ZERO;
    }

    public static class PassedCourse {
        public int        courseId;
        public String     courseCode;
        public String     letterGrade;
        public BigDecimal points = BigDecimal.ZERO;
    }

    public static class Meeting {
        public int       sectionId;
        public String    day;
        public LocalTime start;
        public LocalTime end;

        /** Touching is not overlapping: a class ending 10:30 and one starting 10:30 both fit. */
        public boolean overlaps(Meeting other) {
            return day.equals(other.day)
                && start.isBefore(other.end)
                && other.start.isBefore(end);
        }
    }

    public static class PlanEntry {
        public int     courseId;
        public boolean isMandatory;
        public Integer recommendedSemester;   // may be null
    }

    public static class CourseStats {
        public int        courseId;
        public int        unlocksCount;
        public BigDecimal avgGradePoints;     // null = never taken
        public int        timesTaken;
    }

    public static class Candidate {
        public int    sectionId;
        public int    courseId;
        public int    credits;
        public int    capacity;
        public int    enrolledCount;
        public int    seatsAvailable;
        public String courseCode = "";
        public String courseTitle = "";
        public String sectionNumber = "";
        public String instructorName = "";
        public String room = "";
        public String scheduleText = "";
        public final List<Reason> eligibility = new ArrayList<>();
    }

    public static class Attempt {
        public int        studentId;
        public int        courseId;
        public BigDecimal points = BigDecimal.ZERO;

        public boolean passed() { return points.compareTo(PASS_POINTS) >= 0; }
    }

    /** One of the k nearest neighbours. */
    public static class Neighbour {
        public int    studentId;
        public double similarity;
    }

    private static class PeerStats {
        int        took;
        int        passed;
        BigDecimal totalPoints = BigDecimal.ZERO;

        BigDecimal avgPoints() {
            return took == 0 ? BigDecimal.ZERO
                             : totalPoints.divide(BigDecimal.valueOf(took), 4, RoundingMode.HALF_UP);
        }

        double passRate() { return took == 0 ? 0.0 : (double) passed / (double) took; }
    }

    /* =====================================================================
       3. THE ORCHESTRATOR — read this method first, it is the whole story
       ===================================================================== */
    public RecommendationResult recommend(int studentId) {
        ValidationException.requireId(studentId, "Student");
        RecommendationResult out = new RecommendationResult();

        /* ---------- R1 / R2 : not per-course, so they gate the whole screen ---------- */
        String gate = checkGlobalGates(studentId);
        if (gate != null) {
            out.blockedReason = gate;
            return out;
        }

        StudentProfile me = dao.findProfile(studentId)
                .orElseThrow(() -> new ServiceException("That student record was not found."));
        me.currentSemester = currentSemesterOf(me.completedCredits, me.creditsRequired);

        /* ---------- load everything ONCE (no N+1 queries) ---------- */
        Map<Integer, List<Prereq>>  prereqs     = dao.loadAllPrerequisites();
        Map<Integer, PassedCourse>  myPassed    = dao.loadMyBestPassedGrades(studentId);
        Set<Integer>                enrolledNow = new HashSet<>();
        me.currentCredits = dao.loadCurrentEnrolment(studentId, enrolledNow);
        me.creditLimit    = creditLimitFor(studentId);

        List<Meeting>               myMeetings    = dao.loadMyMeetings(studentId);
        Map<Integer, PlanEntry>     plan          = dao.loadProgramRequirements(me.programId);
        Map<Integer, CourseStats>   stats         = dao.loadCourseStatistics();
        Map<Integer, List<String>>  unlockedCodes = dao.loadUnlockedCourseCodes();
        List<Attempt>               attempts      = dao.loadAllGradedAttempts();
        Map<Integer, List<Meeting>> sectionMeet   = dao.loadAllSectionMeetings();
        List<Candidate>             candidates    = dao.loadOpenSections();

        for (Candidate candidate : candidates) {
            candidate.scheduleText = describeSchedule(
                    sectionMeet.getOrDefault(candidate.sectionId, List.of()));
        }

        out.sectionsConsidered = candidates.size();
        int maxUnlocks = maxUnlocksInCatalogue(stats);

        /* ---------- LAYER 1 : eligibility filter (R3, R4, R5, R6, R7) ---------- */
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (isEligible(candidate, me, prereqs, myPassed, enrolledNow, myMeetings, sectionMeet)) {
                eligible.add(candidate);
            }
        }
        eligible = keepBestSectionPerCourse(eligible);
        out.sectionsSurviving = eligible.size();

        /* ---------- LAYER 3 preparation : neighbours, once for the whole run ---------- */
        Map<Integer, Set<Integer>> passedSets = buildPassedCourseSets(attempts);
        List<Neighbour> neighbours = findKNearestNeighbours(studentId, passedSets);
        Map<Integer, PeerStats> peerStats = collectNeighbourCourseStats(neighbours, attempts);

        out.neighbourCount = neighbours.size();
        if (!neighbours.isEmpty()) {
            out.highestSimilarity = neighbours.get(0).similarity;
            out.lowestSimilarity  = neighbours.get(neighbours.size() - 1).similarity;
        }

        /* ---------- LAYER 2 + LAYER 3 : score every survivor ---------- */
        for (Candidate candidate : eligible) {
            out.recommendations.add(
                    score(candidate, me, plan, stats, unlockedCodes, maxUnlocks, peerStats));
        }

        /* ---------- sort, cut, rank. Deterministic tie-break on course code. ---------- */
        out.recommendations.sort(
                Comparator.comparingInt((Recommendation r) -> r.finalScore).reversed()
                          .thenComparing(r -> r.courseCode));
        while (out.recommendations.size() > TOP_N) {
            out.recommendations.remove(out.recommendations.size() - 1);
        }
        for (int i = 0; i < out.recommendations.size(); i++) {
            out.recommendations.get(i).rank = i + 1;
        }

        out.cumulativeGpa    = me.gpa;
        out.completedCredits = me.completedCredits;
        out.currentSemester  = me.currentSemester;
        out.currentCredits   = me.currentCredits;
        out.creditLimit      = me.creditLimit;
        out.programName      = me.programName;
        return out;
    }

    /**
     * R1 (registration window) and R2 (the student is ACTIVE). Neither is a per-course rule, so
     * a failure shows a banner instead of an empty list — a student who cannot register at all
     * deserves to be told why, not handed a blank screen.
     *
     * @return the banner text, or null when the screen may proceed
     */
    private String checkGlobalGates(int studentId) {
        String status = dao.findStudentStatus(studentId).orElse(null);
        if (status == null) {
            return "No student record was found for this account.";
        }
        if (!"ACTIVE".equals(status)) {
            return "Your student status is " + status
                 + ". Recommendations are only available to active students.";
        }

        Semester semester = registrationService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester, so there is nothing to recommend.";
        }
        LocalDateTime now = LocalDateTime.now();
        if (semester.getRegistrationStart() != null && now.isBefore(semester.getRegistrationStart())) {
            return "Registration for " + semester.getSemesterName()
                 + " opens on " + semester.getRegistrationStart().toLocalDate() + ".";
        }
        if (semester.getRegistrationEnd() != null && now.isAfter(semester.getRegistrationEnd())) {
            return "Registration for " + semester.getSemesterName()
                 + " closed on " + semester.getRegistrationEnd().toLocalDate() + ".";
        }
        return null;
    }

    /** Section 6.2's credit cap, reused from Phase 09 rather than re-implemented here. */
    private int creditLimitFor(int studentId) {
        Student student = studentDao.findById(studentId)
                .orElseThrow(() -> new ServiceException("That student record was not found."));
        return registrationService.creditCapFor(student);
    }

    /** Which semester the student is in, derived from progress rather than from a calendar. */
    public int currentSemesterOf(int completedCredits, int creditsRequired) {
        if (creditsRequired <= 0) {
            return 1;
        }
        double perSemester = (double) creditsRequired / SEMESTERS_IN_PROGRAM;
        int semester = (int) Math.floor(completedCredits / perSemester) + 1;
        return Math.max(1, Math.min(SEMESTERS_IN_PROGRAM, semester));
    }

    /* =====================================================================
       LAYER 1 — THE ELIGIBILITY FILTER (rules R3, R4, R5, R6, R7)
       Every rule is its own method so it can be pointed at during the demo.
       ===================================================================== */

    private boolean isEligible(Candidate candidate, StudentProfile me,
                               Map<Integer, List<Prereq>> prereqs,
                               Map<Integer, PassedCourse> myPassed,
                               Set<Integer> enrolledNow,
                               List<Meeting> myMeetings,
                               Map<Integer, List<Meeting>> sectionMeet) {
        candidate.eligibility.clear();
        return passesR4_notAlreadyTaken(candidate, myPassed, enrolledNow)
            && passesR3_prerequisitesMet(candidate, prereqs, myPassed)
            && passesR5_seatAvailable(candidate)
            && passesR6_noTimetableConflict(candidate, myMeetings, sectionMeet)
            && passesR7_withinCreditLimit(candidate, me);
    }

    /** R4 — never recommend something already passed or already being taken. */
    private boolean passesR4_notAlreadyTaken(Candidate candidate,
                                             Map<Integer, PassedCourse> myPassed,
                                             Set<Integer> enrolledNow) {
        if (myPassed.containsKey(candidate.courseId) || enrolledNow.contains(candidate.courseId)) {
            return false;
        }
        candidate.eligibility.add(Reason.check("You have not taken this course before"));
        return true;
    }

    /** R3 — every prerequisite passed with at least {@code min_grade_points}. */
    private boolean passesR3_prerequisitesMet(Candidate candidate,
                                              Map<Integer, List<Prereq>> prereqs,
                                              Map<Integer, PassedCourse> myPassed) {
        List<Prereq> required = prereqs.get(candidate.courseId);
        if (required == null || required.isEmpty()) {
            candidate.eligibility.add(Reason.check("No prerequisites required"));
            return true;
        }
        List<String> met = new ArrayList<>();
        for (Prereq prereq : required) {
            PassedCourse got = myPassed.get(prereq.prerequisiteCourseId);
            if (got == null || got.points.compareTo(prereq.minPoints) < 0) {
                return false;
            }
            met.add(prereq.prerequisiteCode + " (" + got.letterGrade + ")");
        }
        met.sort((a, b) -> a.compareTo(b));
        candidate.eligibility.add(Reason.check("Prerequisites met: " + String.join(", ", met)));
        return true;
    }

    /** R5 — a seat must be free. */
    private boolean passesR5_seatAvailable(Candidate candidate) {
        if (candidate.enrolledCount >= candidate.capacity) {
            return false;
        }
        int free = candidate.capacity - candidate.enrolledCount;
        candidate.eligibility.add(Reason.check(candidate.enrolledCount + " of " + candidate.capacity
                + " seats taken — " + free + " still free"));
        return true;
    }

    /** R6 — no day/time overlap with anything already registered. Touching is not overlapping. */
    private boolean passesR6_noTimetableConflict(Candidate candidate, List<Meeting> myMeetings,
                                                 Map<Integer, List<Meeting>> sectionMeet) {
        for (Meeting theirs : sectionMeet.getOrDefault(candidate.sectionId, List.of())) {
            for (Meeting mine : myMeetings) {
                if (theirs.overlaps(mine)) {
                    return false;
                }
            }
        }
        candidate.eligibility.add(Reason.check("Fits your timetable — no conflicts"));
        return true;
    }

    /** R7 — the resulting credit load must stay within the Section 6.2 limit. */
    private boolean passesR7_withinCreditLimit(Candidate candidate, StudentProfile me) {
        int total = me.currentCredits + candidate.credits;
        if (total > me.creditLimit) {
            return false;
        }
        candidate.eligibility.add(Reason.check("Credit load would be " + me.currentCredits + " + "
                + candidate.credits + " = " + total + " of your " + me.creditLimit + " limit"));
        return true;
    }

    /**
     * One recommendation per course: keep the section with the most free seats.
     * A list that shows the same course three times is a bug, not a feature.
     */
    private List<Candidate> keepBestSectionPerCourse(List<Candidate> in) {
        Map<Integer, Candidate> best = new LinkedHashMap<>();
        for (Candidate candidate : in) {
            Candidate current = best.get(candidate.courseId);
            if (current == null
                    || (candidate.capacity - candidate.enrolledCount)
                        > (current.capacity - current.enrolledCount)) {
                best.put(candidate.courseId, candidate);
            }
        }
        return new ArrayList<>(best.values());
    }

    /* =====================================================================
       LAYER 2 — THE FOUR WEIGHTED FACTORS (base score, 0 .. 100)
       Each factor is rounded on its own, THEN added. Never round the total.
       ===================================================================== */

    private Recommendation score(Candidate candidate, StudentProfile me,
                                 Map<Integer, PlanEntry> plan,
                                 Map<Integer, CourseStats> stats,
                                 Map<Integer, List<String>> unlockedCodes,
                                 int maxUnlocks,
                                 Map<Integer, PeerStats> peerStats) {

        Recommendation r = new Recommendation();
        r.courseId       = candidate.courseId;
        r.sectionId      = candidate.sectionId;
        r.courseCode     = candidate.courseCode;
        r.courseTitle    = candidate.courseTitle;
        r.credits        = candidate.credits;
        r.sectionNumber  = candidate.sectionNumber;
        r.instructorName = candidate.instructorName;
        r.room           = candidate.room;
        r.scheduleText   = candidate.scheduleText;
        r.capacity       = candidate.capacity;
        r.seatsTaken     = candidate.enrolledCount;
        r.eligibilityReasons.addAll(candidate.eligibility);

        PlanEntry   entry = plan.get(candidate.courseId);
        CourseStats st    = stats.get(candidate.courseId);

        /* ---- the four factors ---- */
        r.mandatoryPoints      = scoreMandatoryForDegree(entry, me, r);
        r.behindSchedulePoints = scoreBehindSchedule(entry, me, r);
        r.unlockPoints         = scoreUnlocksFutureCourses(st, maxUnlocks,
                                    unlockedCodes.getOrDefault(candidate.courseId, List.of()), r);
        r.difficultyPoints     = scoreDifficultyMatch(st, me, r);

        r.baseScore = r.mandatoryPoints + r.behindSchedulePoints
                    + r.unlockPoints + r.difficultyPoints;

        /* ---- layer 3 ---- */
        r.collaborativeBonus = scoreCollaborativeBonus(peerStats.get(candidate.courseId), r);

        /* ---- the total. 115, not 100. ---- */
        r.finalScore = r.baseScore + r.collaborativeBonus;

        assertScoreIsSane(r);
        return r;
    }

    /**
     * FACTOR 1 — Mandatory for the degree. Weight 40.
     * Section 9: "40 if is_mandatory = 1 in program_requirements, else 10." A lookup, not
     * arithmetic — two values, 40 and 10.
     */
    private int scoreMandatoryForDegree(PlanEntry entry, StudentProfile me, Recommendation r) {
        int points;
        String text;
        if (entry != null && entry.isMandatory) {
            points = WEIGHT_MANDATORY;
            text   = "Required for your degree (" + me.programName + ")";
        } else {
            points = ELECTIVE_POINTS;
            text   = "Counts as an elective towards " + me.programName;
        }
        r.degreeFitReasons.add(Reason.scored(Block.DEGREE_FIT, text, points));
        return points;
    }

    /**
     * FACTOR 2 — Behind schedule. Weight 25, capped at 25.
     *
     * <p>A lookup table, not a formula, on purpose: the professor can read it, the student can
     * defend it, and it can never produce 25.4 points.</p>
     */
    private int scoreBehindSchedule(PlanEntry entry, StudentProfile me, Recommendation r) {
        if (entry == null || entry.recommendedSemester == null) {
            r.degreeFitReasons.add(Reason.scored(Block.DEGREE_FIT,
                    "Not part of your published degree plan", 0));
            return 0;
        }
        int recommended = entry.recommendedSemester;
        int behind      = me.currentSemester - recommended;

        int points;
        String text;
        if (behind >= 3) {
            points = 25;
            text = "You are " + behind + " semesters behind on this course";
        } else if (behind == 2) {
            points = 22;
            text = "You are 2 semesters behind on this course";
        } else if (behind == 1) {
            points = 18;
            text = "You are 1 semester behind on this course";
        } else if (behind == 0) {
            points = 12;
            text = "Scheduled for your current semester (semester " + recommended + ")";
        } else if (behind == -1) {
            points = 5;
            text = "Normally taken in semester " + recommended + " — one semester ahead";
        } else {
            points = 0;
            text = "Not scheduled until semester " + recommended + " — too early";
        }
        r.degreeFitReasons.add(Reason.scored(Block.DEGREE_FIT, text, points));
        return points;
    }

    /**
     * FACTOR 3 — Unlocks future courses. Weight 20.
     * Section 9: {@code 20 × (courses that list this as a prerequisite / max in catalogue)}.
     */
    private int scoreUnlocksFutureCourses(CourseStats st, int maxUnlocks,
                                          List<String> unlockedCodes, Recommendation r) {
        int unlocks = (st == null) ? 0 : st.unlocksCount;
        int points  = (maxUnlocks <= 0 || unlocks <= 0)
                ? 0
                : (int) Math.round(WEIGHT_UNLOCKS * ((double) unlocks / (double) maxUnlocks));

        String text;
        if (unlocks <= 0) {
            text = "Does not unlock any later course";
        } else {
            List<String> shown = unlockedCodes.size() > 4
                    ? unlockedCodes.subList(0, 4) : unlockedCodes;
            String codes = String.join(", ", shown) + (unlockedCodes.size() > 4 ? ", …" : "");
            text = "Unlocks " + unlocks + " later " + plural(unlocks, "course")
                 + (codes.isBlank() ? "" : " (" + codes + ")");
        }
        r.degreeFitReasons.add(Reason.scored(Block.DEGREE_FIT, text, points));
        return points;
    }

    /**
     * FACTOR 4 — Difficulty match. Weight 15.
     *
     * <pre>
     *   offset = +0.50 if gpa &lt; 2.00 ; 0.00 if 2.00 &lt;= gpa &lt; 3.00 ; -0.50 if gpa &gt;= 3.00
     *   target = clamp(gpa + offset, 0, 4)
     *   points = round( 15 × (1 − |courseAvg − target| / 4) )
     *   points = 8 when the course has never been taken (neutral)
     * </pre>
     *
     * <p>A student on 3.24 is strong, so the model aims at courses whose historical average is
     * 2.74 — harder than the student's own average.</p>
     */
    private int scoreDifficultyMatch(CourseStats st, StudentProfile me, Recommendation r) {
        if (st == null || st.avgGradePoints == null || st.timesTaken == 0) {
            r.degreeFitReasons.add(Reason.scored(Block.DEGREE_FIT,
                    "No grade history yet — difficulty assumed average", NEUTRAL_DIFFICULTY_POINTS));
            return NEUTRAL_DIFFICULTY_POINTS;
        }
        BigDecimal offset = me.gpa.compareTo(GPA_STRUGGLE) < 0 ? new BigDecimal("0.50")
                          : me.gpa.compareTo(GPA_STRONG) < 0   ? BigDecimal.ZERO
                                                               : new BigDecimal("-0.50");
        BigDecimal target = clamp(me.gpa.add(offset), BigDecimal.ZERO, FOUR);
        double distance   = st.avgGradePoints.subtract(target).abs().doubleValue();
        int points        = (int) Math.round(WEIGHT_DIFFICULTY * (1.0 - distance / 4.0));
        if (points < 0) {
            points = 0;
        }

        r.degreeFitReasons.add(new Reason(Block.DEGREE_FIT, points > 0,
                "Difficulty suits your GPA of " + GradeCalculator.formatGpa(me.gpa),
                points, null,
                "Course historical average "
                    + st.avgGradePoints.setScale(2, RoundingMode.HALF_UP)
                    + " grade points over " + st.timesTaken
                    + " attempts; your target band is " + target.setScale(2, RoundingMode.HALF_UP)));
        return points;
    }

    /* =====================================================================
       LAYER 3 — ITEM-BASED k-NN WITH THE JACCARD INDEX (bonus 0 .. +15)
       ===================================================================== */

    /**
     * THE JACCARD INDEX — Section 9.
     *
     * <pre>
     *              |A ∩ B|
     *   J(A,B) = ───────────
     *              |A ∪ B|
     * </pre>
     *
     * <p>A and B are the sets of course ids each student has PASSED.
     * {@code |A ∪ B| = |A| + |B| − |A ∩ B|}, so the union never has to be built, and an empty
     * set gives 0.0 rather than a division by zero.</p>
     *
     * <p><b>Why Jaccard and not cosine or Pearson?</b> The data is implicit and binary — a
     * student either passed a course or did not, there are no ratings. Jaccard is the natural
     * similarity for sets, and it normalises for how many courses somebody has taken, so a
     * fourth-year student who has passed 40 courses does not look "similar to everybody".</p>
     */
    public double jaccardIndex(Set<Integer> a, Set<Integer> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<Integer> smaller = a.size() <= b.size() ? a : b;
        Set<Integer> larger  = a.size() <= b.size() ? b : a;
        int intersection = 0;
        for (Integer id : smaller) {
            if (larger.contains(id)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / (double) union;
    }

    /** The k = 10 most similar students. Deterministic: ties are broken by student id. */
    public List<Neighbour> findKNearestNeighbours(int studentId, Map<Integer, Set<Integer>> passedSets) {
        Set<Integer> mine = passedSets.getOrDefault(studentId, Set.of());
        List<Neighbour> all = new ArrayList<>();
        if (mine.isEmpty()) {
            return all;                      // a brand-new student has no neighbours
        }
        for (Map.Entry<Integer, Set<Integer>> entry : passedSets.entrySet()) {
            if (entry.getKey() == studentId) {
                continue;
            }
            double similarity = jaccardIndex(mine, entry.getValue());
            if (similarity <= 0.0) {
                continue;                    // no course in common = not a neighbour, a stranger
            }
            Neighbour neighbour = new Neighbour();
            neighbour.studentId  = entry.getKey();
            neighbour.similarity = similarity;
            all.add(neighbour);
        }
        all.sort(Comparator.comparingDouble((Neighbour n) -> n.similarity).reversed()
                           .thenComparingInt(n -> n.studentId));
        return all.size() > K_NEIGHBOURS ? new ArrayList<>(all.subList(0, K_NEIGHBOURS)) : all;
    }

    /** student_id -> the set of course ids they PASSED. This is the "A" and "B" of Jaccard. */
    public Map<Integer, Set<Integer>> buildPassedCourseSets(List<Attempt> attempts) {
        Map<Integer, Set<Integer>> map = new HashMap<>();
        for (Attempt attempt : attempts) {
            if (attempt.passed()) {
                map.computeIfAbsent(attempt.studentId, key -> new TreeSet<>()).add(attempt.courseId);
            }
        }
        return map;
    }

    /** What the neighbours took, and how well they did. */
    private Map<Integer, PeerStats> collectNeighbourCourseStats(List<Neighbour> neighbours,
                                                                List<Attempt> attempts) {
        Set<Integer> ids = new HashSet<>();
        for (Neighbour neighbour : neighbours) {
            ids.add(neighbour.studentId);
        }
        Map<Integer, PeerStats> byCourse = new HashMap<>();
        for (Attempt attempt : attempts) {
            if (!ids.contains(attempt.studentId)) {
                continue;
            }
            PeerStats stats = byCourse.computeIfAbsent(attempt.courseId, key -> new PeerStats());
            stats.took++;
            stats.totalPoints = stats.totalPoints.add(attempt.points);
            if (attempt.passed()) {
                stats.passed++;
            }
        }
        return byCourse;
    }

    /**
     * THE BONUS — 0 .. +15, never negative, never folded into the base.
     *
     * <pre>bonus = round( 15 × passRate × (avgGradePoints / 4) )</pre>
     *
     * <p>Both halves matter: {@code passRate} says "people like you get through it",
     * {@code avgPts / 4} says "and they do well in it". A course everybody scrapes a D in earns
     * {@code 15 × 1.00 × 0.25 = 4}, not 15. No evidence means no bonus — never a penalty.</p>
     */
    private int scoreCollaborativeBonus(PeerStats stats, Recommendation r) {
        if (stats == null || stats.took == 0) {
            r.peerReasons.add(new Reason(Block.PEERS, false,
                    "No students with a similar background have taken this yet", 0, null, null));
            return 0;
        }
        double passRate = stats.passRate();
        double avgRatio = stats.avgPoints().doubleValue() / 4.0;
        int bonus = (int) Math.round(MAX_COLLABORATIVE_BONUS * passRate * avgRatio);
        bonus = Math.max(0, Math.min(MAX_COLLABORATIVE_BONUS, bonus));

        r.peersTook      = stats.took;
        r.peersPassed    = stats.passed;
        r.peersAvgPoints = stats.avgPoints().setScale(2, RoundingMode.HALF_UP);

        int percent = (int) Math.round(passRate * 100.0);
        r.peerReasons.add(new Reason(Block.PEERS, bonus > 0,
                percent + "% of students like you passed it", bonus,
                "(avg " + nearestLetter(r.peersAvgPoints) + ")",
                "avg " + r.peersAvgPoints + " grade points from " + stats.took
                    + " attempts by students similar to you"));
        return bonus;
    }

    /* =====================================================================
       GUARD RAILS
       ===================================================================== */

    /**
     * A model that cannot violate its own scale is a model you can demo without fear.
     *
     * <p>This is the method that makes it impossible for the screen to lie: the four printed
     * degree-fit numbers must add up to the printed base score, and the base plus the bonus must
     * equal the header score.</p>
     */
    private void assertScoreIsSane(Recommendation r) {
        int shown = 0;
        for (Reason reason : r.degreeFitReasons) {
            shown += (reason.points == null ? 0 : reason.points);
        }
        if (shown != r.baseScore) {
            throw new IllegalStateException("Displayed degree-fit reasons sum to " + shown
                    + " but baseScore is " + r.baseScore + " for " + r.courseCode);
        }
        if (r.degreeFitReasons.size() != 4) {
            throw new IllegalStateException("Expected exactly 4 degree-fit lines for "
                    + r.courseCode + ", found " + r.degreeFitReasons.size());
        }
        if (r.baseScore < 0 || r.baseScore > MAX_BASE_SCORE) {
            throw new IllegalStateException("baseScore out of range: " + r.baseScore);
        }
        if (r.collaborativeBonus < 0 || r.collaborativeBonus > MAX_COLLABORATIVE_BONUS) {
            throw new IllegalStateException("bonus out of range: " + r.collaborativeBonus);
        }
        if (r.finalScore != r.baseScore + r.collaborativeBonus
                || r.finalScore > MAX_FINAL_SCORE || r.finalScore < 0) {
            throw new IllegalStateException("finalScore out of range: " + r.finalScore);
        }
    }

    /* =====================================================================
       SMALL HELPERS
       ===================================================================== */

    private int maxUnlocksInCatalogue(Map<Integer, CourseStats> stats) {
        int max = 0;
        for (CourseStats entry : stats.values()) {
            max = Math.max(max, entry.unlocksCount);
        }
        return max;
    }

    /** "SUN 10:00-11:30, TUE 10:00-11:30" — day order, then start time. */
    private String describeSchedule(List<Meeting> meetings) {
        if (meetings.isEmpty()) {
            return "";
        }
        List<Meeting> sorted = new ArrayList<>(meetings);
        sorted.sort(Comparator.comparingInt((Meeting m) -> dayOrder(m.day))
                              .thenComparing(m -> m.start));
        List<String> parts = new ArrayList<>();
        for (Meeting meeting : sorted) {
            parts.add(meeting.day + " " + meeting.start.format(HM) + "-" + meeting.end.format(HM));
        }
        return String.join(", ", parts);
    }

    private int dayOrder(String day) {
        try {
            return DayOfWeekCode.fromDb(day).ordinal();
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;   // an unrecognised day sorts last rather than crashing
        }
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal low, BigDecimal high) {
        if (value.compareTo(low) < 0) {
            return low;
        }
        if (value.compareTo(high) > 0) {
            return high;
        }
        return value;
    }

    private static String plural(int n, String word) {
        return n == 1 ? word : word + "s";
    }

    /** The Section 5.2 band closest to a grade-point average, for "(avg B+)". */
    private static String nearestLetter(BigDecimal points) {
        String[] letters = { "A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D+", "D", "F" };
        double[] values  = { 4.00, 3.70, 3.30, 3.00, 2.70, 2.30, 2.00, 1.70, 1.30, 1.00, 0.00 };
        double value = points.doubleValue();
        String best = "F";
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < letters.length; i++) {
            double gap = Math.abs(values[i] - value);
            if (gap < bestGap) {
                bestGap = gap;
                best = letters[i];
            }
        }
        return best;
    }
}
