package com.university.dao;

import com.university.enums.LetterGrade;
import com.university.enums.ResultStatus;
import com.university.model.Grade;

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
 * zero is a real mark.</p>
 */
public class GradeDAO extends AbstractDAO implements GenericDAO<Grade> {

    private static final String SELECT =
            "SELECT grade_id, enrollment_id, partial_mark, lab_mark, final_mark, total_mark, "
            + "letter_grade, grade_points, result_status, is_submitted, submitted_by, "
            + "submitted_at, last_modified_by, last_modified_at FROM dbo.grades";

    private static final String INSERT =
            "INSERT INTO dbo.grades (enrollment_id, partial_mark, lab_mark, final_mark, "
            + "total_mark, letter_grade, grade_points, result_status, is_submitted, "
            + "submitted_by, submitted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.grades SET partial_mark = ?, lab_mark = ?, final_mark = ?, total_mark = ?, "
            + "letter_grade = ?, grade_points = ?, result_status = ?, is_submitted = ?, "
            + "last_modified_by = ?, last_modified_at = ? WHERE grade_id = ?";

    private static final String DELETE = "DELETE FROM dbo.grades WHERE grade_id = ?";

    private static final RowMapper<Grade> MAPPER = GradeDAO::mapRow;

    static Grade mapRow(ResultSet rs) throws SQLException {
        Grade grade = new Grade();
        grade.setGradeId(rs.getInt("grade_id"));
        grade.setEnrollmentId(rs.getInt("enrollment_id"));
        grade.setPartialMark(rs.getBigDecimal("partial_mark"));
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
        return queryList("SELECT g.grade_id, g.enrollment_id, g.partial_mark, g.lab_mark, "
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
        return queryList("SELECT g.grade_id, g.enrollment_id, g.partial_mark, g.lab_mark, "
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
        return queryList("SELECT g.grade_id, g.enrollment_id, g.partial_mark, g.lab_mark, "
                        + "g.final_mark, g.total_mark, g.letter_grade, g.grade_points, "
                        + "g.result_status, g.is_submitted, g.submitted_by, g.submitted_at, "
                        + "g.last_modified_by, g.last_modified_at "
                        + "FROM dbo.grades g "
                        + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                        + "WHERE e.student_id = ? AND g.is_submitted = 1 ORDER BY g.grade_id",
                MAPPER, studentId);
    }

    /**
     * Recomputes the cumulative grade point average from the submitted grades.
     *
     * <p>Weighted by course credits, and limited to the attempts still marked
     * as counting, so a repeated course is not paid for twice.</p>
     *
     * @return the average rounded to two decimals, or zero when nothing counts
     */
    public BigDecimal calculateCumulativeGpa(int studentId) {
        String sql = "SELECT CAST(CASE WHEN SUM(c.credits) IS NULL OR SUM(c.credits) = 0 THEN 0 "
                + "ELSE SUM(g.grade_points * c.credits) / SUM(c.credits) END AS DECIMAL(3,2)) "
                + "FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.courses c ON c.course_id = s.course_id "
                + "WHERE e.student_id = ? AND e.counts_in_gpa = 1 AND g.is_submitted = 1 "
                + "AND g.grade_points IS NOT NULL";
        return queryOne(sql, rs -> rs.getBigDecimal(1), studentId)
                .orElse(BigDecimal.ZERO);
    }

    /** The credits a student has actually earned, meaning passed. */
    public int calculateCompletedCredits(int studentId) {
        return queryInt("SELECT ISNULL(SUM(c.credits), 0) FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.courses c ON c.course_id = s.course_id "
                + "WHERE e.student_id = ? AND g.result_status = 'PASSED' AND g.is_submitted = 1",
                studentId);
    }

    /** How many grades in a section are still unsubmitted. */
    public int countUnsubmittedInSection(int sectionId) {
        return queryInt("SELECT COUNT(*) FROM dbo.grades g "
                + "INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "WHERE e.section_id = ? AND g.is_submitted = 0", sectionId);
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
                + "submitted_by = ?, submitted_at = ? WHERE grade_id = ?",
                submittedByUserId, moment, gradeId) > 0;
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
                entity.getPartialMark(),
                entity.getLabMark(),
                entity.getFinalMark(),
                entity.getTotalMark(),
                entity.getLetterGrade(),
                entity.getGradePoints(),
                entity.getResultStatus(),
                entity.isSubmitted(),
                entity.getSubmittedBy(),
                entity.getSubmittedAt()
        };
    }

    private Object[] updateParams(Grade entity) {
        return new Object[]{
                entity.getPartialMark(),
                entity.getLabMark(),
                entity.getFinalMark(),
                entity.getTotalMark(),
                entity.getLetterGrade(),
                entity.getGradePoints(),
                entity.getResultStatus(),
                entity.isSubmitted(),
                entity.getLastModifiedBy(),
                entity.getLastModifiedAt(),
                entity.getGradeId()
        };
    }
}
