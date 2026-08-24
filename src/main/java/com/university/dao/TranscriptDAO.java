package com.university.dao;

import com.university.service.TranscriptService.DegreeProgress;
import com.university.service.TranscriptService.RequirementRow;
import com.university.service.TranscriptService.Transcript;
import com.university.service.TranscriptService.TranscriptRow;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Optional;

/**
 * Every read behind the transcript and the degree-progress screens.
 *
 * <p><b>Why this class exists.</b> The phase document reads
 * {@code vw_StudentTranscript}, {@code vw_StudentGPASummary} and
 * {@code sp_GetStudentDegreeProgress}. This database has neither views nor stored
 * procedures, so those reads are expressed here as ordinary parameterised queries —
 * the same columns, the same ordering, the same rules.</p>
 *
 * <p><b>The rules the document delegates to the view, kept here instead:</b></p>
 * <ul>
 *   <li>a {@code DROPPED} enrolment never appears (Section 6.4);</li>
 *   <li>a mark with {@code is_submitted = 0} is invisible — a student never sees a draft
 *       (Phase 11, rule G4);</li>
 *   <li>a submitted mark still stays hidden until the student completes this enrollment's
 *       instructor evaluation (or the evaluation window has closed) — the same gate
 *       {@link GradeDAO#findStudentGradeRows} applies to My Grades;</li>
 *   <li>a {@code WITHDRAWN} enrolment shows the letter {@code W} with no marks and no points
 *       (Section 6.4);</li>
 *   <li>rows come back ordered by {@code semesters.start_date}, never by {@code semester_name} —
 *       "Fall 2025" sorts before "Spring 2024" alphabetically and would reverse the transcript.</li>
 * </ul>
 *
 * <p>The GPA filter in {@code TranscriptService.TranscriptRow.countsTowardsGpa()} is the Java
 * twin of the WHERE clause in {@link GradeDAO} — that is what makes the running cumulative
 * under the last term equal {@code students.cumulative_gpa}.</p>
 *
 * <p>This class imports the data carriers from the service layer on purpose: the phase document
 * declares them as nested types of {@code TranscriptService}, and keeping SQL out of the service
 * package is the stronger convention in this codebase (no service holds a SQL string).</p>
 */
public class TranscriptDAO extends AbstractDAO {

    /** Passed = a completed, submitted, PASSED attempt of that course. */
    private static final String PASSED_EXISTS =
            "EXISTS (SELECT 1 FROM dbo.enrollments pe "
          + "        INNER JOIN dbo.sections psec ON psec.section_id = pe.section_id "
          + "        INNER JOIN dbo.grades pg     ON pg.enrollment_id = pe.enrollment_id "
          + "        WHERE pe.student_id = s.student_id AND psec.course_id = pr.course_id "
          + "          AND pe.status = 'COMPLETED' AND pg.is_submitted = 1 "
          + "          AND pg.result_status = 'PASSED')";

    /** In progress = sitting in it right now. */
    private static final String ENROLLED_EXISTS =
            "EXISTS (SELECT 1 FROM dbo.enrollments ee "
          + "        INNER JOIN dbo.sections esec ON esec.section_id = ee.section_id "
          + "        WHERE ee.student_id = s.student_id AND esec.course_id = pr.course_id "
          + "          AND ee.status = 'ENROLLED')";

    /** Failed = a completed, submitted, failing attempt (Study Plan resolves PASSED/ENROLLED first). */
    private static final String FAILED_EXISTS =
            "EXISTS (SELECT 1 FROM dbo.enrollments fe "
          + "        INNER JOIN dbo.sections fsec ON fsec.section_id = fe.section_id "
          + "        INNER JOIN dbo.grades fg     ON fg.enrollment_id = fe.enrollment_id "
          + "        WHERE fe.student_id = s.student_id AND fsec.course_id = pr.course_id "
          + "          AND fe.status = 'COMPLETED' AND fg.is_submitted = 1 "
          + "          AND fg.result_status = 'FAILED')";

