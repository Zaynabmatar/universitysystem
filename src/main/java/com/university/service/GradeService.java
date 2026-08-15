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
 *   G3  marks must be 0..the component's own max mark    (GradeCalculator.isValidMark)
 *   G4  once is_submitted = 1 the instructor can no longer edit, UNLESS the grade window (G2) is
 *       still open, in which case the same row may be corrected in place (see submitSection)
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

    /**
     * Which of one instructor's sections in one semester already have a submitted grade — the
     * same fact as {@link #isSectionSubmitted}, for every section in one round trip.
     */
    public java.util.Set<Integer> submittedSectionIds(int instructorId, int semesterId) {
        return gradeDao.submittedSectionIds(instructorId, semesterId);
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

    /**
     * The same G2 rule as {@link #assertGradeWindowOpen}, without throwing — lets the grade sheet
     * decide, before any edit, whether a submitted row may still be corrected in place.
     */
    public boolean isGradeWindowOpen(int sectionId) {
        Section section = sectionDao.findById(sectionId).orElse(null);
        if (section == null) {
            return false;
        }
        try {
            assertGradeWindowOpen(section);
            return true;
        } catch (ServiceException e) {
            return false;
        }
    }

    /**
     * G3 — every mark actually being written must be 0..the component's own max mark.
     *
     * <p>Skips a submitted row that is not being corrected in this call: it is locked (G4) and
     * neither {@link #saveDraft} nor {@link #submitSection} will write it, so an old mark that a
     * later change to the course's max mark left out of range must not block saving/submitting
     * the rest of the section. The instructor still sees it flagged on the sheet and must correct
     * it before it can ever be re-submitted.</p>
     */
    private void assertMarksValid(List<GradeSheetRow> rows) {
        for (GradeSheetRow row : rows) {
            if (row.isSubmitted() && !row.isEditedAfterSubmit()) {
                continue;
            }
            checkOne(row, row.getCourseworkMark(), "Coursework", row.getCourseworkMaxMark());
            checkOne(row, row.getMidtermMark(), "Midterm", row.getMidtermMaxMark());
            checkOne(row, row.getLabMark(), "Lab", row.getCourseworkMaxMark());
            checkOne(row, row.getFinalMark(), "Final", row.getFinalMaxMark());
        }
    }

    private void checkOne(GradeSheetRow row, BigDecimal mark, String label, BigDecimal max) {
        if (mark != null && !GradeCalculator.isValidMark(mark, max)) {
            throw new ValidationException(label + " mark for " + row.getStudentName()
                    + " must be between 0 and " + max.stripTrailingZeros().toPlainString()
                    + " (you entered " + mark.toPlainString() + ").");
        }
    }

    // =====================================================================
    // Save Draft
    // =====================================================================

    /**
     * Writes the marks WITHOUT submitting. For a never-submitted row {@code is_submitted} stays
     * 0, the enrollment stays ENROLLED, no GPA is recalculated. For a row already submitted and
     * corrected in place while the grade window is open ({@link GradeSheetRow#isEditedAfterSubmit()}),
     * the corrected marks are written immediately so they are never lost on the next refresh —
     * {@code submitted}/{@code editedAfterSubmit} stay as they were; only {@link #submitSection}
     * finalizes the correction (GPA recalculated, student notified, flag cleared).
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
                    if (row.isEditedAfterSubmit()) {
                        // Persist the in-place correction now instead of losing it on refresh —
                        // "Submit and Lock" still does the GPA recalc/notification/flag reset.
                        row.recompute();
                        resubmitGrade(connection, row, actingUserId);
                    }
                    continue; // G4 — never touch a locked row otherwise
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
    // Partial publish — release individual components ahead of Submit and Lock
    // =====================================================================

    /**
     * Releases whatever components are currently marked -- Coursework, Midterm, Lab, Final,
     * independently of one another -- to the students on this sheet, without requiring every
     * component to be present the way {@link #submitSection} does. A component with no mark yet
     * is simply left unpublished; the instructor can press this again once it is filled in (Final
     * added later, for instance). Already-submitted rows are skipped: submission already means
     * every component is visible (see the student query in {@link GradeDAO#findStudentGradeRows}).
     *
     * <p>The Total/Letter/Points the student sees still only appear once the row is fully
     * submitted (rule G6) -- publishing components here never finalizes them, since they are not
     * meaningful until every required component exists.</p>
     */
    public void publishComponents(int sectionId, List<GradeSheetRow> rows, int actingUserId) {
        Section section = requireSection(sectionId);
        assertOwnsSection(section, actingUserId);
        assertGradeWindowOpen(section);
        assertMarksValid(rows);

        Connection connection = transactions.beginTransaction();
        try {
            for (GradeSheetRow row : rows) {
                if (row.isSubmitted()) {
                    continue; // already fully visible; nothing left to publish
                }
                row.recompute();
                int gradeId = upsertGrade(connection, row, actingUserId, false);

                boolean publishCoursework = row.getCourseworkMark() != null;
                boolean publishMidterm = row.getMidtermMark() != null;
                boolean publishLab = row.getLabMark() != null;
                boolean publishFinal = row.getFinalMark() != null;
                if (!publishCoursework && !publishMidterm && !publishLab && !publishFinal) {
                    continue;
                }

                gradeDao.publishComponents(connection, gradeId, publishCoursework, publishMidterm,
                        publishLab, publishFinal);
                row.setCourseworkPublished(row.isCourseworkPublished() || publishCoursework);
                row.setMidtermPublished(row.isMidtermPublished() || publishMidterm);
                row.setLabPublished(row.isLabPublished() || publishLab);
                row.setFinalPublished(row.isFinalPublished() || publishFinal);
            }
            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The marks could not be published.", e);
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
     *
     * <p>A row that was already submitted and has since been edited in the sheet (see
     * {@link GradeSheetRow#isEditedAfterSubmit()}) is corrected in place instead of being
     * skipped — this is how the instructor fixes a mistake while the grade window (G2) is still
     * open, distinct from {@link #adminOverride}, which needs no open window but does need
     * ADMIN. Same grade row, same {@code submitted_by}/{@code submitted_at}; only the marks,
     * total/letter/points and {@code last_modified_by}/{@code last_modified_at} change.</p>
     */
    public void submitSection(int sectionId, List<GradeSheetRow> rows, int actingUserId) {
        Section section = requireSection(sectionId);
        assertOwnsSection(section, actingUserId);
        assertGradeWindowOpen(section);
        assertMarksValid(rows);

        boolean hasCompleteUnsubmittedRow = false;
        boolean hasEditedSubmittedRow = false;
        for (GradeSheetRow row : rows) {
            if (row.isSubmitted()) {
                hasEditedSubmittedRow = hasEditedSubmittedRow || row.isEditedAfterSubmit();
                continue;
            }

            boolean completeCore = row.getMidtermMark() != null
                    && row.getFinalMark() != null;
            boolean completeComponent = row.isHasLab()
                    ? row.getLabMark() != null
                    : row.getCourseworkMark() != null;

            if (completeCore && completeComponent) {
                hasCompleteUnsubmittedRow = true;
            }
        }

        if (!hasCompleteUnsubmittedRow && !hasEditedSubmittedRow) {
            throw new ValidationException("There are no fully marked students ready to submit.");
        }

        Connection connection = transactions.beginTransaction();
        try {
            for (GradeSheetRow row : rows) {
                if (row.isSubmitted()) {
                    if (row.isEditedAfterSubmit()) {
                        row.recompute();
                        resubmitGrade(connection, row, actingUserId);
                        academicService.refreshAcademicRecord(connection, row.getStudentId());
                        notifySubmitted(connection, section, row, row.getGradeId());
                        row.setEditedAfterSubmit(false);
                    }
                    continue;
                }
                boolean completeCore = row.getMidtermMark() != null
                        && row.getFinalMark() != null;
                boolean completeComponent = row.isHasLab()
                        ? row.getLabMark() != null
                        : row.getCourseworkMark() != null;

                if (!completeCore || !completeComponent) {
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
    public void adminOverride(int enrollmentId, BigDecimal coursework, BigDecimal midterm, BigDecimal lab,
                              BigDecimal finalMark, int adminUserId, String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new ValidationException("Please type a reason for the change (at least 5 characters).");
        }
        assertIsAdmin(adminUserId);

        Enrollment enrollment = enrollmentDao.findById(enrollmentId)
                .orElseThrow(() -> new ValidationException("That enrollment no longer exists."));
        Grade existing = gradeDao.findByEnrollment(enrollmentId)
                .orElseThrow(() -> new ValidationException("There is no grade row to correct for this enrollment."));
        LetterGrade oldLetter = existing.getLetterGrade();

        Section correctionSection = sectionDao.findById(enrollment.getSectionId())
                .orElseThrow(() -> new ServiceException("That section no longer exists."));
        Course course = courseDao.findById(correctionSection.getCourseId())
                .orElseThrow(() -> new ServiceException("That course no longer exists."));
        boolean hasLab = course.isHasLab();

        checkOverrideMark(coursework, "Coursework", course.getCourseworkMaxMark());
        checkOverrideMark(midterm, "Midterm", course.getMidtermMaxMark());
        checkOverrideMark(lab, "Lab", course.getCourseworkMaxMark());
        checkOverrideMark(finalMark, "Final", course.getFinalMaxMark());

        BigDecimal total = GradeCalculator.totalMark(coursework, midterm, lab, finalMark, hasLab,
                course.getCourseworkWeight(), course.getMidtermWeight(), course.getFinalWeight(),
                course.getCourseworkMaxMark(), course.getMidtermMaxMark(), course.getFinalMaxMark());
        LetterGrade newLetter = GradeCalculator.letterGrade(total);

        Grade correction = new Grade();
        correction.setGradeId(existing.getGradeId());
        correction.setCourseworkMark(coursework);
        correction.setMidtermMark(midterm);
        correction.setLabMark(hasLab ? lab : null);
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

    private void checkOverrideMark(BigDecimal mark, String label, BigDecimal max) {
        if (mark != null && !GradeCalculator.isValidMark(mark, max)) {
            throw new ValidationException(label + " mark must be between 0 and "
                    + max.stripTrailingZeros().toPlainString() + ".");
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
        grade.setLabMark(row.isHasLab() ? row.getLabMark() : null);
        grade.setFinalMark(row.getFinalMark());
        grade.setTotalMark(row.getTotalMark());
        grade.setLetterGrade(row.getLetterGrade());
        grade.setGradePoints(row.getGradePoints());
        grade.setResultStatus(row.getLetterGrade() == null ? null : row.getLetterGrade().toResultStatus());
        grade.setSubmitted(false);
        grade.setCourseworkPublished(row.isCourseworkPublished());
        grade.setMidtermPublished(row.isMidtermPublished());
        grade.setLabPublished(row.isLabPublished());
        grade.setFinalPublished(row.isFinalPublished());
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

    /**
     * Corrects an already-submitted row in place while the grade window is still open (called
     * from {@link #submitSection} for a row with {@link GradeSheetRow#isEditedAfterSubmit()}).
     * Reuses {@link GradeDAO#overrideSubmitted}, the same write G5's {@link #adminOverride} uses,
     * so {@code submitted_by}/{@code submitted_at} are preserved and only
     * {@code last_modified_by}/{@code last_modified_at} change — {@code trg_Grade_Audit} picks up
     * the correction the same way either time.
     */
    private void resubmitGrade(Connection connection, GradeSheetRow row, int actingUserId) throws SQLException {
        Grade grade = new Grade();
        grade.setGradeId(row.getGradeId());
        grade.setCourseworkMark(row.getCourseworkMark());
        grade.setMidtermMark(row.getMidtermMark());
        grade.setLabMark(row.isHasLab() ? row.getLabMark() : null);
        grade.setFinalMark(row.getFinalMark());
        grade.setTotalMark(row.getTotalMark());
        grade.setLetterGrade(row.getLetterGrade());
        grade.setGradePoints(row.getGradePoints());
        grade.setResultStatus(row.getLetterGrade() == null ? null : row.getLetterGrade().toResultStatus());
        grade.setLastModifiedBy(actingUserId);
        grade.setLastModifiedAt(LocalDateTime.now());
        gradeDao.overrideSubmitted(connection, grade);
    }

    /** Notification N9 — phase-10 context/NOTIFICATION_MESSAGES.md. */
    private void notifySubmitted(Connection connection, Section section, GradeSheetRow row, int gradeId)
            throws SQLException {
        Course course = courseDao.findById(section.getCourseId()).orElse(null);
        Student student = studentDao.findById(connection, row.getStudentId()).orElse(null);
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

    // =====================================================================
    // Rescaling stored marks when the admin changes a component's max mark
    // =====================================================================

    /**
     * Called by {@link CourseService#updateCourse} in the same transaction as the course update.
     * When a component's max mark changes, every mark already stored for that course (submitted
     * or not) is rescaled proportionally to the new max — 95/100 becomes 19/20 — so its
     * percentage/grade meaning is preserved instead of the mark silently becoming invalid (rule
     * G3). This is not a correction (no reason, no audit entry, {@code is_submitted}/{@code
     * submitted_by}/{@code last_modified_by} untouched): the grade itself has not changed, only
     * the ruler it is measured against.
     */
    public void rescaleMarksForMaxMarkChange(Connection connection, Course oldCourse, Course newCourse)
            throws SQLException {
        boolean componentChanged = oldCourse.getCourseworkMaxMark().compareTo(newCourse.getCourseworkMaxMark()) != 0;
        boolean midtermChanged = oldCourse.getMidtermMaxMark().compareTo(newCourse.getMidtermMaxMark()) != 0;
        boolean finalChanged = oldCourse.getFinalMaxMark().compareTo(newCourse.getFinalMaxMark()) != 0;
        if (!componentChanged && !midtermChanged && !finalChanged) {
            return;
        }

        for (Grade grade : gradeDao.findByCourse(connection, newCourse.getCourseId())) {
            grade.setCourseworkMark(rescaleMark(grade.getCourseworkMark(),
                    oldCourse.getCourseworkMaxMark(), newCourse.getCourseworkMaxMark()));
            grade.setLabMark(rescaleMark(grade.getLabMark(),
                    oldCourse.getCourseworkMaxMark(), newCourse.getCourseworkMaxMark()));
            grade.setMidtermMark(rescaleMark(grade.getMidtermMark(),
                    oldCourse.getMidtermMaxMark(), newCourse.getMidtermMaxMark()));
            grade.setFinalMark(rescaleMark(grade.getFinalMark(),
                    oldCourse.getFinalMaxMark(), newCourse.getFinalMaxMark()));

            BigDecimal total = GradeCalculator.totalMark(grade.getCourseworkMark(), grade.getMidtermMark(),
                    grade.getLabMark(), grade.getFinalMark(), newCourse.isHasLab(),
                    newCourse.getCourseworkWeight(), newCourse.getMidtermWeight(), newCourse.getFinalWeight(),
                    newCourse.getCourseworkMaxMark(), newCourse.getMidtermMaxMark(), newCourse.getFinalMaxMark());
            LetterGrade letter = GradeCalculator.letterGrade(total);
            grade.setTotalMark(total);
            grade.setLetterGrade(letter);
            grade.setGradePoints(letter == null ? null : letter.getGradePoints());
            grade.setResultStatus(letter == null ? null : letter.toResultStatus());

            gradeDao.rescaleStoredGrade(connection, grade);
        }

        // Submitted grades feed students.cumulative_gpa/completed_credits (cached columns) —
        // the rescale just changed grade_points for some of them, so the cache must follow.
        for (int studentId : gradeDao.findSubmittedStudentIdsByCourse(connection, newCourse.getCourseId())) {
            academicService.refreshAcademicRecord(connection, studentId);
        }
    }

    /** {@code mark * newMax / oldMax}, rounded HALF_UP to 2 decimals — null and a 0 old max pass through unchanged. */
    private BigDecimal rescaleMark(BigDecimal mark, BigDecimal oldMax, BigDecimal newMax) {
        if (mark == null || oldMax == null || newMax == null
                || oldMax.compareTo(BigDecimal.ZERO) == 0 || oldMax.compareTo(newMax) == 0) {
            return mark;
        }
        return mark.multiply(newMax).divide(oldMax, 2, java.math.RoundingMode.HALF_UP);
    }
}


