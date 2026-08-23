package com.university.dao;

import com.university.enums.EnrollmentStatus;
import com.university.enums.LetterGrade;
import com.university.enums.ResultStatus;
import com.university.model.Grade;
import com.university.model.GradeSheetRow;
import com.university.model.StudentGradeRow;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.grades}.
 *
 * <p>Every mark column is nullable, so the mapper reads them as objects. A
 * missing mark stays null and does not become zero, which matters because
 * zero is a real mark. {@code letter_grade} is written as
 * {@link LetterGrade#toDb()}, not the enum's {@code name()} — {@code A-} and
 * {@code B+} are not legal Java identifiers, so the constant names
 * ({@code A_MINUS}, {@code B_PLUS}) differ from the string the schema and the
 * UI use.</p>
 */
public class GradeDAO extends AbstractDAO implements GenericDAO<Grade> {

    private static final String SELECT =
            "SELECT grade_id, enrollment_id, coursework_mark, midterm_mark, lab_mark, final_mark, "
            + "total_mark, letter_grade, grade_points, result_status, is_submitted, "
            + "coursework_published, midterm_published, lab_published, final_published, "
            + "coursework_published_mark, midterm_published_mark, lab_published_mark, final_published_mark, "
            + "submitted_by, submitted_at, last_modified_by, last_modified_at FROM dbo.grades";

    private static final String INSERT =
            "INSERT INTO dbo.grades (enrollment_id, coursework_mark, midterm_mark, lab_mark, final_mark, "
            + "total_mark, letter_grade, grade_points, result_status, is_submitted, "
            + "coursework_published, midterm_published, lab_published, final_published, "
            + "submitted_by, submitted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.grades SET coursework_mark = ?, midterm_mark = ?, lab_mark = ?, final_mark = ?, "
            + "total_mark = ?, letter_grade = ?, grade_points = ?, result_status = ?, is_submitted = ?, "
            + "coursework_published = ?, midterm_published = ?, lab_published = ?, final_published = ?, "
            // A mark the instructor just cleared (now NULL) can never leave a stale published
            // snapshot behind for the student to keep seeing -- when the new raw mark is NULL the
            // published snapshot is cleared with it; otherwise it is left exactly as it was (only
            // publishComponents ever moves it forward to a non-null value).
            + "coursework_published_mark = CASE WHEN ? IS NULL THEN NULL ELSE coursework_published_mark END, "
            + "midterm_published_mark    = CASE WHEN ? IS NULL THEN NULL ELSE midterm_published_mark    END, "
            + "lab_published_mark        = CASE WHEN ? IS NULL THEN NULL ELSE lab_published_mark        END, "
            + "final_published_mark      = CASE WHEN ? IS NULL THEN NULL ELSE final_published_mark      END, "
            + "last_modified_by = ?, last_modified_at = ? WHERE grade_id = ? AND is_submitted = 0";

    private static final String DELETE = "DELETE FROM dbo.grades WHERE grade_id = ?";

    private static final RowMapper<Grade> MAPPER = GradeDAO::mapRow;

    static Grade mapRow(ResultSet rs) throws SQLException {
        Grade grade = new Grade();
        grade.setGradeId(rs.getInt("grade_id"));
        grade.setEnrollmentId(rs.getInt("enrollment_id"));
        grade.setCourseworkMark(rs.getBigDecimal("coursework_mark"));
        grade.setMidtermMark(rs.getBigDecimal("midterm_mark"));
        grade.setLabMark(rs.getBigDecimal("lab_mark"));
        grade.setFinalMark(rs.getBigDecimal("final_mark"));
        grade.setTotalMark(rs.getBigDecimal("total_mark"));
        grade.setLetterGrade(LetterGrade.fromDb(rs.getString("letter_grade")));
        grade.setGradePoints(rs.getBigDecimal("grade_points"));
        grade.setResultStatus(ResultStatus.fromDb(rs.getString("result_status")));
        grade.setSubmitted(rs.getBoolean("is_submitted"));
        grade.setCourseworkPublished(rs.getBoolean("coursework_published"));
        grade.setMidtermPublished(rs.getBoolean("midterm_published"));
        grade.setLabPublished(rs.getBoolean("lab_published"));
        grade.setFinalPublished(rs.getBoolean("final_published"));
        grade.setCourseworkPublishedMark(rs.getBigDecimal("coursework_published_mark"));
        grade.setMidtermPublishedMark(rs.getBigDecimal("midterm_published_mark"));
        grade.setLabPublishedMark(rs.getBigDecimal("lab_published_mark"));
        grade.setFinalPublishedMark(rs.getBigDecimal("final_published_mark"));
        grade.setSubmittedBy(DaoUtils.getInteger(rs, "submitted_by"));
        grade.setSubmittedAt(DaoUtils.getLocalDateTime(rs, "submitted_at"));
        grade.setLastModifiedBy(DaoUtils.getInteger(rs, "last_modified_by"));
        grade.setLastModifiedAt(DaoUtils.getLocalDateTime(rs, "last_modified_at"));
        return grade;
    }

    @Override
    public Optional<Grade> findById(int id) {
        return queryOne(SELECT + " WHERE grade_id = ?", MAPPER, id);
    }

    @Override
    public List<Grade> findAll() {
        return queryList(SELECT + " ORDER BY grade_id", MAPPER);
    }

    /** The grade belonging to one registration, the usual way in. */
    public Optional<Grade> findByEnrollment(int enrollmentId) {
        return queryOne(SELECT + " WHERE enrollment_id = ?", MAPPER, enrollmentId);
    }

    /** Every grade in one section, for the mark sheet. */
    public List<Grade> findBySection(int sectionId) {
        return queryList("SELECT g.grade_id, g.enrollment_id, g.coursework_mark, g.midterm_mark, "
                        + "g.final_mark, g.total_mark, g.letter_grade, g.grade_points, "
                        + "g.result_status, g.is_submitted, g.submitted_by, g.submitted_at, "
                        + "g.last_modified_by, g.last_modified_at "
                        + "FROM dbo.grades g "
                        + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                        + "WHERE e.section_id = ? ORDER BY e.student_id",
                MAPPER, sectionId);
    }

    /** Every grade one student has earned, for the transcript. */
    public List<Grade> findByStudent(int studentId) {
        return queryList("SELECT g.grade_id, g.enrollment_id, g.coursework_mark, g.midterm_mark, "
                        + "g.final_mark, g.total_mark, g.letter_grade, g.grade_points, "
                        + "g.result_status, g.is_submitted, g.submitted_by, g.submitted_at, "
                        + "g.last_modified_by, g.last_modified_at "
                        + "FROM dbo.grades g "
                        + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                        + "WHERE e.student_id = ? ORDER BY g.grade_id",
                MAPPER, studentId);
    }

    /** The grades a student may actually see, meaning the submitted ones. */
    public List<Grade> findSubmittedByStudent(int studentId) {
        return queryList("SELECT g.grade_id, g.enrollment_id, g.coursework_mark, g.midterm_mark, "
                        + "g.final_mark, g.total_mark, g.letter_grade, g.grade_points, "
                        + "g.result_status, g.is_submitted, g.submitted_by, g.submitted_at, "
                        + "g.last_modified_by, g.last_modified_at "
                        + "FROM dbo.grades g "
                        + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                        + "WHERE e.student_id = ? AND g.is_submitted = 1 ORDER BY g.grade_id",
                MAPPER, studentId);
    }

    /**
     * RULE R4 — the letter grade of a course this student has already passed, if any.
     *
     * <p>Picks the best attempt when more than one exists (should not normally happen, since a
     * passed course blocks further registration, but a defensive {@code ORDER BY} costs nothing).</p>
     */
    public Optional<LetterGrade> findPassedLetterGrade(int studentId, int courseId) {
        return queryOne("SELECT TOP 1 g.letter_grade FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "WHERE e.student_id = ? AND s.course_id = ? AND e.status = 'COMPLETED' "
                + "AND g.is_submitted = 1 AND g.result_status = 'PASSED' "
                + "ORDER BY g.grade_points DESC",
                rs -> LetterGrade.fromDb(rs.getString("letter_grade")), studentId, courseId);
    }

    /**
     * Recomputes the cumulative grade point average from the submitted grades.
     *
     * <p>Weighted by course credits, limited to the attempts still marked as counting (Section
     * 5.5 repeat policy), and excluding W/I (Section 5.2) even though a withdrawn enrolment
     * should already have {@code counts_in_gpa = 0} — the extra clause matches
     * project_details.md's own query character for character.</p>
     *
     * @return the average rounded HALF_UP to two decimals, or zero when nothing counts
     */
    public BigDecimal calculateCumulativeGpa(int studentId) {
        return gpaQuery(null, studentId, null);
    }

    public BigDecimal calculateCumulativeGpa(Connection connection, int studentId) {
        return gpaQuery(connection, studentId, null);
    }

    /** Term GPA - the same formula, restricted to one semester's sections (Section 5.3). */
    public BigDecimal calculateTermGpa(int studentId, int semesterId) {
        return gpaQuery(null, studentId, semesterId);
    }

    public BigDecimal calculateTermGpa(Connection connection, int studentId, int semesterId) {
        return gpaQuery(connection, studentId, semesterId);
    }

    private BigDecimal gpaQuery(Connection connection, int studentId, Integer semesterId) {
        String sql = "SELECT CAST(CASE WHEN SUM(c.credits) IS NULL OR SUM(c.credits) = 0 THEN 0 "
                + "ELSE SUM(g.grade_points * c.credits) / SUM(c.credits) END AS DECIMAL(5,4)) "
                + "FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.courses c ON c.course_id = s.course_id "
                + "WHERE e.student_id = ? AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1 "
                + "AND g.is_submitted = 1 AND g.letter_grade NOT IN ('W', 'I')"
                + (semesterId == null ? "" : " AND s.semester_id = ?");

        Optional<BigDecimal> value;
        if (connection == null) {
            value = semesterId == null
                    ? queryOne(sql, rs -> rs.getBigDecimal(1), studentId)
                    : queryOne(sql, rs -> rs.getBigDecimal(1), studentId, semesterId);
        } else {
            value = semesterId == null
                    ? queryOne(connection, sql, rs -> rs.getBigDecimal(1), studentId)
                    : queryOne(connection, sql, rs -> rs.getBigDecimal(1), studentId, semesterId);
        }

        BigDecimal raw = value.orElse(BigDecimal.ZERO);
        return raw.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** The credits a student has actually earned, meaning passed. */
    public int calculateCompletedCredits(int studentId) {
        return calculateCompletedCredits(null, studentId);
    }

    public int calculateCompletedCredits(Connection connection, int studentId) {
        String sql = "SELECT ISNULL(SUM(c.credits), 0) FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.courses c ON c.course_id = s.course_id "
                + "WHERE e.student_id = ? AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1 "
                + "AND g.is_submitted = 1 AND g.result_status = 'PASSED'";

        return connection == null
                ? queryInt(sql, studentId)
                : queryInt(connection, sql, studentId);
    }

    /** How many grades in a section are still unsubmitted. */
    public int countUnsubmittedInSection(int sectionId) {
        return queryInt("SELECT COUNT(*) FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "WHERE e.section_id = ? AND g.is_submitted = 0", sectionId);
    }

    /**
     * The instructor's grade sheet: every ENROLLED or COMPLETED student in the section, joined
     * with whatever grade row already exists for them. A student with no grade row yet gets one
     * with every mark blank — that is what the LEFT JOIN is for.
     */
    public List<GradeSheetRow> findSectionRoster(int sectionId) {
        String sql = "SELECT e.enrollment_id, e.student_id, st.user_id, st.first_name, st.last_name, "
                + "g.grade_id, g.coursework_mark, g.midterm_mark, g.lab_mark, g.final_mark, "
                + "g.is_submitted, g.coursework_published, g.midterm_published, g.lab_published, "
                + "g.final_published, c.has_lab, c.coursework_weight, c.midterm_weight, c.final_weight, "
                + "c.coursework_max_mark, c.midterm_max_mark, c.final_max_mark "
                + "FROM dbo.enrollments e "
                + "INNER JOIN dbo.students st ON st.student_id = e.student_id "
                + "INNER JOIN dbo.sections sec ON sec.section_id = e.section_id "
                + "INNER JOIN dbo.courses c ON c.course_id = sec.course_id "
                + "LEFT JOIN dbo.grades g ON g.enrollment_id = e.enrollment_id "
                + "WHERE e.section_id = ? AND e.status IN ('ENROLLED', 'COMPLETED') "
                + "ORDER BY st.user_id";
        return queryList(sql, rs -> {
            GradeSheetRow row = new GradeSheetRow();
            row.setEnrollmentId(rs.getInt("enrollment_id"));
            row.setStudentId(rs.getInt("student_id"));
            row.setStudentUserId(rs.getInt("user_id"));
            row.setStudentName(rs.getString("first_name") + " " + rs.getString("last_name"));
            row.setGradeId(DaoUtils.getInteger(rs, "grade_id"));
            row.setCourseworkMark(rs.getBigDecimal("coursework_mark"));
            row.setMidtermMark(rs.getBigDecimal("midterm_mark"));
            row.setLabMark(rs.getBigDecimal("lab_mark"));
            row.setFinalMark(rs.getBigDecimal("final_mark"));
            row.setHasLab(rs.getBoolean("has_lab"));
            row.setCourseworkWeight(rs.getBigDecimal("coursework_weight"));
            row.setMidtermWeight(rs.getBigDecimal("midterm_weight"));
            row.setFinalWeight(rs.getBigDecimal("final_weight"));
            row.setCourseworkMaxMark(rs.getBigDecimal("coursework_max_mark"));
            row.setMidtermMaxMark(rs.getBigDecimal("midterm_max_mark"));
            row.setFinalMaxMark(rs.getBigDecimal("final_max_mark"));
            row.setSubmitted(rs.getBoolean("is_submitted"));
            row.setCourseworkPublished(rs.getBoolean("coursework_published"));
            row.setMidtermPublished(rs.getBoolean("midterm_published"));
            row.setLabPublished(rs.getBoolean("lab_published"));
            row.setFinalPublished(rs.getBoolean("final_published"));
            row.recompute();
            return row;
        }, sectionId);
    }

    /**
     * The student's My Grades list: every enrollment they still hold, with the grade attached
     * ONLY when it has been submitted.
     *
     * <p>The mark columns are read through {@code CASE WHEN g.is_submitted = 1} rather than being
     * filtered out afterwards, so an unpublished draft never leaves the database — Section 6.6:
     * "a student must never see a draft". Only an active enrollment (ENROLLED or COMPLETED, the
     * same test as {@link com.university.enums.EnrollmentStatus#occupiesSeat()}) appears here —
     * dropped and withdrawn rows are left out, matching the instructor's own section roster
     * ({@link #findSectionRoster}) so the two sides of one grade never disagree on who is still
     * on the list. The W itself is not lost: it stays on the transcript
     * ({@link com.university.dao.TranscriptDAO}).</p>
     *
     * <p>Midterm is revealed the moment it is published, independent of everything else. Every
     * other component — Coursework/Lab, Final — belongs to the final result, so each of those
     * also requires the student to have already completed this enrollment's instructor evaluation
     * ({@code dbo.instructor_evaluations}); an already-published mark stays hidden until then.
     * The overall Total/Letter/Points is the course grade itself: it requires BOTH the instructor
     * evaluation AND the section actually being submitted and locked ({@code is_submitted = 1}) —
     * publishing every individual component ahead of "Submit and Lock" is never enough on its own,
     * or a grade would leak before the instructor ever locks the section.</p>
     *
     * @param semesterId one semester, or null for every semester
     */
    public List<StudentGradeRow> findStudentGradeRows(int studentId, Integer semesterId) {
        String sql = "SELECT e.enrollment_id, e.status, e.counts_in_gpa, s.instructor_id, "
                + "sem.semester_id, sem.semester_name, c.course_code, c.course_title, c.credits, c.has_lab, "
                + "ISNULL(g.is_submitted, 0) AS is_submitted, "
                // Each component is revealed the moment its OWN publish flag is set, or once the
                // whole row is submitted (submission always implied full visibility, unchanged) --
                // this is what lets Coursework/Midterm show up before Final even exists. Midterm is
                // never gated by the evaluation; the other final-stage columns require evaluation while the window is open.
                // Once submitted, the raw mark IS the final truth (no further draft edit is
                // possible without an Admin Unlock, which itself does not touch these columns).
                // Before that, only the snapshot frozen at the last Publish is shown -- the raw
                // mark column keeps moving with every Save Draft, whether or not this component
                // has ever been published, so it must never be read directly here.
                + "CASE WHEN (g.is_submitted = 1 OR g.coursework_published = 1) AND (ev.evaluation_done = 1 OR (sem.evaluation_end IS NOT NULL AND SYSDATETIME() > sem.evaluation_end)) "
                + "     THEN CASE WHEN g.is_submitted = 1 THEN g.coursework_mark ELSE g.coursework_published_mark END "
                + "     END AS coursework_mark, "
                + "CASE WHEN g.is_submitted = 1 OR g.midterm_published    = 1 "
                + "     THEN CASE WHEN g.is_submitted = 1 THEN g.midterm_mark ELSE g.midterm_published_mark END "
                + "     END AS midterm_mark, "
                + "CASE WHEN (g.is_submitted = 1 OR g.lab_published = 1) AND (ev.evaluation_done = 1 OR (sem.evaluation_end IS NOT NULL AND SYSDATETIME() > sem.evaluation_end)) "
                + "     THEN CASE WHEN g.is_submitted = 1 THEN g.lab_mark ELSE g.lab_published_mark END "
                + "     END AS lab_mark, "
                // Was the Lab ever actually released to this student? Kept separate from lab_mark
                // above so a Lab cleared after an Admin Unlock (lab_published stays 1, but the mark
                // just went back to NULL) can be told apart from a Lab that was never graded at all
                // (lab_published = 0) -- both otherwise read as the same NULL lab_mark.
                + "ISNULL(g.lab_published, 0) AS lab_published, "
                + "CASE WHEN (g.is_submitted = 1 OR g.final_published = 1) AND (ev.evaluation_done = 1 OR (sem.evaluation_end IS NOT NULL AND SYSDATETIME() > sem.evaluation_end)) "
                + "     THEN CASE WHEN g.is_submitted = 1 THEN g.final_mark ELSE g.final_published_mark END "
                + "     END AS final_mark, "
                // The course grade itself -- strictly BOTH conditions, no shortcut: the section
                // must actually be submitted and locked (is_submitted = 1), never merely "every
                // component happens to be published", and the student must have completed the
                // instructor evaluation while that window is still open. After the evaluation deadline, the grade is released automatically.
                + "CASE WHEN g.is_submitted = 1 AND (ev.evaluation_done = 1 OR (sem.evaluation_end IS NOT NULL AND SYSDATETIME() > sem.evaluation_end)) "
                + "     THEN g.total_mark   END AS total_mark, "
                + "CASE WHEN g.is_submitted = 1 AND (ev.evaluation_done = 1 OR (sem.evaluation_end IS NOT NULL AND SYSDATETIME() > sem.evaluation_end)) "
                + "     THEN g.letter_grade END AS letter_grade, "
                + "CASE WHEN g.is_submitted = 1 AND (ev.evaluation_done = 1 OR (sem.evaluation_end IS NOT NULL AND SYSDATETIME() > sem.evaluation_end)) "
                + "     THEN g.grade_points END AS grade_points "
                + "FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.semesters sem ON sem.semester_id = s.semester_id "
                + "INNER JOIN dbo.courses c ON c.course_id = s.course_id "
                + "LEFT JOIN dbo.grades g ON g.enrollment_id = e.enrollment_id "
                + "CROSS APPLY (SELECT CASE WHEN EXISTS ("
                + "     SELECT 1 FROM dbo.instructor_evaluations ie "
                + "     WHERE ie.enrollment_id = e.enrollment_id) THEN 1 ELSE 0 END AS evaluation_done) ev "
                + "WHERE e.student_id = ? AND e.status IN ('ENROLLED', 'COMPLETED')"
                + (semesterId == null ? "" : " AND sem.semester_id = ?")
                + " ORDER BY sem.start_date DESC, c.course_code";

        RowMapper<StudentGradeRow> mapper = rs -> {
            StudentGradeRow row = new StudentGradeRow();
            row.setEnrollmentId(rs.getInt("enrollment_id"));
            row.setInstructorId(DaoUtils.getInteger(rs, "instructor_id"));
            row.setEnrollmentStatus(EnrollmentStatus.fromDb(rs.getString("status")));
            row.setCountsInGpa(rs.getBoolean("counts_in_gpa"));
            row.setSemesterId(rs.getInt("semester_id"));
            row.setSemesterName(rs.getString("semester_name"));
            row.setCourseCode(rs.getString("course_code"));
            row.setCourseTitle(rs.getString("course_title"));
            row.setCredits(rs.getInt("credits"));
            row.setHasLab(rs.getBoolean("has_lab"));
            row.setSubmitted(rs.getBoolean("is_submitted"));
            row.setCourseworkMark(rs.getBigDecimal("coursework_mark"));
            row.setMidtermMark(rs.getBigDecimal("midterm_mark"));
            row.setLabMark(rs.getBigDecimal("lab_mark"));
            row.setLabCleared(rs.getBoolean("lab_published") && rs.getBigDecimal("lab_mark") == null);
            row.setFinalMark(rs.getBigDecimal("final_mark"));
            row.setTotalMark(rs.getBigDecimal("total_mark"));
            row.setLetterGrade(LetterGrade.fromDb(rs.getString("letter_grade")));
            row.setGradePoints(rs.getBigDecimal("grade_points"));
            return row;
        };

        return semesterId == null
                ? queryList(sql, mapper, studentId)
                : queryList(sql, mapper, studentId, semesterId);
    }

    /**
     * Every grade belonging to a section of one course — used to rescale marks when the admin
     * changes a component's max mark (regardless of {@code is_submitted}: rescaling preserves the
     * mark's meaning, it is not a correction, so G4 does not apply).
     */
    public List<Grade> findByCourse(Connection connection, int courseId) {
        String sql = "SELECT g.grade_id, g.enrollment_id, g.coursework_mark, g.midterm_mark, g.lab_mark, "
                + "g.final_mark, g.total_mark, g.letter_grade, g.grade_points, g.result_status, "
                + "g.is_submitted, g.coursework_published, g.midterm_published, g.lab_published, "
                + "g.final_published, g.coursework_published_mark, g.midterm_published_mark, "
                + "g.lab_published_mark, g.final_published_mark, "
                + "g.submitted_by, g.submitted_at, g.last_modified_by, g.last_modified_at "
                + "FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "WHERE s.course_id = ?";
        return queryList(connection, sql, MAPPER, courseId);
    }

    /**
     * Every student with a SUBMITTED grade in a course — used after rescaling stored marks
     * (see {@link com.university.service.GradeService#rescaleMarksForMaxMarkChange}) to know
     * whose cached {@code students.cumulative_gpa}/credits need recomputing, since only
     * submitted grades feed the GPA (unsubmitted rows never counted, so no refresh is needed
     * for them).
     */
    public List<Integer> findSubmittedStudentIdsByCourse(Connection connection, int courseId) {
        String sql = "SELECT DISTINCT e.student_id FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "WHERE s.course_id = ? AND g.is_submitted = 1";
        return queryList(connection, sql, rs -> rs.getInt("student_id"), courseId);
    }

    /**
     * Rewrites only the mark/total/letter/points columns of an already-loaded grade, regardless
     * of {@code is_submitted} — used solely by the proportional rescale in {@link
     * com.university.service.GradeService#rescaleMarksForMaxMarkChange}, never for a per-student
     * correction (see {@link #overrideSubmitted} for that). {@code submitted_by}/{@code
     * submitted_at}/{@code last_modified_by}/{@code last_modified_at} are deliberately untouched.
     */
    public boolean rescaleStoredGrade(Connection connection, Grade entity) {
        String sql = "UPDATE dbo.grades SET coursework_mark = ?, midterm_mark = ?, lab_mark = ?, "
                + "final_mark = ?, total_mark = ?, letter_grade = ?, grade_points = ?, result_status = ?, "
                + "coursework_published_mark = ?, midterm_published_mark = ?, "
                + "lab_published_mark = ?, final_published_mark = ? "
                + "WHERE grade_id = ?";
        return executeUpdate(connection, sql,
                entity.getCourseworkMark(), entity.getMidtermMark(), entity.getLabMark(), entity.getFinalMark(),
                entity.getTotalMark(), letterOrNull(entity), entity.getGradePoints(), resultOrNull(entity),
                entity.getCourseworkPublishedMark(), entity.getMidtermPublishedMark(),
                entity.getLabPublishedMark(), entity.getFinalPublishedMark(),
                entity.getGradeId()) > 0;
    }

    /**
     * Admin Unlock: reverts every submitted grade in a section back to {@code is_submitted = 0} so
     * the instructor can edit it normally again — marks and publish flags are left exactly as they
     * were, nothing is cleared.
     *
     * @return how many grade rows were unlocked (0 means the section was not locked)
     */
    public int unlockSection(Connection connection, int sectionId) {
        String sql = "UPDATE g SET g.is_submitted = 0 FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "WHERE e.section_id = ? AND g.is_submitted = 1";
        return executeUpdate(connection, sql, sectionId);
    }

    /** True once at least one grade in the section has been submitted — rule G4's read side. */
    public boolean isSectionSubmitted(int sectionId) {
        return queryInt("SELECT COUNT(*) FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "WHERE e.section_id = ? AND g.is_submitted = 1", sectionId) > 0;
    }

    /**
     * Which of one instructor's sections in one semester have at least one submitted grade — the
     * same fact as {@link #isSectionSubmitted}, but for every section in a single round trip
     * instead of one query per section.
     */
    public java.util.Set<Integer> submittedSectionIds(int instructorId, int semesterId) {
        return new java.util.HashSet<>(queryList(
                "SELECT DISTINCT e.section_id AS section_id FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "WHERE s.instructor_id = ? AND s.semester_id = ? AND g.is_submitted = 1",
                rs -> rs.getInt("section_id"), instructorId, semesterId));
    }

    /**
     * Releases individual components to the student ahead of the whole row being submitted --
     * this is the "partial grade publish" a component earns the moment the instructor has a mark
     * for it, independent of whether the others (typically Final) exist yet.
     *
     * <p>Each flag only ever moves 0 -&gt; 1 here: a {@code false} argument leaves that component's
     * current flag untouched rather than clearing it, so calling this again after the instructor
     * adds the Final mark does not un-publish an already-visible Coursework/Midterm.</p>
     */
    public boolean publishComponents(Connection connection, int gradeId, boolean coursework, boolean midterm,
                                      boolean lab, boolean finalMark) {
        String sql = "UPDATE dbo.grades SET "
                + "coursework_published = CASE WHEN ? = 1 THEN 1 ELSE coursework_published END, "
                + "midterm_published    = CASE WHEN ? = 1 THEN 1 ELSE midterm_published    END, "
                + "lab_published        = CASE WHEN ? = 1 THEN 1 ELSE lab_published        END, "
                + "final_published      = CASE WHEN ? = 1 THEN 1 ELSE final_published      END, "
                // The snapshot the student actually sees -- only moves when its own flag argument
                // is true, so it always freezes at exactly the mark just written by the same
                // upsertGrade call this method follows, never a later, still-unpublished edit.
                + "coursework_published_mark = CASE WHEN ? = 1 THEN coursework_mark ELSE coursework_published_mark END, "
                + "midterm_published_mark    = CASE WHEN ? = 1 THEN midterm_mark    ELSE midterm_published_mark    END, "
                + "lab_published_mark        = CASE WHEN ? = 1 THEN lab_mark        ELSE lab_published_mark        END, "
                + "final_published_mark      = CASE WHEN ? = 1 THEN final_mark      ELSE final_published_mark      END "
                + "WHERE grade_id = ?";
        return executeUpdate(connection, sql,
                coursework, midterm, lab, finalMark,
                coursework, midterm, lab, finalMark,
                gradeId) > 0;
    }

    /**
     * Publishes a grade, stamping who released it and when.
     *
     * <p>Once this has run the student can see the mark, so it is deliberately
     * separate from the ordinary update.</p>
     */
    public boolean submit(int gradeId, int submittedByUserId, LocalDateTime moment) {
        return executeUpdate("UPDATE dbo.grades SET is_submitted = 1, submitted_by = ?, "
                + "submitted_at = ? WHERE grade_id = ?", submittedByUserId, moment, gradeId) > 0;
    }

    /** Publishes a grade inside a transaction already running. */
    public boolean submit(Connection connection, int gradeId, int submittedByUserId,
                          LocalDateTime moment) {
        return executeUpdate(connection, "UPDATE dbo.grades SET is_submitted = 1, "
                + "submitted_by = ?, submitted_at = ? WHERE grade_id = ? AND is_submitted = 0",
                submittedByUserId, moment, gradeId) > 0;
    }

    /**
     * RULE G5 — the registrar's correction of an already-submitted grade. Unlike the ordinary
     * {@link #update}, this one does NOT require {@code is_submitted = 0}; that is the entire
     * point of an override. It stamps {@code last_modified_by}/{@code last_modified_at} so
     * {@code trg_Grade_Audit} has something to attribute the change to.
     */
    public boolean overrideSubmitted(Connection connection, Grade entity) {
        String sql = "UPDATE dbo.grades SET coursework_mark = ?, midterm_mark = ?, lab_mark = ?, "
                + "final_mark = ?, total_mark = ?, letter_grade = ?, grade_points = ?, result_status = ?, "
                + "last_modified_by = ?, last_modified_at = ? WHERE grade_id = ?";
        return executeUpdate(connection, sql,
                entity.getCourseworkMark(), entity.getMidtermMark(), entity.getLabMark(), entity.getFinalMark(),
                entity.getTotalMark(), letterOrNull(entity), entity.getGradePoints(),
                resultOrNull(entity), entity.getLastModifiedBy(), entity.getLastModifiedAt(),
                entity.getGradeId()) > 0;
    }

    @Override
    public int insert(Grade entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Grade entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Grade entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Grade entity) {
        return executeUpdate(connection, UPDATE, updateParams(entity)) > 0;
    }

    /**
     * Save Draft: always writes the instructor's current mark values, whether or not any
     * component is already published -- the whole point of a draft is that it must never be
     * silently discarded (that used to be preserved-on-publish here, which meant a published
     * component's edit reappeared as the OLD value after every reload). What the student sees
     * stays frozen at {@code *_published_mark} (only {@link #publishComponents} moves that), so
     * writing the raw mark here freely never leaks an unpublished edit early.
     */
    public boolean updateDraft(Connection connection, Grade entity) {
        String sql = "UPDATE dbo.grades SET "
                + "coursework_mark = ?, midterm_mark = ?, lab_mark = ?, final_mark = ?, "
                + "total_mark = ?, letter_grade = ?, grade_points = ?, result_status = ?, "
                // Same rule as the ordinary update(): a mark cleared back to NULL here (e.g. after
                // an Admin Unlock) must take its published snapshot down with it, or a student
                // whose section is no longer submitted keeps reading the *_published_mark left over
                // from before the unlock forever, since Save Draft otherwise never touches these
                // columns at all.
                + "coursework_published_mark = CASE WHEN ? IS NULL THEN NULL ELSE coursework_published_mark END, "
                + "midterm_published_mark    = CASE WHEN ? IS NULL THEN NULL ELSE midterm_published_mark    END, "
                + "lab_published_mark        = CASE WHEN ? IS NULL THEN NULL ELSE lab_published_mark        END, "
                + "final_published_mark      = CASE WHEN ? IS NULL THEN NULL ELSE final_published_mark      END, "
                + "last_modified_by = ?, last_modified_at = ? "
                + "WHERE grade_id = ? AND is_submitted = 0";

        return executeUpdate(connection, sql,
                entity.getCourseworkMark(),
                entity.getMidtermMark(),
                entity.getLabMark(),
                entity.getFinalMark(),
                entity.getTotalMark(),
                letterOrNull(entity),
                entity.getGradePoints(),
                resultOrNull(entity),
                entity.getCourseworkMark(),
                entity.getMidtermMark(),
                entity.getLabMark(),
                entity.getFinalMark(),
                entity.getLastModifiedBy(),
                entity.getLastModifiedAt(),
                entity.getGradeId()) > 0;
    }

    @Override
    public boolean deleteById(int id) {
        return executeUpdate(DELETE, id) > 0;
    }

    @Override
    public boolean deleteById(Connection connection, int id) {
        return executeUpdate(connection, DELETE, id) > 0;
    }

    private Object[] insertParams(Grade entity) {
        return new Object[]{
                entity.getEnrollmentId(),
                entity.getCourseworkMark(),
                entity.getMidtermMark(),
                entity.getLabMark(),
                entity.getFinalMark(),
                entity.getTotalMark(),
                letterOrNull(entity),
                entity.getGradePoints(),
                resultOrNull(entity),
                entity.isSubmitted(),
                entity.isCourseworkPublished(),
                entity.isMidtermPublished(),
                entity.isLabPublished(),
                entity.isFinalPublished(),
                entity.getSubmittedBy(),
                entity.getSubmittedAt()
        };
    }

    private Object[] updateParams(Grade entity) {
        return new Object[]{
                entity.getCourseworkMark(),
                entity.getMidtermMark(),
                entity.getLabMark(),
                entity.getFinalMark(),
                entity.getTotalMark(),
                letterOrNull(entity),
                entity.getGradePoints(),
                resultOrNull(entity),
                entity.isSubmitted(),
                entity.isCourseworkPublished(),
                entity.isMidtermPublished(),
                entity.isLabPublished(),
                entity.isFinalPublished(),
                entity.getCourseworkMark(),
                entity.getMidtermMark(),
                entity.getLabMark(),
                entity.getFinalMark(),
                entity.getLastModifiedBy(),
                entity.getLastModifiedAt(),
                entity.getGradeId()
        };
    }

    /** {@link LetterGrade#toDb()}, not the enum's {@code name()} — see the class comment. */
    private String letterOrNull(Grade entity) {
        return entity.getLetterGrade() == null ? null : entity.getLetterGrade().toDb();
    }

    private String resultOrNull(Grade entity) {
        return entity.getResultStatus() == null ? null : entity.getResultStatus().toDb();
    }
}