    private static final String SQL_HEADER =
            "SELECT s.student_id, s.user_id, s.first_name, s.last_name, "
          + "       s.admission_date, s.academic_standing, s.cumulative_gpa, s.completed_credits, "
          + "       p.program_name, p.total_credits_required "
          + "FROM dbo.students s "
          + "INNER JOIN dbo.programs p ON p.program_id = s.program_id "
          + "WHERE s.student_id = ?";

    /**
     * Same evaluation gate {@code GradeDAO.findStudentGradeRows} applies to the course's final
     * result: BOTH the section submitted/locked ({@code is_submitted = 1}) AND the student having
     * completed this enrollment's instructor evaluation, unless the evaluation window has already
     * closed (Section 6.6 / GradeDAO). A grade hidden in My Grades for this reason must stay
     * hidden on the Transcript too — this is that same condition, kept in sync on purpose.
     */
    private static final String EVALUATION_GATE =
            "(ev.evaluation_done = 1 OR (sem.evaluation_end IS NOT NULL AND SYSDATETIME() > sem.evaluation_end))";

    private static final String SQL_ROWS =
            "SELECT sem.semester_id, sem.semester_name, sem.academic_year, sem.term, sem.start_date, "
          + "       c.course_id, c.course_code, c.course_title, c.credits, "
          + "       sec.section_number, "
          + "       CASE WHEN i.instructor_id IS NULL THEN NULL "
          + "            ELSE i.first_name + N' ' + i.last_name END AS instructor_name, "
          + "       e.enrollment_id, e.status, e.is_repeat, e.counts_in_gpa, "
          + "       CASE WHEN e.status <> 'WITHDRAWN' AND g.is_submitted = 1 AND " + EVALUATION_GATE + " "
          + "            THEN g.total_mark END AS total_mark, "
          + "       CASE WHEN e.status = 'WITHDRAWN' THEN N'W' "
          + "            WHEN g.is_submitted = 1 AND " + EVALUATION_GATE + " THEN g.letter_grade END AS letter_grade, "
          + "       CASE WHEN e.status <> 'WITHDRAWN' AND g.is_submitted = 1 AND " + EVALUATION_GATE + " "
          + "            THEN g.grade_points END AS grade_points, "
          + "       CASE WHEN e.status <> 'WITHDRAWN' AND g.is_submitted = 1 AND " + EVALUATION_GATE + " "
          + "            THEN g.grade_points * c.credits END AS quality_points "
          + "FROM dbo.enrollments e "
          + "INNER JOIN dbo.sections sec   ON sec.section_id  = e.section_id "
          + "INNER JOIN dbo.semesters sem  ON sem.semester_id = sec.semester_id "
          + "INNER JOIN dbo.courses c      ON c.course_id     = sec.course_id "
          + "LEFT JOIN  dbo.instructors i  ON i.instructor_id = sec.instructor_id "
          + "LEFT JOIN  dbo.grades g       ON g.enrollment_id = e.enrollment_id "
          + "CROSS APPLY (SELECT CASE WHEN EXISTS ("
          + "     SELECT 1 FROM dbo.instructor_evaluations ie "
          + "     WHERE ie.enrollment_id = e.enrollment_id) THEN 1 ELSE 0 END AS evaluation_done) ev "
          + "WHERE e.student_id = ? AND e.status <> 'DROPPED' "
          + "ORDER BY sem.start_date, sem.semester_id, c.course_code";

    private static final String SQL_PROGRESS_SUMMARY =
            "SELECT s.student_id, s.user_id, s.first_name, s.last_name, s.program_id, "
          + "       s.cumulative_gpa, s.completed_credits, "
          + "       p.program_name, p.total_credits_required, "
          + "       (SELECT COUNT(*) FROM dbo.program_requirements pr "
          + "         WHERE pr.program_id = s.program_id AND pr.is_mandatory = 1) AS mandatory_total, "
          + "       (SELECT COUNT(*) FROM dbo.program_requirements pr "
          + "         WHERE pr.program_id = s.program_id AND pr.is_mandatory = 1 "
          + "           AND " + PASSED_EXISTS + ") AS mandatory_passed "
          + "FROM dbo.students s "
          + "INNER JOIN dbo.programs p ON p.program_id = s.program_id "
          + "WHERE s.student_id = ?";

