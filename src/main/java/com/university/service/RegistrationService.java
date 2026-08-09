package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.CourseDAO;
import com.university.dao.CoursePrerequisiteDAO;
import com.university.dao.EnrollmentDAO;
import com.university.dao.GradeDAO;
import com.university.dao.SectionDAO;
import com.university.dao.SectionScheduleDAO;
import com.university.dao.SemesterDAO;
import com.university.dao.StudentDAO;
import com.university.dao.WaitlistDAO;
import com.university.enums.EnrollmentStatus;
import com.university.enums.LetterGrade;
import com.university.enums.NotificationType;
import com.university.enums.SectionStatus;
import com.university.enums.WaitlistStatus;
import com.university.model.Course;
import com.university.model.Enrollment;
import com.university.model.Section;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.model.Waitlist;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Registering for classes, dropping them, and the eight rules that guard the door.
 *
 * <p>Everything here comes from project_details.md Section 6.1 (the eight registration rules,
 * checked in order and stopping at the first failure) and Section 6.4 (the drop/withdraw
 * deadlines). The credit ceiling in {@link #creditCapFor} is a flat 18 credits per semester for
 * every student (Phase 20 of the database-foundation cleanup; it previously varied by GPA band).
 * The exact wording of every message is copied from those sections character for character — the
 * professor reads them off the screen.</p>
 *
 * <p>Every check reads the data fresh; only the seat itself is claimed atomically, by
 * {@link SectionDAO#changeEnrolledCount}, which raises {@code enrolled_count} only while it
 * stays within capacity. That one conditional UPDATE is what stops two students clicking
 * Register on the last seat at the same moment from both succeeding — the count is never read
 * and written back as two separate steps.</p>
 */
public class RegistrationService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final StudentDAO studentDao = new StudentDAO();
    private final SectionDAO sectionDao = new SectionDAO();
    private final SemesterDAO semesterDao = new SemesterDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final GradeDAO gradeDao = new GradeDAO();
    private final CoursePrerequisiteDAO prerequisiteDao = new CoursePrerequisiteDAO();
    private final SectionScheduleDAO scheduleDao = new SectionScheduleDAO();
    private final WaitlistDAO waitlistDao = new WaitlistDAO();
    private final NotificationService notifications = new NotificationService();
    private final WaitlistService waitlistService = new WaitlistService();
    private final AcademicService academicService = new AcademicService();
    private final TuitionService tuitionService = new TuitionService();

    /** Gives access to the connection helpers without exposing a whole data access object. */
    private final AbstractDAO transactions = new AbstractDAO() {
    };

    // ============================================================== REGISTER

    /**
     * Registers a student for a section, enforcing rules R1-R8 in the exact order
     * project_details.md Section 6.1 lists them. The check stops at the first failure — a
     * student who breaks two rules at once is told about the earlier one.
     *
     * @throws RegistrationException naming the rule that refused the request
     */
    public void registerStudent(int studentId, int sectionId) {
        ValidationException.requireId(studentId, "Student");
        ValidationException.requireId(sectionId, "Section");

        Student student = requireStudent(studentId);
        Section section = requireSection(sectionId);
        Semester semester = requireSemester(section.getSemesterId());
        Course course = requireCourse(section.getCourseId());

        // R1 — the registration window. Two distinct reasons share the rule, and are told apart:
        // "not yet" (before registrationStart) and "no longer" (after registrationEnd) are different
        // facts about the database, and showing registrationStart for both — as this used to —
        // told a student registration "opens" on a date already months in the past.
        LocalDateTime now = LocalDateTime.now();
        if (!semester.isRegistrationOpen(now)) {
            String reason = now.isBefore(semester.getRegistrationStart())
                    ? "Registration is closed. It opens on " + DATE_TIME.format(semester.getRegistrationStart()) + "."
                    : "Registration is closed. It ended on " + DATE_TIME.format(semester.getRegistrationEnd()) + ".";
            throw new RegistrationException("R1", sectionId, reason);
        }

        // R2 — the student must be ACTIVE
        if (!student.getStatus().canRegister()) {
            throw new RegistrationException("R2", sectionId,
                    "Your account is " + student.getStatus() + ". Please contact the registrar.");
        }

        // FINANCIAL HOLD — a required balance left owing from an earlier semester blocks
        // registration into a later one, until it is settled. Does not block adding more courses
        // within the semester the balance itself belongs to.
        if (tuitionService.hasUnpaidPreviousBalance(studentId, semester)) {
            throw new RegistrationException("FINANCIAL_HOLD", sectionId,
                    "You have an unpaid balance from a previous semester. "
                    + "Please settle it before registering for a new semester.");
        }

        if (section.getStatus() != SectionStatus.OPEN) {
            throw new RegistrationException("SECTION_CLOSED", sectionId,
                    "This section is not open for registration.");
        }

        // A section with no meeting time at all is an incomplete offering — Admin has not
        // finished configuring it yet (Section 9/29 of the database-foundation cleanup: no valid
        // schedule means no registration, checked here rather than left to Admin discipline alone).
        if (scheduleDao.findBySection(sectionId).isEmpty()) {
            throw new RegistrationException("SECTION_NOT_SCHEDULED", sectionId,
                    "This section has not been scheduled yet and cannot be registered for.");
        }

        // R3 — every prerequisite must be passed
        List<Course> unmet = prerequisiteDao.findUnmetPrerequisiteCourses(studentId, course.getCourseId());
        if (!unmet.isEmpty()) {
            throw new RegistrationException("R3", sectionId,
                    "You must pass " + unmet.get(0).getCourseCode() + " before taking this course.");
        }

        // R4 — must not already have passed the course
        Optional<LetterGrade> passed = gradeDao.findPassedLetterGrade(studentId, course.getCourseId());
        if (passed.isPresent()) {
            throw new RegistrationException("R4", sectionId,
                    "You have already passed this course with grade " + passed.get() + ".");
        }

        // The student's existing relationship with THIS exact section, if any.
        Optional<Enrollment> existingSame = enrollmentDao.findByStudentAndSection(studentId, sectionId);
        boolean reviveDrop = false;
        if (existingSame.isPresent()) {
            EnrollmentStatus status = existingSame.get().getStatus();
            if (status == EnrollmentStatus.ENROLLED) {
                throw new RegistrationException("R8", sectionId, "You are already registered in this section.");
            }
            if (status == EnrollmentStatus.WITHDRAWN) {
                throw new RegistrationException("R8", sectionId,
                        "You withdrew from this section. Speak to the registrar if you need to re-join it.");
            }
            if (status == EnrollmentStatus.COMPLETED) {
                throw new RegistrationException("R8", sectionId, "You are already registered in this section.");
            }
            // DROPPED — the row is revived below rather than duplicated (UQ_enrollments_student_section).
            reviveDrop = true;
        }

        // R5 — a free seat (a fresh read; the real guard is the atomic UPDATE at write time)
        if (section.getEnrolledCount() >= section.getCapacity()) {
            int position = waitlistDao.countWaiting(sectionId) + 1;
            throw new RegistrationException("R5", sectionId, position,
                    "This section is full. Would you like to join the waitlist? (You are #" + position + ")");
        }

        // R6 — no timetable conflict
        String conflict = checkTimeConflict(null, studentId, sectionId, semester.getSemesterId());
        if (conflict != null) {
            throw new RegistrationException("R6", sectionId, conflict);
        }

        // R7 — the GPA-based credit limit
        String overLimit = checkCreditLimit(null, studentId, sectionId, semester.getSemesterId());
        if (overLimit != null) {
            throw new RegistrationException("R7", sectionId, overLimit);
        }

        // R8 — not already registered in another section of the same course
        if (!reviveDrop) {
            Optional<String> otherSection =
                    enrollmentDao.findOtherEnrolledSectionNumber(studentId, course.getCourseId(), sectionId);
            if (otherSection.isPresent()) {
                throw new RegistrationException("R8", sectionId,
                        "You are already registered in section " + otherSection.get() + " of this course.");
            }
        }

        // Section 5, repeat policy: any earlier COMPLETED attempt at this course makes the new one
        // a repeat. R4 has already ruled out a *passed* attempt, so in practice this only fires
        // for a previous fail — but it is written the same way project_details.md's own query is.
        boolean repeat = enrollmentDao.hasCompletedCourse(studentId, course.getCourseId());

        Connection connection = transactions.beginTransaction();
        try {
            // The atomic guard: raises enrolled_count only while it stays within capacity. If this
            // returns false, the seat was taken by someone else between the read above and now.
            if (!sectionDao.changeEnrolledCount(connection, sectionId, 1)) {
                int position = waitlistDao.countWaiting(sectionId) + 1;
                throw new RegistrationException("R5", sectionId, position,
                        "This section is full. Would you like to join the waitlist? (You are #" + position + ")");
            }

            int enrollmentId;
            if (reviveDrop) {
                Enrollment existing = existingSame.get();
                existing.setStatus(EnrollmentStatus.ENROLLED);
                existing.setRepeat(repeat);
                existing.setCountsInGpa(true);
                enrollmentDao.update(connection, existing);
                enrollmentId = existing.getEnrollmentId();
            } else {
                Enrollment enrollment = new Enrollment();
                enrollment.setStudentId(studentId);
                enrollment.setSectionId(sectionId);
                enrollment.setStatus(EnrollmentStatus.ENROLLED);
                enrollment.setRepeat(repeat);
                enrollment.setCountsInGpa(true);
                enrollmentId = enrollmentDao.insert(connection, enrollment);
            }

            // Section 5.5 repeat policy, step 2 — the new attempt has just been created above;
            // every older COMPLETED attempt at this course now stops counting in the GPA. That
            // changes the true cumulative GPA immediately, so the cache has to be refreshed in
            // the same transaction — left alone, students.cumulative_gpa/completed_credits would
            // still show the retired attempt's contribution until the next grade submission.
            if (repeat) {
                enrollmentDao.retireOlderCompletedAttempts(connection, studentId, course.getCourseId(), enrollmentId);
                academicService.refreshAcademicRecord(connection, studentId);
            }

            notifications.notify(connection, student.getUserId(), NotificationType.REGISTRATION,
                    "Registered successfully",
                    "You are now registered in " + course.getCourseCode() + "-" + section.getSectionNumber()
                            + " — " + course.getCourseTitle() + ".",
                    "enrollments", enrollmentId);

            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("Registration could not be completed.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    /**
     * RULE R6, exposed so a future waitlist promotion can re-run the same check inside its own
     * transaction. {@code conn} is accepted for that reason; a null connection reads normally.
     *
     * @return {@code null} when there is no conflict, otherwise the R6 message
     */
    public String checkTimeConflict(Connection conn, int studentId, int sectionId, int semesterId) {
        return scheduleDao.findStudentClash(studentId, semesterId, sectionId)
                .map(c -> "Time conflict with " + c.getCourseCode() + " on " + c.getDayOfWeek().name()
                        + " at " + HM.format(c.getNewStartTime()) + ".")
                .orElse(null);
    }

    /**
     * RULE R7, exposed for the same reason as {@link #checkTimeConflict}.
     *
     * @return {@code null} when the load still fits, otherwise the R7 message
     */
    public String checkCreditLimit(Connection conn, int studentId, int sectionId, int semesterId) {
        Student student = requireStudent(studentId);
        Section section = requireSection(sectionId);
        Course course = requireCourse(section.getCourseId());

        int max = creditCapFor(student);
        int current = enrollmentDao.sumCreditsInSemester(studentId, semesterId);
        int wouldBe = current + course.getCredits();
        if (wouldBe > max) {
            return "Credit limit exceeded. Your maximum is " + max + " credits ("
                    + current + " + " + course.getCredits() + ").";
        }
        return null;
    }

    /**
     * The maximum credits any student may carry in a normal semester: a flat ceiling, the same
     * for every student regardless of GPA or completed credits. (This used to vary by GPA band —
     * 12/18/21 — but that let students validly register above 18 credits, which the university's
     * registration rules do not allow; {@code student} stays a parameter so a future per-program
     * or per-student exception has somewhere to plug in without changing every call site.)
     */
    public int creditCapFor(Student student) {
        return MAX_SEMESTER_CREDITS;
    }

    /** The flat maximum credits a student may carry in one semester's registration. */
    public static final int MAX_SEMESTER_CREDITS = 18;

    /** The current semester, or null when none is set — Phase 08's `SemesterService` owns the read. */
    public Semester getCurrentSemester() {
        return semesterDao.findCurrent().orElse(null);
    }

    // ================================================================== DROP

    /** What happened when a registration was dropped or withdrawn. */
    public static class DropResult {
        private final boolean withdrawal;
        private final String resultTitle;
        private final String resultMessage;
        private String promotionMessage;

        DropResult(boolean withdrawal, String resultTitle, String resultMessage) {
            this.withdrawal = withdrawal;
            this.resultTitle = resultTitle;
            this.resultMessage = resultMessage;
        }

        /** True when this was a period-2 withdrawal (records against the record); false = a clean drop. */
        public boolean isWithdrawal() {
            return withdrawal;
        }

        public String getResultTitle() {
            return resultTitle;
        }

        public String getResultMessage() {
            return resultMessage;
        }

        /** Unused until a waitlist-promotion feature reads it. */
        public String getPromotionMessage() {
            return promotionMessage;
        }

        public void setPromotionMessage(String promotionMessage) {
            this.promotionMessage = promotionMessage;
        }
    }

    /**
     * Drops or withdraws a student from a section, following the three periods of Section 6.4:
     * a clean drop on or before the drop deadline, a {@code W} withdrawal up to the withdrawal
     * deadline, and refusal after that.
     *
     * @throws ServiceException when the enrolment does not exist, is not currently ENROLLED, or
     *                          the withdrawal deadline has passed
     */
    public DropResult dropSection(int studentId, int sectionId) {
        ValidationException.requireId(studentId, "Student");
        ValidationException.requireId(sectionId, "Section");

        Enrollment enrollment = enrollmentDao.findByStudentAndSection(studentId, sectionId)
                .orElseThrow(() -> new ServiceException("You are not currently registered in this section."));
        if (enrollment.getStatus() != EnrollmentStatus.ENROLLED) {
            throw new ServiceException("You are not currently registered in this section.");
        }

        Section section = requireSection(sectionId);
        Semester semester = requireSemester(section.getSemesterId());
        Course course = requireCourse(section.getCourseId());
        Student student = requireStudent(studentId);
        String label = course.getCourseCode() + "-" + section.getSectionNumber();

        LocalDate today = LocalDate.now();
        boolean withinDropWindow = semester.getDropDeadline() == null || !today.isAfter(semester.getDropDeadline());
        boolean withinWithdrawWindow =
                semester.getWithdrawDeadline() == null || !today.isAfter(semester.getWithdrawDeadline());

        if (!withinDropWindow && !withinWithdrawWindow) {
            throw new ServiceException("The withdrawal deadline has passed.");
        }

        Connection connection = transactions.beginTransaction();
        DropResult result;
        try {
            if (withinDropWindow) {
                enrollmentDao.setStatus(connection, enrollment.getEnrollmentId(), EnrollmentStatus.DROPPED);
                sectionDao.changeEnrolledCount(connection, sectionId, -1);

                String message = "You have dropped " + label + ". It will not appear on your transcript.";
                notifications.notify(connection, student.getUserId(), NotificationType.REGISTRATION,
                        "Course dropped", message, "enrollments", enrollment.getEnrollmentId());
                result = new DropResult(false, "Course dropped", message);
            } else {
                enrollmentDao.setStatus(connection, enrollment.getEnrollmentId(), EnrollmentStatus.WITHDRAWN);
                enrollmentDao.setCountsInGpa(connection, enrollment.getEnrollmentId(), false);
                sectionDao.changeEnrolledCount(connection, sectionId, -1);

                String message = "You have withdrawn from " + label
                        + ". A grade of W will appear on your transcript. It does not affect your GPA.";
                notifications.notify(connection, student.getUserId(), NotificationType.REGISTRATION,
                        "Course withdrawn", message, "enrollments", enrollment.getEnrollmentId());
                result = new DropResult(true, "Course withdrawn", message);
            }

            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The change could not be completed.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }

        // project_details.md Section 6.4 — both a DROPPED and a WITHDRAWN seat trigger waitlist
        // auto-promotion (Section 6.5). This runs only after the drop's own transaction has
        // committed; run any earlier and the seat does not look free yet.
        WaitlistService.PromotionResult promotion = waitlistService.promoteNext(sectionId);
        if (promotion.isPromoted()) {
            result.setPromotionMessage(promotion.getPromotedStudentName()
                    + " was promoted from the waitlist for " + promotion.getSectionLabel() + ".");
        }
        return result;
    }

    /**
     * True when today is still on or before the drop deadline for a section's semester — used by
     * the screen to choose which confirmation text to show *before* calling {@link #dropSection}.
     */
    public boolean isWithinFreeDropWindow(Semester semester) {
        LocalDate today = LocalDate.now();
        return semester.getDropDeadline() == null || !today.isAfter(semester.getDropDeadline());
    }

    // =============================================================== WAITLIST

    /**
     * Joins the queue for a section that is full. Only reached after the student has been shown
     * their queue position (rule R5) and agreed to join.
     *
     * @return the new queue entry's key
     */
    public int joinWaitlist(int studentId, int sectionId) {
        Student student = requireStudent(studentId);
        Section section = requireSection(sectionId);
        requireSemester(section.getSemesterId());
        Course course = requireCourse(section.getCourseId());

        if (!section.isFull()) {
            throw new ServiceException("This section still has seats. Register for it directly.");
        }
        if (enrollmentDao.findByStudentAndSection(studentId, sectionId)
                .map(e -> e.getStatus() == EnrollmentStatus.ENROLLED).orElse(false)) {
            throw new ServiceException("You already hold a place in this section.");
        }
        Optional<Waitlist> existing = waitlistDao.findByStudentAndSection(studentId, sectionId);
        if (existing.isPresent() && existing.get().getStatus() == WaitlistStatus.WAITING) {
            throw new ServiceException("You are already on the waiting list at position "
                    + existing.get().getPosition() + ".");
        }

        Connection connection = transactions.beginTransaction();
        try {
            Waitlist entry = new Waitlist();
            entry.setSectionId(sectionId);
            entry.setStudentId(studentId);
            entry.setPosition(waitlistDao.nextPosition(connection, sectionId));
            entry.setStatus(WaitlistStatus.WAITING);
            int waitlistId = waitlistDao.insert(connection, entry);

            String label = course.getCourseCode() + "-" + section.getSectionNumber();
            notifications.notify(connection, student.getUserId(), NotificationType.WAITLIST,
                    "Added to the waitlist",
                    "You have joined the waitlist for " + label + ". You are #" + entry.getPosition()
                            + " in line.",
                    "waitlist", waitlistId);

            connection.commit();
            return waitlistId;
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("You could not be added to the waiting list.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    // ================================================================== READS

    /** The credits a student is carrying this semester. */
    public int currentCreditLoad(int studentId, int semesterId) {
        return enrollmentDao.sumCreditsInSemester(studentId, semesterId);
    }

    /** The sections a student currently holds an ENROLLED seat in, for one semester. */
    public List<Enrollment> currentRegistrations(int studentId, int semesterId) {
        return enrollmentDao.findByStudentAndSemester(studentId, semesterId).stream()
                .filter(e -> e.getStatus() == EnrollmentStatus.ENROLLED)
                .toList();
    }

    /**
     * Every enrollment a student holds in one semester that still belongs on their class list —
     * ENROLLED, COMPLETED or WITHDRAWN, but not DROPPED. Used by the Classes page, which (unlike
     * {@link #currentRegistrations}) also needs to show a withdrawn or already-completed course.
     */
    public List<Enrollment> myClassesForSemester(int studentId, int semesterId) {
        return enrollmentDao.findByStudentAndSemester(studentId, semesterId).stream()
                .filter(e -> e.getStatus() != EnrollmentStatus.DROPPED)
                .toList();
    }

    /** Every enrollment a student has ever held, across every semester. */
    public List<Enrollment> allEnrollments(int studentId) {
        return enrollmentDao.findByStudent(studentId);
    }

    // ================================================================= HELPERS

    private Student requireStudent(int studentId) {
        return studentDao.findById(studentId)
                .orElseThrow(() -> new ServiceException("That student record was not found."));
    }

    private Section requireSection(int sectionId) {
        return sectionDao.findById(sectionId)
                .orElseThrow(() -> new ServiceException("That section was not found."));
    }

    private Semester requireSemester(int semesterId) {
        return semesterDao.findById(semesterId)
                .orElseThrow(() -> new ServiceException("That semester was not found."));
    }

    private Course requireCourse(int courseId) {
        return courseDao.findById(courseId)
                .orElseThrow(() -> new ServiceException("That course was not found."));
    }
}
