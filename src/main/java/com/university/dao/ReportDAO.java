package com.university.dao;

import com.university.service.ReportService.CourseStat;
import com.university.service.ReportService.Kpis;
import com.university.service.ReportService.ProbationRow;
import com.university.service.ReportService.SectionFill;
import com.university.service.ReportService.Slice;
import com.university.service.ReportService.TopGpaRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Every read the admin dashboard and the reports screen need. Read-only — this class contains
 * no {@code INSERT}, {@code UPDATE} or {@code DELETE}.
 *
 * <p><b>Adaptation note.</b> project_details.md Section 10/11 read {@code vw_CourseStatistics}
 * and {@code vw_SectionDetails}. This database has no views and no stored procedures (see
 * {@code RecommendationDAO}'s adaptation note), so both are expressed here as ordinary
 * parameterised queries. The pass-rate query reuses the exact "graded" definition
 * {@code RecommendationDAO} and {@code GradeDAO} already use: a grade counts once it is
 * submitted and carries a {@code result_status} — {@code W} and {@code I} never get one
 * ({@link com.university.enums.LetterGrade#toResultStatus()}), so they are excluded
 * automatically rather than by a second, possibly-drifting filter.</p>
 */
public class ReportDAO extends AbstractDAO {

    // =====================================================================
    // Section 10, row 1 — KPI CARDS
    // =====================================================================

    public Kpis getKpis() {
        String sql =
                "SELECT "
              + "  (SELECT COUNT(*) FROM dbo.students    WHERE status = N'ACTIVE')  AS total_students, "
              + "  (SELECT COUNT(*) FROM dbo.instructors WHERE is_active = 1)       AS total_instructors, "
              + "  (SELECT COUNT(*) FROM dbo.courses     WHERE is_active = 1)       AS total_courses, "
              + "  (SELECT COUNT(*) FROM dbo.sections sec "
              + "     JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id "
              + "    WHERE sem.is_current = 1 AND sec.status = N'OPEN')             AS active_sections, "
              + "  (SELECT COUNT(*) FROM dbo.enrollments e "
              + "     JOIN dbo.sections  s   ON s.section_id   = e.section_id "
              + "     JOIN dbo.semesters sem ON sem.semester_id = s.semester_id AND sem.is_current = 1 "
              + "    WHERE e.status = N'ENROLLED')                                  AS enrollments_now, "
              + "  (SELECT COUNT(*) FROM dbo.students WHERE academic_standing = N'PROBATION') AS on_probation, "
              + "  (SELECT TOP 1 semester_name FROM dbo.semesters WHERE is_current = 1)       AS current_semester";

        return queryOne(sql, resultSet -> {
            Kpis k = new Kpis();
            k.totalStudents = resultSet.getInt("total_students");
            k.totalInstructors = resultSet.getInt("total_instructors");
            k.totalCourses = resultSet.getInt("total_courses");
            k.activeSections = resultSet.getInt("active_sections");
            k.enrollmentsThisSemester = resultSet.getInt("enrollments_now");
            k.studentsOnProbation = resultSet.getInt("on_probation");
            String currentSemester = resultSet.getNString("current_semester");
            if (currentSemester != null) {
                k.currentSemester = currentSemester;
            }
            return k;
        }).orElseGet(Kpis::new);
    }

    // =====================================================================
    // Section 10, row 2 — ENROLLMENT COUNT PER DEPARTMENT (BarChart)
    // =====================================================================

    /**
     * A department is credited through the course it owns, not through the enrolled student's
     * own program — LEFT JOINed all the way down so a department with zero enrollments this
     * semester still comes back with a count of 0 rather than being silently hidden.
     */
    public List<Slice> enrollmentPerDepartment() {
        String sql =
                "SELECT d.dept_code, COUNT(e.enrollment_id) AS enrollment_count "
              + "FROM dbo.departments d "
              + "LEFT JOIN dbo.courses     c   ON c.dept_id       = d.dept_id "
              + "LEFT JOIN dbo.sections    sec ON sec.course_id   = c.course_id "
              + "LEFT JOIN dbo.semesters   sem ON sem.semester_id = sec.semester_id AND sem.is_current = 1 "
              + "LEFT JOIN dbo.enrollments e   ON e.section_id    = sec.section_id AND e.status = N'ENROLLED' "
              + "WHERE d.is_active = 1 "
              + "GROUP BY d.dept_code "
              + "ORDER BY COUNT(e.enrollment_id) DESC, d.dept_code";
        return sliceQuery(sql, "dept_code", "enrollment_count");
    }

    // =====================================================================
    // Section 10, row 3 — GRADE DISTRIBUTION (PieChart)
    // =====================================================================

    /**
     * Grouped into A / B / C / D / F because an eleven-slice pie is unreadable. Drafts
     * ({@code is_submitted = 0}) and the letters W and I are excluded — an unpublished grade is
     * not a grade, and W / I are the absence of one.
     */
    public List<Slice> gradeDistribution() {
        String sql =
                "SELECT LEFT(g.letter_grade, 1) AS grade_band, COUNT(*) AS student_count "
              + "FROM dbo.grades g "
              + "WHERE g.is_submitted = 1 "
              + "  AND g.letter_grade IS NOT NULL "
              + "  AND g.letter_grade NOT IN (N'W', N'I') "
              + "GROUP BY LEFT(g.letter_grade, 1) "
              + "ORDER BY grade_band";
        return sliceQuery(sql, "grade_band", "student_count");
    }

    // =====================================================================
    // Section 10, row 4 — PASS RATE PER COURSE (BarChart)
    // =====================================================================

    /**
     * @param limit how many courses to return, ranked by enrollment volume (times taken) so the
     *              chart shows the courses most students actually experienced — not just
     *              whichever happen to have the highest pass rate, which would cherry-pick the
     *              15 best-performing courses out of 300+ and always look uniformly high no
     *              matter how the rest of the catalogue is actually doing. 0 returns every
     *              course, ordered by code instead (used by the CSV export).
     */
    public List<CourseStat> passRatePerCourse(int limit) {
        String stats =
                "SELECT c.course_id, c.course_code, c.course_title, d.dept_code, c.credits, "
              + "       COUNT(g.grade_id) AS times_taken, "
              + "       SUM(CASE WHEN g.result_status = N'PASSED' THEN 1 ELSE 0 END) AS times_passed, "
              + "       SUM(CASE WHEN g.result_status = N'FAILED' THEN 1 ELSE 0 END) AS times_failed, "
              + "       AVG(g.grade_points) AS avg_grade_points, "
              + "       AVG(g.total_mark)   AS avg_total_mark "
              + "FROM dbo.courses c "
              + "JOIN dbo.departments d       ON d.dept_id      = c.dept_id "
              + "LEFT JOIN dbo.sections s     ON s.course_id    = c.course_id "
              + "LEFT JOIN dbo.enrollments e  ON e.section_id   = s.section_id AND e.status = N'COMPLETED' "
              + "LEFT JOIN dbo.grades g       ON g.enrollment_id = e.enrollment_id "
              + "                            AND g.is_submitted = 1 AND g.result_status IS NOT NULL "
              + "GROUP BY c.course_id, c.course_code, c.course_title, d.dept_code, c.credits";

        String sql = (limit > 0)
                ? "SELECT TOP (" + safeTop(limit) + ") course_code, course_title, dept_code, credits, "
                  + "       times_taken, times_passed, times_failed, "
                  + "       CAST(ROUND(times_passed * 100.0 / times_taken, 1) AS DECIMAL(5,1)) AS pass_rate_percent, "
                  + "       avg_grade_points, avg_total_mark "
                  + "FROM (" + stats + ") v "
                  + "WHERE times_taken > 0 "
                  + "ORDER BY times_taken DESC, course_code"
                : "SELECT course_code, course_title, dept_code, credits, "
                  + "       times_taken, times_passed, times_failed, "
                  + "       CASE WHEN times_taken = 0 THEN NULL "
                  + "            ELSE CAST(ROUND(times_passed * 100.0 / times_taken, 1) AS DECIMAL(5,1)) END AS pass_rate_percent, "
                  + "       avg_grade_points, avg_total_mark "
                  + "FROM (" + stats + ") v "
                  + "ORDER BY course_code";

        return queryList(sql, resultSet -> {
            CourseStat s = new CourseStat();
            s.courseCode = resultSet.getNString("course_code");
            s.courseTitle = resultSet.getNString("course_title");
            s.deptCode = resultSet.getNString("dept_code");
            s.credits = resultSet.getInt("credits");
            s.timesTaken = resultSet.getInt("times_taken");
            s.timesPassed = resultSet.getInt("times_passed");
            s.timesFailed = resultSet.getInt("times_failed");
            s.passRatePercent = nullableDouble(resultSet, "pass_rate_percent");
            s.avgGradePoints = nullableDouble(resultSet, "avg_grade_points");
            s.avgTotalMark = nullableDouble(resultSet, "avg_total_mark");
            return s;
        });
    }

    // =====================================================================
    // Section 10, row 5 — ENROLLMENT TREND ACROSS SEMESTERS (LineChart)
    // =====================================================================

    /**
     * Ordered by {@code start_date}, never by {@code semester_name} — "Fall 2025" sorts before
     * "Spring 2025" alphabetically, which would draw the line backwards. Every semester appears,
     * even one with no enrollments yet, so the line has no gap; DROPPED enrollments are excluded.
     */
    public List<Slice> enrollmentTrend() {
        String sql =
                "SELECT sem.semester_name, COUNT(e.enrollment_id) AS enrollment_count "
              + "FROM dbo.semesters sem "
              + "LEFT JOIN dbo.sections    sec ON sec.semester_id = sem.semester_id "
              + "LEFT JOIN dbo.enrollments e   ON e.section_id    = sec.section_id AND e.status <> N'DROPPED' "
              + "GROUP BY sem.semester_id, sem.semester_name, sem.start_date "
              + "ORDER BY sem.start_date";
        return sliceQuery(sql, "semester_name", "enrollment_count");
    }

    // =====================================================================
    // Section 10, row 6 — SECTION FILL RATE (Table + ProgressBar)
    // =====================================================================

    public List<SectionFill> sectionFillRates() {
        String sql =
                "SELECT sec.section_id, c.course_code, c.course_title, sec.section_number, "
              + "       CASE WHEN i.instructor_id IS NULL THEN N'TBA' "
              + "            ELSE i.first_name + N' ' + i.last_name END AS instructor_name, "
              + "       sec.room, sec.enrolled_count, sec.capacity, "
              + "       (sec.capacity - sec.enrolled_count) AS seats_available, "
              + "       CAST(ROUND(sec.enrolled_count * 100.0 / sec.capacity, 1) AS DECIMAL(5,1)) AS fill_rate_percent, "
              + "       sec.status "
              + "FROM dbo.sections sec "
              + "JOIN dbo.courses c    ON c.course_id    = sec.course_id "
              + "JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id AND sem.is_current = 1 "
              + "LEFT JOIN dbo.instructors i ON i.instructor_id = sec.instructor_id "
              + "ORDER BY fill_rate_percent DESC, c.course_code, sec.section_number";

        return queryList(sql, resultSet -> {
            SectionFill f = new SectionFill();
            f.sectionId = resultSet.getInt("section_id");
            f.courseCode = resultSet.getNString("course_code");
            f.courseTitle = resultSet.getNString("course_title");
            f.sectionNumber = resultSet.getNString("section_number");
            f.instructorName = resultSet.getNString("instructor_name");
            f.room = resultSet.getNString("room");
            f.enrolled = resultSet.getInt("enrolled_count");
            f.capacity = resultSet.getInt("capacity");
            f.seatsAvailable = resultSet.getInt("seats_available");
            Double pct = nullableDouble(resultSet, "fill_rate_percent");
            f.fillPercent = (pct == null ? 0.0 : pct);
            f.status = resultSet.getNString("status");
            return f;
        });
    }

    // =====================================================================
    // Section 10, row 7 — STUDENTS ON PROBATION (Table)
    // =====================================================================

    /**
     * SUSPENDED is included on purpose: it is what PROBATION becomes once
     * {@code probation_count} reaches 2, and it is the most urgent row of all.
     */
    public List<ProbationRow> studentsOnProbation() {
        String sql =
                "SELECT st.user_id, st.first_name, st.last_name, st.email, st.phone, "
              + "       p.program_name, st.cumulative_gpa, st.completed_credits, "
              + "       st.probation_count, st.academic_standing, st.status "
              + "FROM dbo.students st "
              + "JOIN dbo.programs p ON p.program_id = st.program_id "
              + "WHERE st.academic_standing IN (N'PROBATION', N'SUSPENDED') "
              + "ORDER BY st.cumulative_gpa ASC, st.user_id";

        return queryList(sql, resultSet -> {
            ProbationRow r = new ProbationRow();
            r.studentUserId = resultSet.getInt("user_id");
            r.studentName = resultSet.getNString("first_name") + " " + resultSet.getNString("last_name");
            r.email = resultSet.getNString("email");
            r.phone = resultSet.getNString("phone");
            r.programName = resultSet.getNString("program_name");
            r.gpa = resultSet.getBigDecimal("cumulative_gpa");
            r.completedCredits = resultSet.getInt("completed_credits");
            r.probationCount = resultSet.getInt("probation_count");
            r.standing = resultSet.getNString("academic_standing");
            r.status = resultSet.getNString("status");
            return r;
        });
    }

    // =====================================================================
    // Section 10, row 8 — TOP 10 STUDENTS BY GPA (Table)
    // =====================================================================

    /**
     * {@code completed_credits > 0} is deliberate: a brand-new student has a GPA of 0.00 and no
     * completed courses, and must not appear in a "top students" list.
     */
    public List<TopGpaRow> topStudentsByGpa(int howMany) {
        String sql =
                "SELECT TOP (" + safeTop(howMany) + ") st.user_id, st.first_name, st.last_name, "
              + "       p.program_name, st.cumulative_gpa, st.completed_credits, st.academic_standing "
              + "FROM dbo.students st "
              + "JOIN dbo.programs p ON p.program_id = st.program_id "
              + "WHERE st.completed_credits > 0 AND st.status = N'ACTIVE' "
              + "ORDER BY st.cumulative_gpa DESC, st.completed_credits DESC, st.user_id";

        List<TopGpaRow> out = new ArrayList<>();
        int[] rank = {0};
        out.addAll(queryList(sql, resultSet -> {
            TopGpaRow r = new TopGpaRow();
            r.rank = ++rank[0];
            r.studentUserId = resultSet.getInt("user_id");
            r.studentName = resultSet.getNString("first_name") + " " + resultSet.getNString("last_name");
            r.programName = resultSet.getNString("program_name");
            r.gpa = resultSet.getBigDecimal("cumulative_gpa");
            r.completedCredits = resultSet.getInt("completed_credits");
            r.standing = resultSet.getNString("academic_standing");
            return r;
        }));
        return out;
    }

    // =====================================================================
    // internals
    // =====================================================================

    /** Runs a two-column "label, number" query and turns it into chart slices. */
    private List<Slice> sliceQuery(String sql, String labelCol, String valueCol) {
        return queryList(sql, resultSet ->
                new Slice(resultSet.getNString(labelCol), resultSet.getDouble(valueCol)));
    }

    /**
     * SQL Server does not accept a parameter marker inside {@code TOP (?)} in every context, so
     * the number is inlined here — but only after being forced into a small positive integer.
     * No user text ever reaches this method; every other value in every query above is bound
     * through {@link #bind}.
     */
    private String safeTop(int n) {
        int bounded = n;
        if (bounded < 1) {
            bounded = 1;
        }
        if (bounded > 1000) {
            bounded = 1000;
        }
        return Integer.toString(bounded);
    }

    /** {@code getDouble()} returns 0.0 for SQL NULL, which would be a lie. This returns null instead. */
    private Double nullableDouble(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }
}