    private static final String SQL_DEGREE_PLAN =
            "SELECT c.course_id, c.course_code, c.course_title, c.credits, "
          + "       pr.is_mandatory, pr.requirement_type, pr.recommended_semester, pr.min_completed_credits, "
          + "       CASE WHEN " + PASSED_EXISTS + "   THEN N'PASSED' "
          + "            WHEN " + ENROLLED_EXISTS + " THEN N'IN_PROGRESS' "
          + "            WHEN " + FAILED_EXISTS + "   THEN N'FAILED' "
          + "            ELSE N'NONE' END AS attempt_status "
          + "FROM dbo.students s "
          + "INNER JOIN dbo.program_requirements pr ON pr.program_id = s.program_id "
          + "INNER JOIN dbo.courses c               ON c.course_id   = pr.course_id "
          + "WHERE s.student_id = ? "
          + "ORDER BY pr.recommended_semester, c.course_code";

    /**
     * Every prerequisite relationship touching this program's own curriculum — one row per
     * (dependent course, prerequisite course) pair, both already courses the program requires.
     * Loaded once per program rather than once per course: the curriculum is 30-70 rows, and a
     * per-course query would be a per-course round trip for no reason.
     */
    private static final String SQL_PROGRAM_PREREQUISITES =
            "SELECT cp.course_id, cp.prerequisite_course_id, "
          + "       pc.course_code AS prereq_code, pc.course_title AS prereq_title "
          + "FROM dbo.course_prerequisites cp "
          + "INNER JOIN dbo.courses pc ON pc.course_id = cp.prerequisite_course_id "
          + "WHERE cp.course_id IN (SELECT pr.course_id FROM dbo.program_requirements pr "
          + "                        WHERE pr.program_id = ?)";

    /** The student box at the top of the transcript, plus the authoritative cached figures. */
    public Optional<Transcript> findHeader(int studentId) {
        return queryOne(SQL_HEADER, resultSet -> {
            Transcript transcript      = new Transcript();
            transcript.studentId       = resultSet.getInt("student_id");
            transcript.studentUserId   = resultSet.getInt("user_id");
            transcript.firstName       = resultSet.getNString("first_name");
            transcript.lastName        = resultSet.getNString("last_name");
            transcript.programName     = resultSet.getNString("program_name");
            transcript.academicStanding = resultSet.getNString("academic_standing");
            Date admission             = resultSet.getDate("admission_date");
            transcript.admissionDate   = admission == null ? null : admission.toLocalDate();
            transcript.cumulativeGpa   = orZero(resultSet.getBigDecimal("cumulative_gpa"));
            transcript.creditsEarned   = resultSet.getInt("completed_credits");
            transcript.creditsRequired = resultSet.getInt("total_credits_required");
            return transcript;
        }, studentId);
    }

    /** Every transcript line, oldest semester first, alphabetical within a semester. */
    public List<TranscriptRow> findTranscriptRows(int studentId) {
        return queryList(SQL_ROWS, resultSet -> {
            TranscriptRow row     = new TranscriptRow();
            row.semesterId        = resultSet.getInt("semester_id");
            row.semesterName      = resultSet.getNString("semester_name");
            row.academicYear      = resultSet.getNString("academic_year");
            row.term              = resultSet.getNString("term");
            Date start            = resultSet.getDate("start_date");
            row.semesterStart     = start == null ? null : start.toLocalDate();
            row.courseId          = resultSet.getInt("course_id");
            row.courseCode        = resultSet.getNString("course_code");
            row.courseTitle       = resultSet.getNString("course_title");
            row.credits           = resultSet.getInt("credits");
            row.sectionNumber     = resultSet.getNString("section_number");
            row.instructorName    = resultSet.getNString("instructor_name");
            row.enrollmentId      = resultSet.getInt("enrollment_id");
            row.enrollmentStatus  = resultSet.getNString("status");
            row.isRepeat          = resultSet.getBoolean("is_repeat");
            row.countsInGpa       = resultSet.getBoolean("counts_in_gpa");
            row.totalMark         = resultSet.getBigDecimal("total_mark");
            row.letterGrade       = resultSet.getNString("letter_grade");
            row.gradePoints       = resultSet.getBigDecimal("grade_points");
            row.qualityPoints     = resultSet.getBigDecimal("quality_points");
            return row;
        }, studentId);
    }

