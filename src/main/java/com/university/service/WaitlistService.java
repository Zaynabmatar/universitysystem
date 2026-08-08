package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.CourseDAO;
import com.university.dao.EnrollmentDAO;
import com.university.dao.SectionDAO;
import com.university.dao.SectionScheduleDAO;
import com.university.dao.StudentDAO;
import com.university.dao.WaitlistDAO;
import com.university.enums.EnrollmentStatus;
import com.university.enums.NotificationType;
import com.university.enums.SectionStatus;
import com.university.enums.WaitlistStatus;
import com.university.model.Course;
import com.university.model.Enrollment;
import com.university.model.Section;
import com.university.model.Student;
import com.university.model.Waitlist;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implements project_details.md Section 6.5 — the waitlist.
 *
 * <p>The important rule (6.5 point 5): before auto-promoting, rules R6 (timetable conflict) and
 * R7 (credit limit) are RE-CHECKED. A student who is no longer eligible is skipped, told why, and
 * left {@code WAITING} — the next person in the queue is promoted instead. Only R6 and R7 are
 * re-checked: R1/R2 are system actions not student ones, and R3/R4 (prerequisites, already
 * passed) cannot change while a student is in the queue for one semester.</p>
 *
 * <p>Deliberately has no dependency on {@link RegistrationService} — that class holds a
 * {@code WaitlistService} field of its own (to trigger promotion after a drop), and a mutual
 * field dependency between the two would recurse forever at construction. The two rule checks
 * below are therefore small, self-contained copies of {@code RegistrationService}'s R6/R7 logic
 * rather than calls into it.</p>
 */
public class WaitlistService {

    private final SectionDAO sectionDao = new SectionDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final WaitlistDAO waitlistDao = new WaitlistDAO();
    private final SectionScheduleDAO scheduleDao = new SectionScheduleDAO();
    private final NotificationService notifications = new NotificationService();

    /** Gives access to the connection helpers without exposing a whole data access object. */
    private final AbstractDAO transactions = new AbstractDAO() {
    };

    // =============================================================== RESULT TYPES

    /** Why a waiting student was passed over during a promotion. */
    public static class SkippedStudent {
        private final int studentId;
        private final String studentName;
        private final String reason;

        SkippedStudent(int studentId, String studentName, String reason) {
            this.studentId = studentId;
            this.studentName = studentName;
            this.reason = reason;
        }

        public int getStudentId() {
            return studentId;
        }

        public String getStudentName() {
            return studentName;
        }

        public String getReason() {
            return reason;
        }
    }

    /** What happened when {@link #promoteNext} looked at a section's queue. */
    public static class PromotionResult {
        private boolean promoted;
        private int promotedStudentId = -1;
        private String promotedStudentName;
        private String sectionLabel;
        private final List<SkippedStudent> skipped = new ArrayList<>();

        public boolean isPromoted() {
            return promoted;
        }

        public int getPromotedStudentId() {
            return promotedStudentId;
        }

        public String getPromotedStudentName() {
            return promotedStudentName;
        }

        public String getSectionLabel() {
            return sectionLabel;
        }

        public List<SkippedStudent> getSkipped() {
            return skipped;
        }
    }

    // =============================================================== 6.5 rule 1 — the queue itself

    /** The queues one student is currently waiting in. */
    public List<Waitlist> getMyWaitlist(int studentId) {
        return waitlistDao.findByStudent(studentId);
    }

