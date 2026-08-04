package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.EnrollmentDAO;
import com.university.dao.GradeDAO;
import com.university.dao.ProgramDAO;
import com.university.dao.ProgramRequirementDAO;
import com.university.dao.SemesterDAO;
import com.university.dao.StudentDAO;
import com.university.enums.AcademicStanding;
import com.university.enums.NotificationType;
import com.university.enums.StudentStatus;
import com.university.model.Grade;
import com.university.model.Program;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.model.StudentGradeRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * The academic record: grade point average, standing, and progress towards
 * the degree.
 *
 * <p>{@code students.cumulative_gpa} and {@code completed_credits} are cached
 * columns. Everything here recomputes them from the published grades and
 * writes the answer back, so the cache is a copy of the truth rather than a
 * separate version of it. project_details.md Section 5.4 is the source for
 * the standing bands, the probation counter and the suspension rule.</p>
 */
public class AcademicService {

    /** At or above this average, a student makes the dean's list. */
    public static final BigDecimal DEANS_LIST_GPA = new BigDecimal("3.50");

    /** Below this average, a student goes on probation. */
    public static final BigDecimal GOOD_STANDING_GPA = new BigDecimal("2.00");

    /** Section 5.4: "probation_count reaches 2 -> SUSPENDED". */
    public static final int PROBATION_LIMIT = 2;

    private final StudentDAO studentDao = new StudentDAO();
    private final GradeDAO gradeDao = new GradeDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final ProgramDAO programDao = new ProgramDAO();
    private final ProgramRequirementDAO requirementDao = new ProgramRequirementDAO();
    private final SemesterDAO semesterDao = new SemesterDAO();
    private final NotificationService notifications = new NotificationService();

    /** Gives access to the connection helpers without exposing a whole data access object. */
    private final AbstractDAO transactions = new AbstractDAO() {
    };

    /**
     * Recomputes the average, the credits and the standing, and stores them — opening and
     * committing its own transaction for a screen that just needs a refresh (a change already
     * inside a transaction should call the {@link Connection}-taking overload instead).
     *
     * @return the standing the student now holds
     */
    public AcademicStanding refreshAcademicRecord(int studentId) {
        Connection connection = transactions.beginTransaction();
        try {
            AcademicStanding standing = refreshAcademicRecord(connection, studentId);
            connection.commit();
            return standing;
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("Could not refresh the academic record.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    /**
     * Recomputes and stores the record as part of a change already running,
     * such as submitting a section's grades — and sends the standing-change
     * notification (N11/N12/N13) on the same connection, so it rolls back with
     * everything else if the change fails.
     */
    public AcademicStanding refreshAcademicRecord(Connection connection, int studentId) {
        Student student = requireStudent(studentId);
        BigDecimal gpa = gradeDao.calculateCumulativeGpa(studentId);
        int credits = gradeDao.calculateCompletedCredits(studentId);

        AcademicStanding previous = student.getAcademicStanding();
        int probationCount = nextProbationCount(previous, gpa, credits, student.getProbationCount());
        AcademicStanding standing = standingFor(gpa, credits, probationCount);
        StudentStatus status = standing == AcademicStanding.SUSPENDED
                ? StudentStatus.SUSPENDED : student.getStatus();

        studentDao.updateAcademicCache(connection, studentId, gpa, credits, standing, probationCount, status);

        if (standing != previous) {
            notifyStandingChange(connection, student, standing, gpa);
        }
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

    /**
     * Section 5.4: "probation_count is incremented once per term that ends on probation, not
     * once per grade submitted." A student's standing is only recomputed here from a state that
     * was not already PROBATION or SUSPENDED, so re-submitting another section's grades in the
     * same term (while the average is still below 2.00) does not count a second time.
     */
    private int nextProbationCount(AcademicStanding previous, BigDecimal gpa, int completedCredits,
                                    int currentCount) {
        if (completedCredits <= 0 || gpa == null) {
            return currentCount;
        }
        boolean fallingBelowGoodStanding = gpa.compareTo(GOOD_STANDING_GPA) < 0;
        boolean alreadyCounted = previous == AcademicStanding.PROBATION || previous == AcademicStanding.SUSPENDED;
        return (fallingBelowGoodStanding && !alreadyCounted) ? currentCount + 1 : currentCount;
    }

    private void notifyStandingChange(Connection connection, Student student, AcademicStanding standing,
                                      BigDecimal gpa) {
        String formattedGpa = (gpa == null ? BigDecimal.ZERO : gpa).setScale(2, RoundingMode.HALF_UP).toPlainString();
        switch (standing) {
            case PROBATION -> notifications.notify(connection, student.getUserId(), NotificationType.WARNING,
                    "Academic standing: Probation",
                    "Your cumulative GPA is " + formattedGpa + ". You have been placed on academic "
                            + "probation and your maximum load is now 12 credits per semester.",
                    "students", student.getStudentId());
            case DEANS_LIST -> notifications.notify(connection, student.getUserId(), NotificationType.SUCCESS,
                    "Congratulations — Dean's List",
                    "Your cumulative GPA is " + formattedGpa + ". You have been placed on the Dean's List.",
                    "students", student.getStudentId());
            case SUSPENDED -> notifications.notify(connection, student.getUserId(), NotificationType.WARNING,
                    "Academic standing: Suspended",
                    "After a second term on probation your status is now SUSPENDED and you cannot "
                            + "register. Please contact the registrar.",
                    "students", student.getStudentId());
            default -> { /* NEW and GOOD are not announced */ }
        }
    }

    /** The published grades of one student, which is the transcript. */
    public List<Grade> transcript(int studentId) {
        return gradeDao.findSubmittedByStudent(studentId);
    }

    /** The average recomputed from the grades, without touching the cache. */
    public BigDecimal currentGpa(int studentId) {
        return gradeDao.calculateCumulativeGpa(studentId);
    }

    /**
     * Section 5.3 — the average for one semester alone, on the same rules as the cumulative one
     * (submitted grades only, counting attempts only, W and I excluded).
     */
    public BigDecimal termGpa(int studentId, int semesterId) {
        return gradeDao.calculateTermGpa(studentId, semesterId);
    }

    /**
     * What the student sees on My Grades: their enrollments with the published grade attached,
     * and nothing at all attached while a grade is still a draft.
     *
     * @param semesterId one semester, or null for the whole record
     */
    public List<StudentGradeRow> gradeRows(int studentId, Integer semesterId) {
        return gradeDao.findStudentGradeRows(studentId, semesterId);
    }

    /** The semesters the student has actually studied in, newest first. */
    public List<Semester> semestersStudied(int studentId) {
        return semesterDao.findWithEnrollments(studentId);
    }

    /** The student's stored record, for the standing and credit figures shown beside the GPA. */
    public Student academicRecordOf(int studentId) {
        return requireStudent(studentId);
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

    private Student requireStudent(int studentId) {
        ValidationException.requireId(studentId, "Student");
        return studentDao.findById(studentId)
                .orElseThrow(() -> new ServiceException("That student record was not found."));
    }
}
