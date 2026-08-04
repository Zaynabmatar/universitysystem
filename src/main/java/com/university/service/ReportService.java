package com.university.service;

import com.university.dao.ReportDAO;

import java.math.BigDecimal;
import java.util.List;

/**
 * project_details.md Section 10 — Reports &amp; Analytics.
 *
 * <p>Every number on the admin dashboard and on the reports screen comes from exactly one
 * method in this class, which is a thin shape-only layer over {@link ReportDAO} — the same
 * split every other domain in this project uses (compare {@code RecommendationService} /
 * {@code RecommendationDAO}). Controllers never write SQL.</p>
 *
 * <p>Two of the reports would read from SQL Server VIEWS ({@code vw_CourseStatistics} and
 * {@code vw_SectionDetails}) if this database had any — it does not (see
 * {@code RecommendationDAO}'s adaptation note), so {@link ReportDAO} expresses the same
 * pass-rate and fill-rate definitions as ordinary joins instead. It is still one definition,
 * in one class, shared by the dashboard, the reports screen and the AI recommendation engine.</p>
 */
public class ReportService {

    private final ReportDAO reportDao = new ReportDAO();

    // =====================================================================
    // Small carrier types. One per report. Public fields on purpose: these are
    // data rows for a table or a chart, not domain objects with behaviour.
    // =====================================================================

    /** The four KPI cards, plus the current semester name for the subtitle. */
    public static final class Kpis {
        public int totalStudents;
        public int totalInstructors;
        public int totalCourses;
        public int activeSections;
        public String currentSemester = "No current semester";
        public int enrollmentsThisSemester;
        public int studentsWaiting;
        public int studentsOnProbation;
    }

    /** One bar, one slice or one point: a label and a number. */
    public static final class Slice {
        public final String label;
        public final double value;

        public Slice(String label, double value) {
            this.label = (label == null || label.isBlank()) ? "Unknown" : label;
            this.value = value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One row of the "pass rate per course" report. */
    public static final class CourseStat {
        public String courseCode;
        public String courseTitle;
        public String deptCode;
        public int credits;
        public int timesTaken;
        public int timesPassed;
        public int timesFailed;
        public Double passRatePercent;   // null when nobody has finished the course
        public Double avgGradePoints;    // null for the same reason
        public Double avgTotalMark;      // null for the same reason
    }

    /** One row of the "section fill rate" report. */
    public static final class SectionFill {
        public int sectionId;
        public String courseCode;
        public String courseTitle;
        public String sectionNumber;
        public String instructorName;
        public String room;
        public int enrolled;
        public int capacity;
        public int seatsAvailable;
        public double fillPercent;   // 0.0 - 100.0 (divide by 100 for a ProgressBar!)
        public String status;
    }

    /** One row of the "students on probation" report. */
    public static final class ProbationRow {
        public String studentNumber;
        public String studentName;
        public String email;
        public String phone;
        public String programName;
        public BigDecimal gpa;
        public int completedCredits;
        public int probationCount;
        public String standing;
        public String status;
    }

    /** One row of the "top 10 students by GPA" report. */
    public static final class TopGpaRow {
        public int rank;   // filled in by the DAO, 1..10
        public String studentNumber;
        public String studentName;
        public String programName;
        public BigDecimal gpa;
        public int completedCredits;
        public String standing;
    }

    // =====================================================================
    // Section 10, row 1 — KPI CARDS
    // =====================================================================

    /**
     * All the dashboard numbers in one round trip to the database.
     *
     * <p>Definitions (say these out loud if asked): {@code total students} = status ACTIVE — a
     * graduated or withdrawn student is history, not a current student. {@code instructors} and
     * {@code courses} use {@code is_active = 1} (the soft-delete flag, Section 6.8).
     * {@code active sections} means status OPEN in the current semester — "can still be joined
     * right now".</p>
     */
    public Kpis getKpis() {
        return reportDao.getKpis();
    }

    // =====================================================================
    // Section 10, row 2 — ENROLLMENT COUNT PER DEPARTMENT (BarChart)
    // =====================================================================

    /** A department with zero enrollments still appears, at height 0 — a LEFT JOIN all the way down. */
    public List<Slice> enrollmentPerDepartment() {
        return reportDao.enrollmentPerDepartment();
    }

    // =====================================================================
    // Section 10, row 3 — GRADE DISTRIBUTION (PieChart)
    // =====================================================================

    /** Grouped A / B / C / D / F. Drafts and W / I are excluded — an unpublished grade is not a grade. */
    public List<Slice> gradeDistribution() {
        return reportDao.gradeDistribution();
    }

    // =====================================================================
    // Section 10, row 4 — PASS RATE PER COURSE (BarChart)
    // =====================================================================

    /**
     * @param limit how many courses to return, ranked by pass rate; pass 0 for every course
     *              (used by the CSV export). A positive limit also drops courses nobody has
     *              completed yet — a bar reading "0% pass rate" for an untaken course is a lie.
     */
    public List<CourseStat> passRatePerCourse(int limit) {
        return reportDao.passRatePerCourse(limit);
    }

    // =====================================================================
    // Section 10, row 5 — ENROLLMENT TREND ACROSS SEMESTERS (LineChart)
    // =====================================================================

    /** One point per semester, ordered by start date (never by name — "Fall" sorts before "Spring"). */
    public List<Slice> enrollmentTrend() {
        return reportDao.enrollmentTrend();
    }

    // =====================================================================
    // Section 10, row 6 — SECTION FILL RATE (Table + ProgressBar)
    // =====================================================================

    public List<SectionFill> sectionFillRates() {
        return reportDao.sectionFillRates();
    }

    // =====================================================================
    // Section 10, row 7 — STUDENTS ON PROBATION (Table)
    // =====================================================================

    /**
     * SUSPENDED students are included too: a student whose probation count reached 2 is the
     * most urgent case of all, so hiding them behind a narrow filter would defeat the report.
     */
    public List<ProbationRow> studentsOnProbation() {
        return reportDao.studentsOnProbation();
    }

    // =====================================================================
    // Section 10, row 8 — TOP 10 STUDENTS BY GPA (Table)
    // =====================================================================

    public List<TopGpaRow> topStudentsByGpa(int howMany) {
        return reportDao.topStudentsByGpa(howMany);
    }
}