    /**
     * A student leaves a queue voluntarily. Everyone behind them moves up one place — the same
     * resequencing a promotion performs (Section 6.5 rule 3).
     */
    public void leaveWaitlist(int studentId, int sectionId) {
        Waitlist entry = waitlistDao.findByStudentAndSection(studentId, sectionId)
                .filter(w -> w.getStatus() == WaitlistStatus.WAITING)
                .orElseThrow(() -> new ServiceException("You are not on the waiting list for this section."));

        Connection connection = transactions.beginTransaction();
        try {
            waitlistDao.setStatus(connection, entry.getWaitlistId(), WaitlistStatus.CANCELLED);
            waitlistDao.closeGap(connection, sectionId, entry.getPosition());

            Section section = sectionDao.findById(sectionId).orElse(null);
            Student student = studentDao.findById(studentId).orElse(null);
            if (section != null && student != null) {
                Course course = courseDao.findById(section.getCourseId()).orElse(null);
                String label = course != null
                        ? course.getCourseCode() + "-" + section.getSectionNumber()
                        : "this section";
                notifications.notify(connection, student.getUserId(), NotificationType.INFO,
                        "Removed from the waitlist",
                        "You are no longer on the waitlist for " + label + ".", null, null);
            }

            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("You could not be removed from the waiting list.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    // =============================================================== 6.5 rules 2-5 — promotion

    /**
     * Called whenever a seat frees up in a section (i.e. from the drop/withdraw flow, always
     * after that drop's own transaction has committed — otherwise the seat does not look free
     * yet). Walks the queue front to back; the first eligible student is enrolled and the walk
     * stops. One freed seat, at most one promotion.
     */
    public PromotionResult promoteNext(int sectionId) {
        PromotionResult result = new PromotionResult();

        Section section = sectionDao.findById(sectionId).orElse(null);
        if (section == null) {
            return result;
        }
        Course course = courseDao.findById(section.getCourseId()).orElse(null);
        result.sectionLabel = course != null
                ? course.getCourseCode() + "-" + section.getSectionNumber()
                : "section " + sectionId;

        boolean hasFreeSeat = section.getStatus() == SectionStatus.OPEN
                && section.getEnrolledCount() < section.getCapacity();
        if (!hasFreeSeat) {
            return result;
        }

        List<Waitlist> queue = waitlistDao.findBySection(sectionId).stream()
                .filter(w -> w.getStatus() == WaitlistStatus.WAITING)
                .toList();

        for (Waitlist entry : queue) {
            int studentId = entry.getStudentId();
            Student student = studentDao.findById(studentId).orElse(null);
            if (student == null) {
                continue;
            }

            String reason = findTimeConflict(studentId, section);
            if (reason == null) {
                reason = checkCreditLimit(student, course, section.getSemesterId());
            }

            if (reason != null) {
                result.skipped.add(new SkippedStudent(studentId, student.getFullName(), reason));
                notifySkipped(student, result.sectionLabel, reason);
                continue;
            }

            // Either this student is promoted, or the atomic seat claim lost a race to a direct
            // registration between the free-seat check above and now — either way there is no
            // longer a seat to give to the next candidate, so the walk stops here regardless.
            promoteOne(entry, student, course, result);
            break;
        }

        return result;
    }

    /** Attempts to enroll one eligible student inside its own transaction. */
    private void promoteOne(Waitlist entry, Student student, Course course, PromotionResult result) {
        Connection connection = transactions.beginTransaction();
        try {
            if (!sectionDao.changeEnrolledCount(connection, entry.getSectionId(), 1)) {
                transactions.rollbackQuietly(connection);
                return;
            }

            int enrollmentId = insertOrReviveEnrollment(connection, student.getStudentId(),
                    entry.getSectionId(), course);
            waitlistDao.setStatus(connection, entry.getWaitlistId(), WaitlistStatus.PROMOTED);
            waitlistDao.closeGap(connection, entry.getSectionId(), entry.getPosition());

            notifications.notify(connection, student.getUserId(), NotificationType.WAITLIST,
                    "Seat available — you are enrolled",
                    "A seat opened in " + result.sectionLabel + " and you have been enrolled automatically.",
                    "enrollments", enrollmentId);

            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The waitlist promotion could not be completed.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }

        result.promoted = true;
        result.promotedStudentId = student.getStudentId();
        result.promotedStudentName = student.getFullName();
    }

    /** UNIQUE(student_id, section_id) means a previously DROPPED row is revived, not duplicated. */
    private int insertOrReviveEnrollment(Connection connection, int studentId, int sectionId, Course course) {
        Optional<Enrollment> existing = enrollmentDao.findByStudentAndSection(studentId, sectionId);
        boolean repeat = course != null && enrollmentDao.hasCompletedCourse(studentId, course.getCourseId());

        if (existing.isPresent()) {
            Enrollment enrollment = existing.get();
            enrollment.setStatus(EnrollmentStatus.ENROLLED);
            enrollment.setRepeat(repeat);
            enrollment.setCountsInGpa(true);
            enrollmentDao.update(connection, enrollment);
            return enrollment.getEnrollmentId();
        }

        Enrollment enrollment = new Enrollment();
        enrollment.setStudentId(studentId);
        enrollment.setSectionId(sectionId);
        enrollment.setStatus(EnrollmentStatus.ENROLLED);
        enrollment.setRepeat(repeat);
        enrollment.setCountsInGpa(true);
        return enrollmentDao.insert(connection, enrollment);
    }

    // =============================================================== 6.5 rule 5 — the re-check

    /** RULE R6, re-applied. {@code null} when there is no conflict. */
    private String findTimeConflict(int studentId, Section section) {
        return scheduleDao.findStudentClash(studentId, section.getSemesterId(), section.getSectionId())
                .map(c -> "Time conflict with " + c.getCourseCode() + " on " + c.getDayOfWeek().name()
                        + " at " + c.getNewStartTime() + ".")
                .orElse(null);
    }

    /** RULE R7, re-applied. {@code null} when the load still fits. */
    private String checkCreditLimit(Student student, Course course, int semesterId) {
        if (course == null) {
            return null;
        }
        int max = creditCapFor(student);
        int current = enrollmentDao.sumCreditsInSemester(student.getStudentId(), semesterId);
        int wouldBe = current + course.getCredits();
        if (wouldBe > max) {
            return "Credit limit exceeded. Your maximum is " + max + " credits ("
                    + current + " + " + course.getCredits() + ").";
        }
        return null;
    }

    /**
     * The flat maximum credits a student may carry (Phase 20: the same {@code 18} for every
     * student, no GPA band). Reads {@link RegistrationService#MAX_SEMESTER_CREDITS} directly
     * rather than holding a {@code RegistrationService} field: that class already holds a
     * {@code WaitlistService} field of its own, and a field on each side referencing the other
     * would recurse forever at construction.
     */
    private int creditCapFor(Student student) {
        return RegistrationService.MAX_SEMESTER_CREDITS;
    }

    private void notifySkipped(Student student, String sectionLabel, String reason) {
        notifications.notify(student.getUserId(), NotificationType.WARNING,
                "Waitlist seat could not be given to you",
                "A seat opened in " + sectionLabel + " but you could not be enrolled automatically: "
                        + reason + " You are still on the waitlist.");
    }
}
