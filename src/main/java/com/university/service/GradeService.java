package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.CourseDAO;
import com.university.dao.EnrollmentDAO;
import com.university.dao.GradeDAO;
import com.university.dao.SectionDAO;
import com.university.dao.StudentDAO;
import com.university.enums.LetterGrade;
import com.university.enums.NotificationType;
import com.university.model.Course;
import com.university.model.Enrollment;
import com.university.model.Grade;
import com.university.model.Section;
import com.university.model.Student;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Entering marks, turning them into a grade, and publishing the result.
 *
 * <p>The schema constrains what a mark may be but says nothing about how the
 * pieces combine, so the weights and the letter boundaries live here as named
 * constants. Change them in one place and every calculation follows.</p>
 *
 * <p>A mark is invisible to the student until it is submitted. Publishing is
 * therefore a separate step from saving, and it also refreshes the cached
 * average, since a new grade changes it.</p>
 */
public class GradeService {

    /** Weights when the course has a lab: partial, lab, final. */
    public static final BigDecimal PARTIAL_WEIGHT_WITH_LAB = new BigDecimal("0.30");
    public static final BigDecimal LAB_WEIGHT = new BigDecimal("0.20");
    public static final BigDecimal FINAL_WEIGHT_WITH_LAB = new BigDecimal("0.50");

    /** Weights when the course has no lab. */
    public static final BigDecimal PARTIAL_WEIGHT_NO_LAB = new BigDecimal("0.40");
    public static final BigDecimal FINAL_WEIGHT_NO_LAB = new BigDecimal("0.60");

    /** The lowest total that earns each letter. Below the last one is F. */
    public static final BigDecimal A_THRESHOLD = new BigDecimal("90");
    public static final BigDecimal B_THRESHOLD = new BigDecimal("80");
    public static final BigDecimal C_THRESHOLD = new BigDecimal("70");
    public static final BigDecimal D_THRESHOLD = new BigDecimal("60");

    private static final BigDecimal MIN_MARK = BigDecimal.ZERO;
    private static final BigDecimal MAX_MARK = new BigDecimal("100");

    private final GradeDAO gradeDao = new GradeDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final SectionDAO sectionDao = new SectionDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final AcademicService academic = new AcademicService();
    private final NotificationService notifications = new NotificationService();

    private final AbstractDAO transactions = new AbstractDAO() {
    };

