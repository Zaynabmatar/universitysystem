package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.EnrollmentDAO;
import com.university.dao.EvaluationDAO;
import com.university.dao.SectionDAO;
import com.university.dao.SemesterDAO;
import com.university.model.Enrollment;
import com.university.model.EvaluationAnswer;
import com.university.model.EvaluationQuestion;
import com.university.model.Section;
import com.university.model.Semester;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Business rules for instructor evaluations.
 *
 * <p>The rules are enforced here as well as in the UI so they cannot be
 * bypassed by opening a screen directly.</p>
 */
public class EvaluationService {

    private static final int REQUIRED_QUESTION_COUNT = 15;

    private final EvaluationDAO evaluationDao = new EvaluationDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final SectionDAO sectionDao = new SectionDAO();
    private final SemesterDAO semesterDao = new SemesterDAO();

    /** Gives access to transaction helpers without putting business logic in a DAO. */
    private final AbstractDAO transactions = new AbstractDAO() {
    };

    /** The active evaluation questions, already ordered for display. */
    public List<EvaluationQuestion> getQuestions() {
        return evaluationDao.findActiveQuestions();
    }

    /** True after the signed-in student has submitted this enrollment's evaluation. */
    public boolean hasSubmitted(int enrollmentId) {
        requireOwnedEnrollment(enrollmentId);
        return evaluationDao.hasEvaluationForEnrollment(enrollmentId);
    }

    /**
     * True only while this enrollment's semester evaluation period is open
     * and the student has not already submitted.
     */
    public boolean isAvailable(int enrollmentId) {
        Enrollment enrollment = requireOwnedEnrollment(enrollmentId);

        if (evaluationDao.hasEvaluationForEnrollment(enrollmentId)) {
            return false;
        }

        Semester semester = requireSemesterFor(enrollment);
        return semester.isEvaluationOpen(LocalDateTime.now());
    }

    /**
     * Submits one complete instructor evaluation.
     *
     * <p>The parent row and all 15 answers are committed together. If any
     * answer fails, nothing is saved.</p>
     */
    public void submit(int enrollmentId, List<EvaluationAnswer> answers) {
        Enrollment enrollment = requireOwnedEnrollment(enrollmentId);
        Semester semester = requireSemesterFor(enrollment);

        if (!semester.isEvaluationOpen(LocalDateTime.now())) {
            throw new ValidationException("Instructor evaluation is not open for this semester.");
        }

        if (evaluationDao.hasEvaluationForEnrollment(enrollmentId)) {
            throw new ValidationException("You have already submitted this instructor evaluation.");
        }

        List<EvaluationQuestion> questions = evaluationDao.findActiveQuestions();
        validateAnswers(questions, answers);

        Connection connection = transactions.beginTransaction();
        try {
            int evaluationId = evaluationDao.insertEvaluation(connection, enrollmentId);

            for (EvaluationAnswer answer : answers) {
                evaluationDao.insertAnswer(connection, evaluationId, answer);
            }

            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The instructor evaluation could not be submitted.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    /** Overall instructor evaluation average for admin reporting. */
    public java.util.Optional<java.math.BigDecimal> averageForInstructor(int instructorId) {
        if (instructorId <= 0) {
            throw new ValidationException("Instructor ID must be valid.");
        }
        return evaluationDao.findAverageForInstructor(instructorId);
    }

    /** Number of submitted evaluations behind the instructor average. */
    public int evaluationCountForInstructor(int instructorId) {
        if (instructorId <= 0) {
            throw new ValidationException("Instructor ID must be valid.");
        }
        return evaluationDao.countEvaluationsForInstructor(instructorId);
    }
    /** Makes sure a student can act only on their own enrollment. */
    private Enrollment requireOwnedEnrollment(int enrollmentId) {
        int studentId = Session.current().requireStudentId();

        Enrollment enrollment = enrollmentDao.findById(enrollmentId)
                .orElseThrow(() -> new ValidationException("That enrollment no longer exists."));

        if (enrollment.getStudentId() != studentId) {
            throw new ValidationException("You may only evaluate instructors for your own courses.");
        }

        return enrollment;
    }

    /** Resolves the semester behind an enrollment's section. */
    private Semester requireSemesterFor(Enrollment enrollment) {
        Section section = sectionDao.findById(enrollment.getSectionId())
                .orElseThrow(() -> new ServiceException("That section no longer exists."));

        return semesterDao.findById(section.getSemesterId())
                .orElseThrow(() -> new ServiceException("That semester no longer exists."));
    }

    /** Requires exactly one valid 1-to-5 answer for every active question. */
    private void validateAnswers(List<EvaluationQuestion> questions,
                                 List<EvaluationAnswer> answers) {
        if (questions.size() != REQUIRED_QUESTION_COUNT) {
            throw new ServiceException(
                    "The instructor evaluation form is not configured with exactly 15 active questions.");
        }

        if (answers == null || answers.size() != REQUIRED_QUESTION_COUNT) {
            throw new ValidationException("Please answer all 15 evaluation questions.");
        }

        Set<Integer> activeQuestionIds = new HashSet<>();
        for (EvaluationQuestion question : questions) {
            activeQuestionIds.add(question.getQuestionId());
        }

        Set<Integer> answeredQuestionIds = new HashSet<>();

        for (EvaluationAnswer answer : answers) {
            if (answer == null) {
                throw new ValidationException("Please answer all 15 evaluation questions.");
            }

            if (!activeQuestionIds.contains(answer.getQuestionId())) {
                throw new ValidationException("The evaluation contains an invalid question.");
            }

            if (!answeredQuestionIds.add(answer.getQuestionId())) {
                throw new ValidationException("Each evaluation question may be answered only once.");
            }

            if (answer.getRating() < 1 || answer.getRating() > 5) {
                throw new ValidationException("Evaluation ratings must be between 1 and 5.");
            }
        }

        if (!answeredQuestionIds.equals(activeQuestionIds)) {
            throw new ValidationException("Please answer all 15 evaluation questions.");
        }
    }
}