package com.university.service;

import com.university.dao.EnrollmentDAO;
import com.university.dao.GradeDAO;
import com.university.dao.ProgramDAO;
import com.university.dao.ProgramRequirementDAO;
import com.university.dao.StudentDAO;
import com.university.enums.AcademicStanding;
import com.university.model.Grade;
import com.university.model.Program;
import com.university.model.Student;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.util.List;

/**
 * The academic record: grade point average, standing, and progress towards
 * the degree.
 *
 * <p>{@code students.cumulative_gpa} and {@code completed_credits} are cached
 * columns. Everything here recomputes them from the published grades and
 * writes the answer back, so the cache is a copy of the truth rather than a
 * separate version of it.</p>
 */
public class AcademicService {

    /** At or above this average, a student makes the dean's list. */
    public static final BigDecimal DEANS_LIST_GPA = new BigDecimal("3.50");

    /** Below this average, a student goes on probation. */
    public static final BigDecimal GOOD_STANDING_GPA = new BigDecimal("2.00");

    /** This many spells on probation and the student is suspended. */
    public static final int PROBATION_LIMIT = 3;

    private final StudentDAO studentDao = new StudentDAO();
    private final GradeDAO gradeDao = new GradeDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final ProgramDAO programDao = new ProgramDAO();
    private final ProgramRequirementDAO requirementDao = new ProgramRequirementDAO();

    /**
     * Recomputes the average, the credits and the standing, and stores them.
     *
     * @return the standing the student now holds
     */
    public AcademicStanding refreshAcademicRecord(int studentId) {
        Student student = requireStudent(studentId);
        BigDecimal gpa = gradeDao.calculateCumulativeGpa(studentId);
        int credits = gradeDao.calculateCompletedCredits(studentId);
        AcademicStanding standing = standingFor(gpa, credits, student.getProbationCount());
        studentDao.updateAcademicCache(studentId, gpa, credits, standing);
        return standing;
    }

    /**
     * Recomputes and stores the record as part of a change already running,
     * such as publishing a grade.
     */
    public AcademicStanding refreshAcademicRecord(Connection connection, int studentId) {
        Student student = requireStudent(studentId);
        BigDecimal gpa = gradeDao.calculateCumulativeGpa(studentId);
        int credits = gradeDao.calculateCompletedCredits(studentId);
        AcademicStanding standing = standingFor(gpa, credits, student.getProbationCount());
        studentDao.updateAcademicCache(connection, studentId, gpa, credits, standing);
        return standing;
    }

    /**
     * Works out the standing that belongs with a set of figures.
     *
     * <p>A student with nothing graded yet is NEW rather than failing: a zero
     * average that nobody has earned should not read as a bad one.</p>
     */
    public AcademicStanding standingFor(BigDecimal gpa, int completedCredits, int probationCount) {
        if (completedCredits <= 0) {
            return AcademicStanding.NEW;
        }
        BigDecimal value = gpa == null ? BigDecimal.ZERO : gpa;
        if (value.compareTo(GOOD_STANDING_GPA) < 0) {
            return probationCount >= PROBATION_LIMIT
                    ? AcademicStanding.SUSPENDED
                    : AcademicStanding.PROBATION;
        }
        return value.compareTo(DEANS_LIST_GPA) >= 0
                ? AcademicStanding.DEANS_LIST
                : AcademicStanding.GOOD;
    }

    /** The published grades of one student, which is the transcript. */
    public List<Grade> transcript(int studentId) {
        return gradeDao.findSubmittedByStudent(studentId);
    }

    /** The average recomputed from the grades, without touching the cache. */
    public BigDecimal currentGpa(int studentId) {
        return gradeDao.calculateCumulativeGpa(studentId);
    }

    /** The credits actually earned. */
    public int completedCredits(int studentId) {
        return gradeDao.calculateCompletedCredits(studentId);
    }

    /** The credits the student's program demands. */
    public int requiredCredits(int studentId) {
        Student student = requireStudent(studentId);
        return programDao.findById(student.getProgramId())
                .map(Program::getTotalCreditsRequired)
                .orElseGet(() -> requirementDao.sumRequiredCredits(student.getProgramId()));
    }

    /** The credits still to earn, never below zero. */
    public int remainingCredits(int studentId) {
        return Math.max(0, requiredCredits(studentId) - completedCredits(studentId));
    }

    /**
     * How far through the degree the student is.
     *
     * @return a percentage from 0 to 100, rounded to one decimal
     */
    public BigDecimal degreeProgressPercent(int studentId) {
        int required = requiredCredits(studentId);
        if (required <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(completedCredits(studentId))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(required), 1, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100));
    }

    /**
     * True when the student has met the credit requirement and is not below
     * good standing.
     *
     * <p>A credit count alone is not enough: somebody can accumulate credits
     * and still be failing overall.</p>
     */
    public boolean isEligibleToGraduate(int studentId) {
        return remainingCredits(studentId) == 0
                && currentGpa(studentId).compareTo(GOOD_STANDING_GPA) >= 0;
    }

    /** The credits the student is carrying in one semester. */
    public int semesterCreditLoad(int studentId, int semesterId) {
        return enrollmentDao.sumCreditsInSemester(studentId, semesterId);
    }

    /**
     * Records another spell on probation.
     *
     * <p>Kept apart from {@link #refreshAcademicRecord} because the count is a
     * history of how often it has happened, not something derived from the
     * current average.</p>
     */
    public int recordProbation(int studentId) {
        Student student = requireStudent(studentId);
        int updated = student.getProbationCount() + 1;
        student.setProbationCount(updated);
        studentDao.update(student);
        return updated;
    }

    private Student requireStudent(int studentId) {
        ValidationException.requireId(studentId, "Student");
        return studentDao.findById(studentId)
                .orElseThrow(() -> new ServiceException("That student record was not found."));
    }
}