    /**
     * Combines the component marks into a total out of 100.
     *
     * <p>A null lab mark means the course has no lab, and the remaining two
     * components carry the whole weight between them.</p>
     *
     * @return the total rounded to two decimals, or null when a required
     *         component is still missing
     */
    public BigDecimal calculateTotal(BigDecimal partialMark, BigDecimal labMark,
                                     BigDecimal finalMark) {
        if (partialMark == null || finalMark == null) {
            return null;
        }
        BigDecimal total;
        if (labMark == null) {
            total = partialMark.multiply(PARTIAL_WEIGHT_NO_LAB)
                    .add(finalMark.multiply(FINAL_WEIGHT_NO_LAB));
        } else {
            total = partialMark.multiply(PARTIAL_WEIGHT_WITH_LAB)
                    .add(labMark.multiply(LAB_WEIGHT))
                    .add(finalMark.multiply(FINAL_WEIGHT_WITH_LAB));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The letter a total earns.
     *
     * @return the letter, or null when there is no total yet
     */
    public LetterGrade toLetterGrade(BigDecimal totalMark) {
        if (totalMark == null) {
            return null;
        }
        if (totalMark.compareTo(A_THRESHOLD) >= 0) {
            return LetterGrade.A;
        }
        if (totalMark.compareTo(B_THRESHOLD) >= 0) {
            return LetterGrade.B;
        }
        if (totalMark.compareTo(C_THRESHOLD) >= 0) {
            return LetterGrade.C;
        }
        if (totalMark.compareTo(D_THRESHOLD) >= 0) {
            return LetterGrade.D;
        }
        return LetterGrade.F;
    }

    /**
     * Saves the component marks for one registration, working out the total,
     * the letter, the points and the pass or fail from them.
     *
     * <p>Creates the grade row on first use, so a section does not need rows
     * prepared in advance.</p>
     *
     * @param labMark null when the course has no lab
     * @return the grade's key
     * @throws ServiceException when the grade has already been published
     */
    public int saveMarks(int enrollmentId, BigDecimal partialMark, BigDecimal labMark,
                         BigDecimal finalMark, int editedByUserId) {
        ValidationException.requireId(enrollmentId, "Enrolment");
        checkMark(partialMark, "Partial mark");
        checkMark(labMark, "Lab mark");
        checkMark(finalMark, "Final mark");

        enrollmentDao.findById(enrollmentId)
                .orElseThrow(() -> new ServiceException("That registration no longer exists."));

        BigDecimal total = calculateTotal(partialMark, labMark, finalMark);
        LetterGrade letter = toLetterGrade(total);

        Optional<Grade> existing = gradeDao.findByEnrollment(enrollmentId);
        if (existing.isPresent() && existing.get().isSubmitted()) {
            throw new ServiceException("This grade has already been published. "
                    + "It must be reopened before it can be changed.");
        }

        Grade grade = existing.orElseGet(Grade::new);
        grade.setEnrollmentId(enrollmentId);
        grade.setPartialMark(partialMark);
        grade.setLabMark(labMark);
        grade.setFinalMark(finalMark);
        grade.setTotalMark(total);
        grade.setLetterGrade(letter);
        grade.setGradePoints(letter == null ? null : letter.getGradePoints());
        grade.setResultStatus(letter == null ? null : letter.toResultStatus());

        if (existing.isPresent()) {
            grade.setLastModifiedBy(editedByUserId);
            grade.setLastModifiedAt(LocalDateTime.now());
            gradeDao.update(grade);
            return grade.getGradeId();
        }
        grade.setSubmitted(false);
        return gradeDao.insert(grade);
    }

    /**
     * Publishes one grade and refreshes the student's academic record.
     *
     * <p>Both happen on one connection: a published grade that left the
     * average untouched would put the two out of step.</p>
     *
     * @throws ServiceException when the grade is incomplete or already published
     */
    public void submit(int gradeId, int submittedByUserId) {
        ValidationException.requireId(gradeId, "Grade");

        Grade grade = gradeDao.findById(gradeId)
                .orElseThrow(() -> new ServiceException("That grade was not found."));
        if (grade.isSubmitted()) {
            throw new ServiceException("This grade has already been published.");
        }
        if (grade.getTotalMark() == null || grade.getLetterGrade() == null) {
            throw new ServiceException("Enter the partial and final marks before publishing.");
        }

        Enrollment enrollment = enrollmentDao.findById(grade.getEnrollmentId())
                .orElseThrow(() -> new ServiceException("That registration no longer exists."));
        Student student = studentDao.findById(enrollment.getStudentId())
                .orElseThrow(() -> new ServiceException("That student record was not found."));
        Section section = sectionDao.findById(enrollment.getSectionId()).orElse(null);
        Course course = section == null ? null
                : courseDao.findById(section.getCourseId()).orElse(null);
        String courseLabel = course == null ? "your course" : course.getCourseCode();

        Connection connection = transactions.beginTransaction();
        try {
            gradeDao.submit(connection, gradeId, submittedByUserId, LocalDateTime.now());
            academic.refreshAcademicRecord(connection, student.getStudentId());

            notifications.notify(connection, student.getUserId(), NotificationType.GRADE,
                    "Grade published for " + courseLabel,
                    "Your grade for " + courseLabel + " is " + grade.getLetterGrade()
                            + " (" + grade.getTotalMark() + ").",
                    "grades", gradeId);

            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The grade could not be published.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    /**
     * Publishes every finished grade in a section.
     *
     * @return how many were published
     * @throws ServiceException when any grade in the section is still incomplete
     */
    public int submitSection(int sectionId, int submittedByUserId) {
        ValidationException.requireId(sectionId, "Section");

        List<Grade> grades = gradeDao.findBySection(sectionId);
        if (grades.isEmpty()) {
            throw new ServiceException("There are no grades to publish for this section.");
        }
        boolean incomplete = grades.stream()
                .anyMatch(g -> !g.isSubmitted() && g.getTotalMark() == null);
        if (incomplete) {
            throw new ServiceException("Some students have no total mark yet. "
                    + "Complete every mark before publishing the section.");
        }

        int published = 0;
        for (Grade grade : grades) {
            if (!grade.isSubmitted()) {
                submit(grade.getGradeId(), submittedByUserId);
                published++;
            }
        }
        return published;
    }

    /**
     * Creates an empty grade row for every student in a section who has none,
     * so the mark sheet opens with a line per student.
     *
     * @return how many rows were created
     */
    public int prepareMarkSheet(int sectionId) {
        ValidationException.requireId(sectionId, "Section");
        int created = 0;
        for (Enrollment enrollment : enrollmentDao.findActiveBySection(sectionId)) {
            if (gradeDao.findByEnrollment(enrollment.getEnrollmentId()).isEmpty()) {
                Grade grade = new Grade();
                grade.setEnrollmentId(enrollment.getEnrollmentId());
                grade.setSubmitted(false);
                gradeDao.insert(grade);
                created++;
            }
        }
        return created;
    }

    /** The mark sheet of one section. */
    public List<Grade> markSheet(int sectionId) {
        return gradeDao.findBySection(sectionId);
    }

    /** How many marks in a section are still unpublished. */
    public int outstandingInSection(int sectionId) {
        return gradeDao.countUnsubmittedInSection(sectionId);
    }

    /** The grades a student is allowed to see. */
    public List<Grade> publishedGradesOf(int studentId) {
        return gradeDao.findSubmittedByStudent(studentId);
    }

    private void checkMark(BigDecimal mark, String fieldName) {
        if (mark == null) {
            return;
        }
        if (mark.compareTo(MIN_MARK) < 0 || mark.compareTo(MAX_MARK) > 0) {
            throw new ValidationException(fieldName + " must be between 0 and 100.");
        }
    }
}
