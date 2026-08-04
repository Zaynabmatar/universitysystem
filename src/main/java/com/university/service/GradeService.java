package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.CourseDAO;
import com.university.dao.EnrollmentDAO;
import com.university.dao.GradeDAO;
import com.university.dao.InstructorDAO;
import com.university.dao.SectionDAO;
import com.university.dao.SemesterDAO;
import com.university.dao.StudentDAO;
import com.university.dao.UserDAO;
import com.university.enums.EnrollmentStatus;
import com.university.enums.LetterGrade;
import com.university.enums.NotificationType;
import com.university.enums.UserRole;
import com.university.model.Course;
import com.university.model.Enrollment;
import com.university.model.Grade;
import com.university.model.GradeSheetRow;
import com.university.model.Instructor;
import com.university.model.Section;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.model.User;
import com.university.util.GradeCalculator;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * project_details.md Section 6.6 — grade entry rules G1..G6, plus Save Draft / Submit and Lock
 * and the registrar's correction of an already-submitted grade.
 *
 * <pre>
 *   G1  only the instructor assigned to a section may enter its grades
 *   G2  only between the semester's grade_entry_start and grade_entry_end
 *   G3  marks must be 0-100                              (GradeCalculator.isValidMark)
 *   G4  once is_submitted = 1 the instructor can no longer edit
 *   G5  only ADMIN may change a submitted grade, and the change is audited (by trg_Grade_Audit —
 *       this class never writes to audit_log itself)
 *   G6  on submission: enrollment -&gt; COMPLETED, then the academic record is recalculated
 * </pre>
 *
 * <p>Every rule is enforced here, not only in the UI — a rule that only exists in the UI is a
 * suggestion, not a rule.</p>
 */
public class GradeService {

    private final GradeDAO gradeDao = new GradeDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final SectionDAO sectionDao = new SectionDAO();
    private final SemesterDAO semesterDao = new SemesterDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final InstructorDAO instructorDao = new InstructorDAO();
    private final UserDAO userDao = new UserDAO();
    private final AcademicService academicService = new AcademicService();
    private final NotificationService notifications = new NotificationService();

    /** Gives access to the connection helpers without exposing a whole data access object. */
    private final AbstractDAO transactions = new AbstractDAO() {
    };

    // =====================================================================
    // Reading
    // =====================================================================

    /** Every enrolled/completed student in the section, with their marks (blank if not entered). */
    public List<GradeSheetRow> getGradeSheet(int sectionId) {
        return gradeDao.findSectionRoster(sectionId);
    }

    /** True once at least one grade in the section has been submitted (rule G4). */
    public boolean isSectionSubmitted(int sectionId) {
        return gradeDao.isSectionSubmitted(sectionId);
    }

    /** "Not started" / "Draft saved" / "Submitted 🔒" — shown on the My Sections list. */
    public String gradeStatusLabel(int sectionId) {
        if (isSectionSubmitted(sectionId)) {
            return "Submitted 🔒";
        }
        return gradeDao.countUnsubmittedInSection(sectionId) > 0 ? "Draft saved" : "Not started";
    }

    // =====================================================================
    // G1 / G2 / G3 — the guards
    // =====================================================================

    /** G1 — the acting user must be the instructor this section is assigned to. */
    private void assertOwnsSection(Section section, int actingUserId) {
        Instructor instructor = instructorDao.findByUserId(actingUserId).orElse(null);
        boolean owns = instructor != null && section.getInstructorId() != null
                && section.getInstructorId().equals(instructor.getInstructorId());
        if (!owns) {
            throw new ValidationException("You may only enter grades for your own sections.");
        }
    }

    /** G2 — today must fall inside the semester's grade-entry window. */
    private void assertGradeWindowOpen(Section section) {
        Semester semester = semesterDao.findById(section.getSemesterId())
                .orElseThrow(() -> new ServiceException("That semester no longer exists."));
        LocalDate start = semester.getGradeEntryStart();
        LocalDate end = semester.getGradeEntryEnd();
        if (start == null || end == null) {
            throw new ValidationException("The grade entry period has not been set for this semester.");
        }
        LocalDate today = LocalDate.now();
        if (today.isBefore(start)) {
            throw new ValidationException("Grade entry opens on " + start + ".");
        }
        if (today.isAfter(end)) {
            throw new ValidationException("Grade entry closed on " + end + ".");
        }
    }

    /** G3 — every supplied mark must be 0..100. */
    private void assertMarksValid(List<GradeSheetRow> rows) {
        for (GradeSheetRow row : rows) {
            checkOne(row, row.getCourseworkMark(), "Coursework");
            checkOne(row, row.getMidtermMark(), "Midterm");
            checkOne(row, row.getFinalMark(), "Final");
        }
    }

