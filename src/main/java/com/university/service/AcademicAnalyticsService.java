package com.university.service;

import com.university.enums.LetterGrade;
import com.university.enums.Term;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.service.TranscriptService.DegreeProgress;
import com.university.service.TranscriptService.SemesterPlan;
import com.university.service.TranscriptService.TermBlock;
import com.university.service.TranscriptService.Transcript;
import com.university.service.TranscriptService.TranscriptRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the "Academic Analytics" screen's data from the same sources the Transcript and
 * Degree Progress screens already use — {@link TranscriptService}, {@link StudentService},
 * {@link TuitionService} and {@link SemesterService}. Nothing here queries the database
 * directly and nothing here invents a number: every figure is either read straight off an
 * existing service/DAO result or derived in plain Java from rows those services already fetch.
 *
 * <p>The one genuinely new idea is the graduation projection (credits per semester, cumulative
 * credits, and an estimated graduation semester). It is built from the student's own historical
 * pace where any exists, falling back to the average credit load implied by their own program's
 * Study Plan — never a fabricated constant — and is clearly a projection, not a promise.</p>
 */
public class AcademicAnalyticsService {

    private final TranscriptService transcriptService = new TranscriptService();
    private final StudentService studentService = new StudentService();
    private final TuitionService tuitionService = new TuitionService();
    private final SemesterService semesterService = new SemesterService();

    /* ================================================================
       DATA CARRIED TO THE SCREEN
       ================================================================ */

    /** One bar-chart category: a semester the student actually studied in. */
    public static class SemesterBar {
        public String label;
        public BigDecimal termGpa;
        public BigDecimal cumulativeGpa;
        public int passedCredits;
    }

    /** One point of the graduation-projection line chart — actual, projected, or both. */
    public static class ProjectionPoint {
        public String label;
        public boolean actual;
        public Integer perSemesterCredits;          // null for a projected slot
        public Integer cumulativeCredits;            // null for a projected slot
        public Integer projectedCumulativeCredits;   // null before the projection starts
        public int graduationTarget;
    }

    public static class Analytics {
        public String fullName = "";
        public String programName = "";
        public BigDecimal cumulativeGpa = BigDecimal.ZERO;
        public String standingLabel = "";
        public String statusLabel = "";
        public int completedCredits;
        public int requiredCredits;
        public int remainingCredits;
        public BigDecimal progressPercent = BigDecimal.ZERO;
        public String estimatedGraduationSemester = "Unknown";

        public boolean blocked;
        public String blockType;     // "Financial" / "Academic" / null
        public String blockReason;   // null when not blocked

        public final List<SemesterBar> semesterBars = new ArrayList<>();
        public final List<ProjectionPoint> projection = new ArrayList<>();

        public double progressFraction() {
            double f = progressPercent.doubleValue() / 100.0;
            return Math.max(0.0, Math.min(1.0, f));
        }

        public double gpaFraction() {
            double f = cumulativeGpa.doubleValue() / 4.0;
            return Math.max(0.0, Math.min(1.0, f));
        }
    }

    /* ================================================================
       BUILD
       ================================================================ */

    public Analytics build(int studentId) {
        Transcript transcript = transcriptService.getTranscript(studentId);
        DegreeProgress progress = transcriptService.getDegreeProgress(studentId);
        Student student = studentService.findById(studentId);

        Analytics a = new Analytics();
        a.fullName = transcript.fullName();
        a.programName = progress.programName;
        a.cumulativeGpa = progress.cumulativeGpa;
        a.standingLabel = student.getAcademicStanding().getLabel();
        a.statusLabel = student.getStatus().getLabel();
        a.completedCredits = progress.creditsCompleted;
        a.requiredCredits = progress.creditsRequired;
        a.remainingCredits = progress.creditsRemaining;
        a.progressPercent = progress.percentComplete;

        fillBlockage(a, student);
        fillSemesterBars(a, transcript);
        fillProjection(a, transcript, progress);

        return a;
    }

    /* ================================================================
       BLOCKAGE — reuses RegistrationService's own R2 / financial-hold facts
       ================================================================ */

    private void fillBlockage(Analytics a, Student student) {
        // GRADUATED also fails canRegister(), but that is a completed program, not a hold to
        // resolve — only SUSPENDED and WITHDRAWN are shown here as an academic blockage.
        boolean academicHold = student.getStatus() == com.university.enums.StudentStatus.SUSPENDED
                || student.getStatus() == com.university.enums.StudentStatus.WITHDRAWN;
        if (academicHold) {
            a.blocked = true;
            a.blockType = "Academic";
            a.blockReason = "Your account is " + student.getStatus().getLabel()
                    + ". Please contact the registrar.";
            return;
        }

        Semester target = semesterService.getCurrentSemester();
        if (target != null && tuitionService.hasUnpaidPreviousBalance(student.getStudentId(), target)) {
            a.blocked = true;
            a.blockType = "Financial";
            a.blockReason = "You have an unpaid balance from a previous semester. "
                    + "Please settle it before registering for a new semester.";
            return;
        }

        a.blocked = false;
        a.blockType = null;
        a.blockReason = null;
    }

    /* ================================================================
       CUMULATIVE INDICATOR — Semester GPA / Cumulative GPA / Course Credit
       ================================================================ */

