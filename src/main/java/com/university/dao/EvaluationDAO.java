package com.university.dao;

import com.university.model.EvaluationAnswer;
import com.university.model.EvaluationQuestion;
import com.university.model.InstructorEvaluation;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes instructor evaluations.
 *
 * <p>Business rules such as who may evaluate, whether the evaluation window
 * is open, and whether all 15 questions were answered belong in the service
 * layer. This DAO only performs database operations.</p>
 */
public class EvaluationDAO extends AbstractDAO {

    private static final String QUESTION_SELECT =
            "SELECT question_id, question_text, display_order, is_active "
            + "FROM dbo.evaluation_questions";

    private static final String EVALUATION_SELECT =
            "SELECT evaluation_id, enrollment_id, submitted_at "
            + "FROM dbo.instructor_evaluations";

    private static final RowMapper<EvaluationQuestion> QUESTION_MAPPER =
            EvaluationDAO::mapQuestion;

    private static final RowMapper<InstructorEvaluation> EVALUATION_MAPPER =
            EvaluationDAO::mapEvaluation;

    private static EvaluationQuestion mapQuestion(ResultSet rs) throws SQLException {
        EvaluationQuestion question = new EvaluationQuestion();
        question.setQuestionId(rs.getInt("question_id"));
        question.setQuestionText(rs.getString("question_text"));
        question.setDisplayOrder(rs.getInt("display_order"));
        question.setActive(rs.getBoolean("is_active"));
        return question;
    }

    private static InstructorEvaluation mapEvaluation(ResultSet rs) throws SQLException {
        InstructorEvaluation evaluation = new InstructorEvaluation();
        evaluation.setEvaluationId(rs.getInt("evaluation_id"));
        evaluation.setEnrollmentId(rs.getInt("enrollment_id"));
        evaluation.setSubmittedAt(DaoUtils.getLocalDateTime(rs, "submitted_at"));
        return evaluation;
    }

    /** The 15 questions currently shown to students, in display order. */
    public List<EvaluationQuestion> findActiveQuestions() {
        return queryList(
                QUESTION_SELECT + " WHERE is_active = 1 ORDER BY display_order",
                QUESTION_MAPPER);
    }

    /** Finds the submitted evaluation for one enrollment, if it exists. */
    public Optional<InstructorEvaluation> findByEnrollment(int enrollmentId) {
        return queryOne(
                EVALUATION_SELECT + " WHERE enrollment_id = ?",
                EVALUATION_MAPPER,
                enrollmentId);
    }

    /** True after this enrollment has already submitted its one allowed evaluation. */
    public boolean hasEvaluationForEnrollment(int enrollmentId) {
        return queryInt(
                "SELECT COUNT(*) FROM dbo.instructor_evaluations WHERE enrollment_id = ?",
                enrollmentId) > 0;
    }

    /**
     * Creates the parent evaluation row inside a caller-owned transaction.
     *
     * @return generated evaluation_id
     */
    public int insertEvaluation(Connection connection, int enrollmentId) {
        return insertAndReturnKey(
                connection,
                "INSERT INTO dbo.instructor_evaluations (enrollment_id) VALUES (?)",
                enrollmentId);
    }

    /** Stores one 1-to-5 answer inside the same transaction as the evaluation. */
    public void insertAnswer(Connection connection, int evaluationId, EvaluationAnswer answer) {
        executeUpdate(
                connection,
                "INSERT INTO dbo.evaluation_answers (evaluation_id, question_id, rating) "
                + "VALUES (?, ?, ?)",
                evaluationId,
                answer.getQuestionId(),
                answer.getRating());
    }

    /**
     * Overall average for one instructor across all submitted evaluation answers.
     *
     * <p>The result is empty when the instructor has not received evaluations yet.</p>
     */
    public Optional<BigDecimal> findAverageForInstructor(int instructorId) {
        return queryOne(
                "SELECT CAST(AVG(CAST(a.rating AS DECIMAL(10,2))) AS DECIMAL(4,2)) "
                + "FROM dbo.evaluation_answers a "
                + "INNER JOIN dbo.instructor_evaluations ev "
                + "ON ev.evaluation_id = a.evaluation_id "
                + "INNER JOIN dbo.enrollments e "
                + "ON e.enrollment_id = ev.enrollment_id "
                + "INNER JOIN dbo.sections s "
                + "ON s.section_id = e.section_id "
                + "WHERE s.instructor_id = ? "
                + "HAVING COUNT(a.rating) > 0",
                rs -> rs.getBigDecimal(1),
                instructorId);
    }

    /** Number of students who have submitted an evaluation for this instructor. */
    public int countEvaluationsForInstructor(int instructorId) {
        return queryInt(
                "SELECT COUNT(*) "
                + "FROM dbo.instructor_evaluations ev "
                + "INNER JOIN dbo.enrollments e "
                + "ON e.enrollment_id = ev.enrollment_id "
                + "INNER JOIN dbo.sections s "
                + "ON s.section_id = e.section_id "
                + "WHERE s.instructor_id = ?",
                instructorId);
    }
}