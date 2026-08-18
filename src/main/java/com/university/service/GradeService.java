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
 *   G4  once is_submitted = 1 the instructor can no longer edit that section at all — only an
 *       ADMIN correction (adminOverride) or an ADMIN unlock (unlockSection) reopens it
 *   G5  only ADMIN may change a submitted grade, and the change is audited (by trg_Grade_Audit —
 *       this class never writes to audit_log itself)
 *   G6  on submission: enrollment -&gt; COMPLETED, then the academic record is recalculated
 * </pre>
 *
 * <p>{@link #submitSection} locks a WHOLE section at once: every actively enrolled student must
 * have all their required marks or nothing is submitted for anyone (Section 6.6's "Submit and
 * Lock" is an all-or-nothing action, not a per-student one).</p>
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
            if (row.isSubmitted()) {
                continue; // locked (G4) -- only Admin Unlock reopens a submitted row
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
     * 0, the enrollment stays ENROLLED, no GPA is recalculated. A row that is already submitted
     * (its section is locked) is skipped entirely — G4 — until an Admin Unlock
     * ({@link #unlockSection}) reopens it for editing.
     *
     * <p>Always writes the instructor's current values, including for a component that was
     * already published — the instructor's edit must never come back as the old value on the
     * next reload. What a student actually sees does not move: that stays frozen at whatever was
     * last released by {@link #publishComponents}/{@link #submitSection} until one of those runs
     * again, so this never leaks an in-progress edit early, and never sends a notification.</p>
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
                    continue; // G4 — locked; only Admin Unlock reopens a submitted row
                }
                row.recompute();

                if (row.getGradeId() == null) {
                    upsertGrade(connection, row, actingUserId, false);
                } else {
                    Grade grade = new Grade();
                    grade.setGradeId(row.getGradeId());
                    grade.setCourseworkMark(row.getCourseworkMark());
                    grade.setMidtermMark(row.getMidtermMark());
                    grade.setLabMark(row.isHasLab() ? row.getLabMark() : null);
                    grade.setFinalMark(row.getFinalMark());

                    // Total/Letter/Points are only hidden from the student by the SQL gate while
                    // one of the required final-stage components is not yet published; once every
                    // one of them already is, that gate is already open, so writing the freshly
                    // recomputed values here would leak the in-progress edit before Publish. Keep
                    // the previously stored (published) result in that case; only Publish is
                    // allowed to move it forward.
                    Grade existing = gradeDao.findById(row.getGradeId()).orElse(null);
                    boolean fullyPublished = existing != null && existing.isMidtermPublished()
                            && existing.isFinalPublished()
                            && (row.isHasLab() ? existing.isLabPublished() : existing.isCourseworkPublished());
                    if (fullyPublished) {
                        grade.setTotalMark(existing.getTotalMark());
                        grade.setLetterGrade(existing.getLetterGrade());
                        grade.setGradePoints(existing.getGradePoints());
                        grade.setResultStatus(existing.getResultStatus());
                    } else {
                        grade.setTotalMark(row.getTotalMark());
                        grade.setLetterGrade(row.getLetterGrade());
                        grade.setGradePoints(row.getGradePoints());
                        grade.setResultStatus(row.getLetterGrade() == null
                                ? null
                                : row.getLetterGrade().toResultStatus());
                    }
                    grade.setLastModifiedBy(actingUserId);
                    grade.setLastModifiedAt(LocalDateTime.now());

                    gradeDao.updateDraft(connection, grade);
                }
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
     * Releases the Midterm mark -- and ONLY the Midterm mark -- to the students on this sheet.
     * Coursework/Lab and Final are never released by this action, no matter how long ago they
     * were entered: they, along with the overall Total/Letter/Points, stay hidden until the whole
     * section is submitted via {@link #submitSection} ("Submit and Lock"), and even then remain
     * gated behind the student's instructor evaluation (see the student query in
     * {@link GradeDAO#findStudentGradeRows}). Already-submitted rows are skipped: submission
     * already means every component is visible.
     *
     * <p>Sends the student a notification the moment their Midterm becomes visible for the first
     * time (a plain {@link NotificationType#GRADE} message), and a separate
     * {@link NotificationType#WARNING} message instead whenever a Midterm mark that was
     * <em>already</em> published is edited and released again — the red "a published grade
     * changed" case.</p>
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

                Grade existing = row.getGradeId() == null ? null : gradeDao.findById(row.getGradeId()).orElse(null);
                boolean midtermAlreadyPublished = row.isMidtermPublished();

                row.recompute();
                int gradeId = upsertGrade(connection, row, actingUserId, false);

                boolean midtermMarkReady = row.getMidtermMark() != null;
                boolean publishMidterm = midtermMarkReady && !midtermAlreadyPublished;

                boolean midtermEdited = midtermAlreadyPublished && existing != null
                        && !marksEqual(existing.getMidtermPublishedMark(), row.getMidtermMark());

                if (!publishMidterm && !midtermEdited) {
                    continue;
                }

                gradeDao.publishComponents(connection, gradeId, false, true, false, false);
                row.setMidtermPublished(true);

                notifyPublished(connection, section, row, gradeId, midtermEdited);
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

    private boolean marksEqual(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    /** N9-style publish notification: a plain GRADE message the first time, a red WARNING when an
     *  already-published mark was edited and released again. */
    private void notifyPublished(Connection connection, Section section, GradeSheetRow row, int gradeId,
                                  boolean warning) throws SQLException {
        Course course = courseDao.findById(section.getCourseId()).orElse(null);
        Student student = studentDao.findById(connection, row.getStudentId()).orElse(null);
        if (course == null || student == null) {
            return;
        }
        if (warning) {
            notifications.notify(connection, student.getUserId(), NotificationType.WARNING,
                    "A published grade was changed",
                    "One of your already-published marks for " + course.getCourseCode() + " — "
                            + course.getCourseTitle() + " was edited by the instructor. Check My Grades "
                            + "for the new value.",
                    "grades", gradeId);
        } else {
            notifications.notify(connection, student.getUserId(), NotificationType.GRADE,
                    "A new grade was published",
                    "A new mark for " + course.getCourseCode() + " — " + course.getCourseTitle()
                            + " is now available. Check My Grades for details.",
                    "grades", gradeId);
        }
    }

    // =====================================================================
    // Submit and Lock — G4 and G6
    // =====================================================================

    /**
     * WHOLE-SECTION Submit and Lock: every actively enrolled student must have all their required
     * marks (Midterm + Final + Lab for a lab course, Midterm + Final + Coursework otherwise) or
     * the entire section is refused — nothing is submitted for anyone, and the exception message
     * names who is still missing marks. Only once every row qualifies does this run: for each row,
     * {@code grades.is_submitted = 1} (G4), {@code enrollments.status = COMPLETED} and the
     * student's academic record is recalculated (G6), and the student is notified (N9). All of it
     * happens in ONE transaction.
     *
     * <p>Once locked this way, only {@link #adminOverride} (a single student, with a reason) or
     * {@link #unlockSection} (the whole section, reopened for normal editing) can touch these
     * rows again — G4.</p>
     *
     * <p>Same red-vs-blue notification rule as {@link #publishComponents}: for a component that
     * was already published before this call, the value the student was actually last shown
     * ({@code *_published_mark}, not the instructor's raw working mark, which Save Draft may have
     * moved on without ever publishing it) is compared against what is about to be written: a
     * genuine change to a mark the student had already seen sends {@link NotificationType#WARNING}
     * instead of the normal {@link NotificationType#GRADE}. Comparing against that freshly-read
     * snapshot (not some earlier baseline) is also what keeps this from firing twice for the same
     * edit — if that value was already re-published (and so the snapshot already matches what is
     * about to be submitted), submitting it again is not a further change.</p>
     */
    public void submitSection(int sectionId, List<GradeSheetRow> rows, int actingUserId) {
        Section section = requireSection(sectionId);
        assertOwnsSection(section, actingUserId);
        assertGradeWindowOpen(section);
        assertMarksValid(rows);

        if (rows.isEmpty()) {
            throw new ValidationException("There are no students enrolled in this section.");
        }

        List<GradeSheetRow> unsubmitted = rows.stream().filter(row -> !row.isSubmitted()).toList();
        if (unsubmitted.isEmpty()) {
            throw new ValidationException("This section has already been submitted and locked.");
        }

        List<String> incomplete = new ArrayList<>();
        for (GradeSheetRow row : unsubmitted) {
            boolean completeCore = row.getMidtermMark() != null && row.getFinalMark() != null;
            boolean completeComponent = row.isHasLab()
                    ? row.getLabMark() != null
                    : row.getCourseworkMark() != null;
            if (!completeCore || !completeComponent) {
                incomplete.add(row.getStudentName());
            }
        }
        if (!incomplete.isEmpty()) {
            throw new ValidationException(incomplete.size() + " of " + unsubmitted.size()
                    + " student(s) are still missing required marks and must be completed before "
                    + "Submit and Lock: " + String.join(", ", incomplete) + ".");
        }

        Connection connection = transactions.beginTransaction();
        try {
            for (GradeSheetRow row : unsubmitted) {
                Grade existing = row.getGradeId() == null ? null : gradeDao.findById(row.getGradeId()).orElse(null);
                boolean courseworkAlreadyPublished = row.isCourseworkPublished();
                boolean midtermAlreadyPublished = row.isMidtermPublished();
                boolean labAlreadyPublished = row.isLabPublished();
                boolean finalAlreadyPublished = row.isFinalPublished();

                row.recompute();
                int gradeId = upsertGrade(connection, row, actingUserId, true);
                enrollmentDao.setStatus(connection, row.getEnrollmentId(), EnrollmentStatus.COMPLETED);
                academicService.refreshAcademicRecord(connection, row.getStudentId());

                boolean courseworkEdited = courseworkAlreadyPublished && existing != null
                        && !marksEqual(existing.getCourseworkPublishedMark(), row.getCourseworkMark());
                boolean midtermEdited = midtermAlreadyPublished && existing != null
                        && !marksEqual(existing.getMidtermPublishedMark(), row.getMidtermMark());
                boolean labEdited = labAlreadyPublished && existing != null
                        && !marksEqual(existing.getLabPublishedMark(), row.getLabMark());
                boolean finalEdited = finalAlreadyPublished && existing != null
                        && !marksEqual(existing.getFinalPublishedMark(), row.getFinalMark());
                boolean anyEdited = courseworkEdited || midtermEdited || labEdited || finalEdited;

                // Submission always makes every component visible (G6) -- stamp the snapshot the
                // same way publishComponents does, so a later Admin Unlock + re-edit compares
                // against what the student actually saw here, not a stale pre-submission value.
                gradeDao.publishComponents(connection, gradeId, !row.isHasLab(), true, row.isHasLab(), true);

                notifySubmitted(connection, section, row, gradeId, anyEdited);
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
    // Admin Unlock — reopens a Submit-&-Locked section for normal instructor editing
    // =====================================================================

    /**
     * The registrar's way to undo a whole-section Submit and Lock: every submitted grade in the
     * section goes back to {@code is_submitted = 0}, exactly the pre-submit state (marks and
     * publish flags are untouched — nothing is cleared). The instructor can then edit normally
     * again and must press "Submit and Lock" once more when finished.
     */
    public void unlockSection(int sectionId, int adminUserId) {
        assertIsAdmin(adminUserId);
        requireSection(sectionId);

        Connection connection = transactions.beginTransaction();
        try {
            int unlocked = gradeDao.unlockSection(connection, sectionId);
            if (unlocked == 0) {
                throw new ValidationException("This section has not been submitted, so there is nothing to unlock.");
            }
            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The section could not be unlocked.", e);
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

    /** Notification N9 — phase-10 context/NOTIFICATION_MESSAGES.md. {@code warning} is the same
     *  red "a published mark changed" case {@link #notifyPublished} sends, for a component that
     *  was already visible to the student and is only now being submitted with a different value. */
    private void notifySubmitted(Connection connection, Section section, GradeSheetRow row, int gradeId,
                                  boolean warning) throws SQLException {
        Course course = courseDao.findById(section.getCourseId()).orElse(null);
        Student student = studentDao.findById(connection, row.getStudentId()).orElse(null);
        if (course == null || student == null) {
            return;
        }
        if (warning) {
            notifications.notify(connection, student.getUserId(), NotificationType.WARNING,
                    "A published grade was changed",
                    "Your final grade for " + course.getCourseCode() + " — " + course.getCourseTitle()
                            + " has changed to " + row.getLetterGrade().getLabel() + " following a "
                            + "correction by the instructor. Your GPA has been updated.",
                    "grades", gradeId);
        } else {
            notifications.notify(connection, student.getUserId(), NotificationType.GRADE,
                    "Your grade for " + course.getCourseCode() + " is available",
                    "Your final grade for " + course.getCourseCode() + " — " + course.getCourseTitle()
                            + " is " + row.getLetterGrade().getLabel() + ". Your GPA has been updated.",
                    "grades", gradeId);
        }
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

            // The snapshot a student may currently be seeing has to follow the same ruler change,
            // or a published 95/100 would keep reading "95" after the course moves to a 20-point
            // scale, while the instructor's own (correctly rescaled) working value shows 19.
            grade.setCourseworkPublishedMark(rescaleMark(grade.getCourseworkPublishedMark(),
                    oldCourse.getCourseworkMaxMark(), newCourse.getCourseworkMaxMark()));
            grade.setLabPublishedMark(rescaleMark(grade.getLabPublishedMark(),
                    oldCourse.getCourseworkMaxMark(), newCourse.getCourseworkMaxMark()));
            grade.setMidtermPublishedMark(rescaleMark(grade.getMidtermPublishedMark(),
                    oldCourse.getMidtermMaxMark(), newCourse.getMidtermMaxMark()));
            grade.setFinalPublishedMark(rescaleMark(grade.getFinalPublishedMark(),
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