    private void fillSemesterBars(Analytics a, Transcript transcript) {
        for (TermBlock block : transcript.terms) {
            SemesterBar bar = new SemesterBar();
            bar.label = block.semesterName;
            bar.termGpa = block.termGpa;
            bar.cumulativeGpa = block.cumulativeGpa;
            bar.passedCredits = passedCredits(block);
            a.semesterBars.add(bar);
        }
    }

    /**
     * Credits actually earned in one term: the same COMPLETED / counts-in-GPA / submitted /
     * passing filter {@link com.university.dao.GradeDAO#calculateCompletedCredits} applies in
     * SQL, reproduced here in Java over rows {@link TranscriptService} already fetched — so
     * there is no second query and no second definition of "passed", just the one TRANSCRIPT_RULES
     * filter applied per term instead of across the whole record.
     */
    private int passedCredits(TermBlock block) {
        int credits = 0;
        for (TranscriptRow row : block.rows) {
            if (row.countsTowardsGpa() && LetterGrade.fromDb(row.letterGrade).isPassing()) {
                credits += row.credits;
            }
        }
        return credits;
    }

    /* ================================================================
       ACADEMIC PROGRESS & GRADUATION PROJECTION
       ================================================================ */

    private void fillProjection(Analytics a, Transcript transcript, DegreeProgress progress) {
        List<TermBlock> terms = transcript.terms; // oldest first
        List<ProjectionPoint> points = new ArrayList<>();

        int runningPassed = 0;
        int paceCreditSum = 0;
        int paceSemesterCount = 0;

        for (TermBlock block : terms) {
            int passed = passedCredits(block);
            runningPassed += passed;

            ProjectionPoint p = new ProjectionPoint();
            p.label = block.semesterName;
            p.actual = true;
            p.perSemesterCredits = passed;
            p.cumulativeCredits = runningPassed;
            p.graduationTarget = a.requiredCredits;
            points.add(p);

            if (passed > 0) {
                paceCreditSum += passed;
                paceSemesterCount++;
            }
        }

        if (!points.isEmpty()) {
            // the projection line starts exactly where the real one stops, so the two connect
            points.get(points.size() - 1).projectedCumulativeCredits = runningPassed;
        }

        double historicalPace = paceSemesterCount > 0 ? (double) paceCreditSum / paceSemesterCount : 0.0;
        double planPace = planAveragePace(progress);
        double pace = historicalPace > 0 ? historicalPace : planPace;

        int target = a.requiredCredits;
        int cumulative = runningPassed;

        if (target <= 0 || cumulative >= target) {
            a.estimatedGraduationSemester = points.isEmpty()
                    ? "Unknown — no academic record yet"
                    : "Requirements already met";
        } else if (pace <= 0) {
            a.estimatedGraduationSemester = "Unknown — not enough data yet to project a pace";
        } else {
            TermYear cursor = lastTermYear(terms);
            String reached = null;
            int guard = 0;
            while (cumulative < target && guard < 40) {
                cursor = nextTermYear(cursor);
                int step = Math.max(1, (int) Math.round(pace));
                cumulative = Math.min(target, cumulative + step);

                ProjectionPoint p = new ProjectionPoint();
                p.label = cursor.term().getLabel() + " " + cursor.year();
                p.actual = false;
                p.projectedCumulativeCredits = cumulative;
                p.graduationTarget = target;
                points.add(p);

                guard++;
                if (cumulative >= target) {
                    reached = p.label;
                }
            }
            a.estimatedGraduationSemester = reached != null
                    ? reached
                    : "Unknown — beyond the projection horizon";
        }

        a.projection.addAll(points);
    }

    /** The average credits per stage in the student's own program Study Plan — a structural fact
     *  about the program, used only when the student has no personal pace history yet. */
    private double planAveragePace(DegreeProgress progress) {
        Map<Integer, Integer> creditsByStage = new TreeMap<>();
        for (SemesterPlan plan : progress.semesterPlans) {
            if (plan.semesterNumber <= 0) {
                continue; // no recommended semester — not part of the pacing plan
            }
            int sum = plan.courses.stream().mapToInt(c -> c.credits).sum();
            creditsByStage.merge(plan.semesterNumber, sum, Integer::sum);
        }
        if (creditsByStage.isEmpty()) {
            return 0.0;
        }
        int total = creditsByStage.values().stream().mapToInt(Integer::intValue).sum();
        return (double) total / creditsByStage.size();
    }

    private record TermYear(Term term, int year) {
    }

    private TermYear lastTermYear(List<TermBlock> terms) {
        if (terms.isEmpty()) {
            Semester current = semesterService.getCurrentSemester();
            return current != null
                    ? new TermYear(current.getTerm(), current.getStartDate().getYear())
                    : new TermYear(Term.FALL, java.time.LocalDate.now().getYear());
        }
        TermBlock last = terms.get(terms.size() - 1);
        Term term = Term.fromDb(last.term);
        int year = last.startDate != null ? last.startDate.getYear() : java.time.LocalDate.now().getYear();
        return new TermYear(term == null ? Term.FALL : term, year);
    }

    /** Fall(Y) -> Spring(Y+1) -> Fall(Y+1) -> Spring(Y+2)... matching this university's own
     *  semester-naming convention (e.g. "Fall 2023" followed by "Spring 2024"). */
    private TermYear nextTermYear(TermYear ty) {
        return switch (ty.term()) {
            case FALL -> new TermYear(Term.SPRING, ty.year() + 1);
            case SPRING, SUMMER -> new TermYear(Term.FALL, ty.year());
        };
    }
}