    private void checkOne(GradeSheetRow row, BigDecimal mark, String label) {
        if (mark != null && !GradeCalculator.isValidMark(mark)) {
            throw new ValidationException(label + " mark for " + row.getStudentName()
                    + " must be between 0 and 100 (you entered " + mark.toPlainString() + ").");
        }
    }

    // =====================================================================
    // Save Draft
    // =====================================================================

    /**
     * Writes the marks WITHOUT submitting. {@code is_submitted} stays 0, the enrollment stays
     * ENROLLED, no GPA is recalculated, the student sees nothing yet.
     */
    public void saveDraft(int sectionId, List<GradeSheetRow> rows, int actingUserId) {
        Section section = requireSection(sectionId);
        assertOwnsSection(section, actingUserId);
        assertGradeWindowOpen(section);
        assertMarksValid(rows);

        Connection connection = transactions.beginTransaction();
        try {
            for (GradeSheetRow row : rows) {
                if (row.isSubmitted()) {
                    continue; // G4 — never touch a locked row
                }
                row.recompute();
                upsertGrade(connection, row, actingUserId, false);
            }
            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The draft could not be saved.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    // =====================================================================
    // Submit and Lock — G4 and G6
    // =====================================================================

    /**
     * Every student in the section must have all three marks. On success, for each row:
     * {@code grades.is_submitted = 1} (G4), {@code enrollments.status = COMPLETED} and the
     * student's academic record is recalculated (G6), and the student is notified (N9). All of
     * it happens in ONE transaction.
     */
    public void submitSection(int sectionId, List<GradeSheetRow> rows, int actingUserId) {
        Section section = requireSection(sectionId);
        assertOwnsSection(section, actingUserId);
        assertGradeWindowOpen(section);
        assertMarksValid(rows);

        List<String> missing = new ArrayList<>();
        for (GradeSheetRow row : rows) {
            if (row.isSubmitted()) {
                continue;
            }
            if (row.getCourseworkMark() == null || row.getMidtermMark() == null || row.getFinalMark() == null) {
                missing.add(row.getStudentNumber() + " " + row.getStudentName());
            }
        }
        if (!missing.isEmpty()) {
            throw new ValidationException("These students still have missing marks:\n• "
                    + String.join("\n• ", missing));
        }

        Connection connection = transactions.beginTransaction();
        try {
            for (GradeSheetRow row : rows) {
                if (row.isSubmitted()) {
                    continue;
                }
                row.recompute();
                int gradeId = upsertGrade(connection, row, actingUserId, true);
                enrollmentDao.setStatus(connection, row.getEnrollmentId(), EnrollmentStatus.COMPLETED);
                academicService.refreshAcademicRecord(connection, row.getStudentId());
                notifySubmitted(connection, section, row, gradeId);
                row.setSubmitted(true);
            }
            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The grades could not be submitted.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    // =====================================================================
    // G5 — admin override of a SUBMITTED grade
    // =====================================================================

    /**
     * Only an active ADMIN reaches this method — the role is re-checked in SQL, never trusted
     * from the UI alone. The change is written to {@code audit_log} by the DATABASE trigger
     * {@code trg_Grade_Audit} (project_details.md Section 4.16) — this method never inserts into
     * {@code audit_log} itself.
     *
     * @param reason free text typed by the admin; must be at least 5 characters
     */
    public void adminOverride(int enrollmentId, BigDecimal coursework, BigDecimal midterm,
                              BigDecimal finalMark, int adminUserId, String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new ValidationException("Please type a reason for the change (at least 5 characters).");
        }
        checkOverrideMark(coursework, "Coursework");
        checkOverrideMark(midterm, "Midterm");
        checkOverrideMark(finalMark, "Final");
        assertIsAdmin(adminUserId);

        Enrollment enrollment = enrollmentDao.findById(enrollmentId)
                .orElseThrow(() -> new ValidationException("That enrollment no longer exists."));
        Grade existing = gradeDao.findByEnrollment(enrollmentId)
                .orElseThrow(() -> new ValidationException("There is no grade row to correct for this enrollment."));
        LetterGrade oldLetter = existing.getLetterGrade();

        BigDecimal total = GradeCalculator.totalMark(coursework, midterm, finalMark);
        LetterGrade newLetter = GradeCalculator.letterGrade(total);

        Grade correction = new Grade();
        correction.setGradeId(existing.getGradeId());
        correction.setCourseworkMark(coursework);
        correction.setMidtermMark(midterm);
        correction.setFinalMark(finalMark);
        correction.setTotalMark(total);
        correction.setLetterGrade(newLetter);
        correction.setGradePoints(newLetter == null ? null : newLetter.getGradePoints());
        correction.setResultStatus(newLetter == null ? null : newLetter.toResultStatus());
        correction.setLastModifiedBy(adminUserId);
        correction.setLastModifiedAt(LocalDateTime.now());

        Connection connection = transactions.beginTransaction();
        try {
            if (!gradeDao.overrideSubmitted(connection, correction)) {
                throw new ValidationException("There is no grade row to correct for this enrollment.");
            }
            enrollmentDao.setStatus(connection, enrollmentId, EnrollmentStatus.COMPLETED);
            academicService.refreshAcademicRecord(connection, enrollment.getStudentId());
            notifyOverride(connection, enrollment, oldLetter, newLetter);

            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The grade could not be corrected.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    private void checkOverrideMark(BigDecimal mark, String label) {
        if (mark != null && !GradeCalculator.isValidMark(mark)) {
            throw new ValidationException(label + " mark must be between 0 and 100.");
        }
    }

    /** Re-checks the role in SQL — Section 6.6: "only ADMIN may change a submitted grade." */
    private void assertIsAdmin(int userId) {
        User user = userDao.findById(userId).orElse(null);
        if (user == null || user.getRole() != UserRole.ADMIN || !user.isActive()) {
            throw new ValidationException("Only the registrar may change a submitted grade.");
        }
    }

    // =====================================================================
    // internals
    // =====================================================================

    /**
     * Writes the mark columns (insert or update, gated by {@code is_submitted = 0} in SQL — G4
     * enforced twice), then, when {@code submit} is true, flips the submission flag in a second
     * statement so {@code submitted_by}/{@code submitted_at} are only ever stamped by
     * {@link GradeDAO#submit}.
     *
     * @return the grade's key
     */
    private int upsertGrade(Connection connection, GradeSheetRow row, int actingUserId, boolean submit)
            throws SQLException {
        Grade grade = new Grade();
        grade.setEnrollmentId(row.getEnrollmentId());
        grade.setCourseworkMark(row.getCourseworkMark());
        grade.setMidtermMark(row.getMidtermMark());
        grade.setFinalMark(row.getFinalMark());
        grade.setTotalMark(row.getTotalMark());
        grade.setLetterGrade(row.getLetterGrade());
        grade.setGradePoints(row.getGradePoints());
        grade.setResultStatus(row.getLetterGrade() == null ? null : row.getLetterGrade().toResultStatus());
        grade.setSubmitted(false);
        grade.setLastModifiedBy(actingUserId);
        grade.setLastModifiedAt(LocalDateTime.now());

        int gradeId;
        if (row.getGradeId() == null) {
            gradeId = gradeDao.insert(connection, grade);
            row.setGradeId(gradeId);
        } else {
            grade.setGradeId(row.getGradeId());
            gradeDao.update(connection, grade);
            gradeId = row.getGradeId();
        }
        if (submit) {
            gradeDao.submit(connection, gradeId, actingUserId, LocalDateTime.now());
        }
        return gradeId;
    }

    /** Notification N9 — phase-10 context/NOTIFICATION_MESSAGES.md. */
    private void notifySubmitted(Connection connection, Section section, GradeSheetRow row, int gradeId)
            throws SQLException {
        Course course = courseDao.findById(section.getCourseId()).orElse(null);
        Student student = studentDao.findById(row.getStudentId()).orElse(null);
        if (course == null || student == null) {
            return;
        }
        notifications.notify(connection, student.getUserId(), NotificationType.GRADE,
                "Your grade for " + course.getCourseCode() + " is available",
                "Your final grade for " + course.getCourseCode() + " — " + course.getCourseTitle()
                        + " is " + row.getLetterGrade().getLabel() + ". Your GPA has been updated.",
                "grades", gradeId);
    }

    /** Notification N10. */
    private void notifyOverride(Connection connection, Enrollment enrollment, LetterGrade oldLetter,
                                LetterGrade newLetter) throws SQLException {
        Optional<Section> section = sectionDao.findById(enrollment.getSectionId());
        if (section.isEmpty()) {
            return;
        }
        Course course = courseDao.findById(section.get().getCourseId()).orElse(null);
        Student student = studentDao.findById(enrollment.getStudentId()).orElse(null);
        if (course == null || student == null) {
            return;
        }
        notifications.notify(connection, student.getUserId(), NotificationType.GRADE, "A grade was corrected",
                "Your grade for " + course.getCourseCode() + " — " + course.getCourseTitle()
                        + " was changed from " + (oldLetter == null ? "—" : oldLetter.getLabel())
                        + " to " + (newLetter == null ? "—" : newLetter.getLabel())
                        + " by the registrar.", "grades", enrollment.getEnrollmentId());
    }

    private Section requireSection(int sectionId) {
        return sectionDao.findById(sectionId)
                .orElseThrow(() -> new ServiceException("That section no longer exists."));
    }
}