    /** The credit and mandatory-course counts behind the three Section 6.9 conditions. */
    public Optional<DegreeProgress> findProgressSummary(int studentId) {
        return queryOne(SQL_PROGRESS_SUMMARY, resultSet -> {
            DegreeProgress progress   = new DegreeProgress();
            progress.studentId        = resultSet.getInt("student_id");
            progress.studentUserId    = resultSet.getInt("user_id");
            progress.programId        = resultSet.getInt("program_id");
            progress.fullName         = (resultSet.getNString("first_name") + " "
                                       + resultSet.getNString("last_name")).trim();
            progress.programName      = resultSet.getNString("program_name");
            progress.cumulativeGpa    = orZero(resultSet.getBigDecimal("cumulative_gpa"));
            progress.creditsCompleted = resultSet.getInt("completed_credits");
            progress.creditsRequired  = resultSet.getInt("total_credits_required");
            progress.mandatoryTotal   = resultSet.getInt("mandatory_total");
            progress.mandatoryPassed  = resultSet.getInt("mandatory_passed");
            return progress;
        }, studentId);
    }

    /**
     * One row per course of the student's degree plan. {@code courseStatus} carries only the
     * raw attempt fact at this point — {@code PASSED}, {@code IN_PROGRESS}, {@code FAILED} or
     * {@code NONE} — because whether a {@code NONE} course is actually {@code ELIGIBLE},
     * {@code LOCKED} or {@code NOT_YET_AVAILABLE} depends on prerequisites and on every other
     * course's status too, which needs the whole plan assembled first; that refinement is
     * {@link com.university.service.TranscriptService#buildStudyPlan}'s job, not a single row's.
     */
    public List<RequirementRow> findDegreePlan(int studentId) {
        return queryList(SQL_DEGREE_PLAN, resultSet -> {
            RequirementRow row = new RequirementRow();
            row.courseId       = resultSet.getInt("course_id");
            row.courseCode     = resultSet.getNString("course_code");
            row.courseTitle    = resultSet.getNString("course_title");
            row.credits        = resultSet.getInt("credits");
            row.isMandatory    = resultSet.getBoolean("is_mandatory");
            row.requirementType = resultSet.getNString("requirement_type");
            int recommended    = resultSet.getInt("recommended_semester");
            row.recommendedSemester = resultSet.wasNull() ? null : recommended;
            int minCredits     = resultSet.getInt("min_completed_credits");
            row.minCompletedCredits = resultSet.wasNull() ? null : minCredits;
            row.courseStatus   = resultSet.getNString("attempt_status");
            return row;
        }, studentId);
    }

    /** One row per prerequisite link, for every course this program's plan requires. */
    public List<PrerequisiteLink> findProgramPrerequisites(int programId) {
        return queryList(SQL_PROGRAM_PREREQUISITES, resultSet -> {
            PrerequisiteLink link = new PrerequisiteLink();
            link.courseId             = resultSet.getInt("course_id");
            link.prerequisiteCourseId = resultSet.getInt("prerequisite_course_id");
            link.prerequisiteCode     = resultSet.getNString("prereq_code");
            link.prerequisiteTitle    = resultSet.getNString("prereq_title");
            return link;
        }, programId);
    }

    /** One (course, prerequisite) pair from {@code dbo.course_prerequisites}, with the prerequisite's own label. */
    public static class PrerequisiteLink {
        public int    courseId;
        public int    prerequisiteCourseId;
        public String prerequisiteCode;
        public String prerequisiteTitle;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
