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
            + "total_mark, letter_grade, grade_points, result_status, is_submitted, submitted_by, "
            + "submitted_at, last_modified_by, last_modified_at FROM dbo.grades";

    private static final String INSERT =
            "INSERT INTO dbo.grades (enrollment_id, coursework_mark, midterm_mark, lab_mark, final_mark, "
            + "total_mark, letter_grade, grade_points, result_status, is_submitted, "
            + "submitted_by, submitted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.grades SET coursework_mark = ?, midterm_mark = ?, lab_mark = ?, final_mark = ?, "
            + "total_mark = ?, letter_grade = ?, grade_points = ?, result_status = ?, is_submitted = ?, "
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
        return gpaQuery(studentId, null);
    }

    /** Term GPA — the same formula, restricted to one semester's sections (Section 5.3). */
    public BigDecimal calculateTermGpa(int studentId, int semesterId) {
        return gpaQuery(studentId, semesterId);
    }

    private BigDecimal gpaQuery(int studentId, Integer semesterId) {
        String sql = "SELECT CAST(CASE WHEN SUM(c.credits) IS NULL OR SUM(c.credits) = 0 THEN 0 "
                + "ELSE SUM(g.grade_points * c.credits) / SUM(c.credits) END AS DECIMAL(5,4)) "
                + "FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.courses c ON c.course_id = s.course_id "
                + "WHERE e.student_id = ? AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1 "
                + "AND g.is_submitted = 1 AND g.letter_grade NOT IN ('W', 'I')"
                + (semesterId == null ? "" : " AND s.semester_id = ?");
        BigDecimal raw = semesterId == null
                ? queryOne(sql, rs -> rs.getBigDecimal(1), studentId).orElse(BigDecimal.ZERO)
                : queryOne(sql, rs -> rs.getBigDecimal(1), studentId, semesterId).orElse(BigDecimal.ZERO);
        return raw.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** The credits a student has actually earned, meaning passed. */
    public int calculateCompletedCredits(int studentId) {
        return queryInt("SELECT ISNULL(SUM(c.credits), 0) FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.courses c ON c.course_id = s.course_id "
                + "WHERE e.student_id = ? AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1 "
                + "AND g.is_submitted = 1 AND g.result_status = 'PASSED'",
                studentId);
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
                + "g.is_submitted, c.has_lab "
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
            row.setSubmitted(rs.getBoolean("is_submitted"));
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
     * "a student must never see a draft". Dropped rows are left out; withdrawn ones are kept,
     * because a W belongs on the record.</p>
     *
     * @param semesterId one semester, or null for every semester
     */
    public List<StudentGradeRow> findStudentGradeRows(int studentId, Integer semesterId) {
        String sql = "SELECT e.enrollment_id, e.status, e.counts_in_gpa, "
                + "sem.semester_id, sem.semester_name, c.course_code, c.course_title, c.credits, c.has_lab, "
                + "ISNULL(g.is_submitted, 0) AS is_submitted, "
                + "CASE WHEN g.is_submitted = 1 THEN g.coursework_mark END AS coursework_mark, "
                + "CASE WHEN g.is_submitted = 1 THEN g.midterm_mark    END AS midterm_mark, "
                + "CASE WHEN g.is_submitted = 1 THEN g.final_mark      END AS final_mark, "
                + "CASE WHEN g.is_submitted = 1 THEN g.total_mark      END AS total_mark, "
                + "CASE WHEN g.is_submitted = 1 THEN g.letter_grade    END AS letter_grade, "
                + "CASE WHEN g.is_submitted = 1 THEN g.grade_points    END AS grade_points "
                + "FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.semesters sem ON sem.semester_id = s.semester_id "
                + "INNER JOIN dbo.courses c ON c.course_id = s.course_id "
                + "LEFT JOIN dbo.grades g ON g.enrollment_id = e.enrollment_id "
                + "WHERE e.student_id = ? AND e.status <> 'DROPPED'"
                + (semesterId == null ? "" : " AND sem.semester_id = ?")
                + " ORDER BY sem.start_date DESC, c.course_code";

        RowMapper<StudentGradeRow> mapper = rs -> {
            StudentGradeRow row = new StudentGradeRow();
            row.setEnrollmentId(rs.getInt("enrollment_id"));
            row.setEnrollmentStatus(EnrollmentStatus.fromDb(rs.getString("status")));
            row.setCountsInGpa(rs.getBoolean("counts_in_gpa"));
            row.setSemesterId(rs.getInt("semester_id"));
            row.setSemesterName(rs.getString("semester_name"));
            row.setCourseCode(rs.getString("course_code"));
            row.setCourseTitle(rs.getString("course_title"));
            row.setCredits(rs.getInt("credits"));
            row.setSubmitted(rs.getBoolean("is_submitted"));
            row.setCourseworkMark(rs.getBigDecimal("coursework_mark"));
            row.setMidtermMark(rs.getBigDecimal("midterm_mark"));
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

    /** True once at least one grade in the section has been submitted — rule G4's read side. */
    public boolean isSectionSubmitted(int sectionId) {
        return queryInt("SELECT COUNT(*) FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "WHERE e.section_id = ? AND g.is_submitted = 1", sectionId) > 0;
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

