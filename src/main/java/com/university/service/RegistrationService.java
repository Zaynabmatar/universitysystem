package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.CourseDAO;
import com.university.dao.CoursePrerequisiteDAO;
import com.university.dao.EnrollmentDAO;
import com.university.dao.SectionDAO;
import com.university.dao.SectionScheduleDAO;
import com.university.dao.SemesterDAO;
import com.university.dao.StudentDAO;
import com.university.dao.WaitlistDAO;
import com.university.enums.AcademicStanding;
import com.university.enums.EnrollmentStatus;
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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registering for classes, leaving them, and the queue for a full one.
 *
 * <p>Every check here has to pass before a seat is taken: the student is
 * active, the window is open, the course is not already on their list, the
 * prerequisites are behind them, nothing clashes on the timetable, the credit
 * load still fits, and a seat is genuinely free.</p>
 *
 * <p>The last of those is settled by the database rather than by reading a
 * count first. {@code SectionDAO.changeEnrolledCount} raises the counter only
 * while it stays within capacity, so two students clicking at the same moment
 * cannot both take the final seat.</p>
 */
public class RegistrationService {

    /** The most credits a student may carry in one semester. */
    public static final int MAX_CREDITS_PER_SEMESTER = 18;

    /** The fewest credits expected of a full-time student. */
    public static final int MIN_CREDITS_PER_SEMESTER = 12;

    /** A tighter ceiling while a student is on probation. */
    public static final int MAX_CREDITS_ON_PROBATION = 12;

    private final StudentDAO studentDao = new StudentDAO();
    private final SectionDAO sectionDao = new SectionDAO();
    private final SemesterDAO semesterDao = new SemesterDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final CoursePrerequisiteDAO prerequisiteDao = new CoursePrerequisiteDAO();
    private final SectionScheduleDAO scheduleDao = new SectionScheduleDAO();
    private final WaitlistDAO waitlistDao = new WaitlistDAO();
    private final NotificationService notifications = new NotificationService();

    /** Gives access to the connection helpers without exposing a whole data access object. */
    private final AbstractDAO transactions = new AbstractDAO() {
    };

    /**
     * Registers a student for a section.
     *
     * @return the new enrolment's key
     * @throws ServiceException when any rule refuses the request, with a
     *                          message explaining which one
     */
    public int register(int studentId, int sectionId) {
        ValidationException.requireId(studentId, "Student");
        ValidationException.requireId(sectionId, "Section");

        Student student = requireStudent(studentId);
        Section section = requireSection(sectionId);
        Semester semester = requireSemester(section.getSemesterId());
        Course course = requireCourse(section.getCourseId());

        if (!student.getStatus().canRegister()) {
            throw new ServiceException("A student whose status is " + student.getStatus()
                    + " cannot register for classes.");
        }
        if (section.getStatus() != SectionStatus.OPEN) {
            throw new ServiceException("This section is " + section.getStatus()
                    + " and is not taking registrations.");
        }
        if (!semester.isRegistrationOpen(LocalDateTime.now())) {
            throw new ServiceException("Registration for " + semester.getSemesterName()
                    + " is not open. It runs from " + semester.getRegistrationStart()
                    + " to " + semester.getRegistrationEnd() + ".");
        }
        if (enrollmentDao.isAlreadyRegisteredForCourse(studentId, course.getCourseId(),
                semester.getSemesterId())) {
            throw new ServiceException("You are already registered for "
                    + course.getCourseCode() + " this semester.");
        }

        List<Course> unmet =
                prerequisiteDao.findUnmetPrerequisiteCourses(studentId, course.getCourseId());
        if (!unmet.isEmpty()) {
            throw new ServiceException("You have not yet passed the prerequisites for "
                    + course.getCourseCode() + ": "
                    + unmet.stream().map(Course::getCourseCode).collect(Collectors.joining(", "))
                    + ".");
        }

        int clashes = scheduleDao.countClashes(studentId, semester.getSemesterId(), sectionId);
        if (clashes > 0) {
            throw new ServiceException("This section overlaps something already on your "
                    + "timetable. Choose a different section.");
        }

        int creditCap = creditCapFor(student);
        int current = enrollmentDao.sumCreditsInSemester(studentId, semester.getSemesterId());
        if (current + course.getCredits() > creditCap) {
            throw new ServiceException("This would put you on "
                    + (current + course.getCredits()) + " credits, above your limit of "
                    + creditCap + ".");
        }

        boolean repeat = enrollmentDao.hasPassedCourse(studentId, course.getCourseId());

        Connection connection = transactions.beginTransaction();
        try {
            // Raise the seat count first. It only moves while the section stays
            // within capacity, so this both books the seat and proves one was free.
            if (!sectionDao.changeEnrolledCount(connection, sectionId, 1)) {
                throw new ServiceException("This section just filled up. "
                        + "You can join the waiting list instead.");
            }

            Enrollment enrollment = new Enrollment();
            enrollment.setStudentId(studentId);
            enrollment.setSectionId(sectionId);
            enrollment.setStatus(EnrollmentStatus.ENROLLED);
            enrollment.setRepeat(repeat);
            enrollment.setCountsInGpa(true);
            int enrollmentId = enrollmentDao.insert(connection, enrollment);

            notifications.notify(connection, student.getUserId(), NotificationType.REGISTRATION,
                    "Registered for " + course.getCourseCode(),
                    "You are registered for " + course.getCourseCode() + " section "
                            + section.getSectionNumber() + " in " + semester.getSemesterName() + ".",
                    "enrollments", enrollmentId);

            connection.commit();
            return enrollmentId;
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
     * Joins the queue for a section that is full.
     *
     * @return the new queue entry's key
     */
    public int joinWaitlist(int studentId, int sectionId) {
        Student student = requireStudent(studentId);
        Section section = requireSection(sectionId);
        Semester semester = requireSemester(section.getSemesterId());
        Course course = requireCourse(section.getCourseId());

        if (!section.isFull()) {
            throw new ServiceException("This section still has seats. Register for it directly.");
        }
        if (enrollmentDao.findByStudentAndSection(studentId, sectionId).isPresent()) {
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

            notifications.notify(connection, student.getUserId(), NotificationType.WAITLIST,
                    "Waiting list for " + course.getCourseCode(),
                    "You are number " + entry.getPosition() + " in the queue for "
                            + course.getCourseCode() + " section " + section.getSectionNumber()
                            + " in " + semester.getSemesterName() + ".",
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

    /**
     * Drops a class, which leaves no mark on the record.
     *
     * <p>Only until the drop deadline. Afterwards the choice is
     * {@link #withdraw}.</p>
     */
    public void drop(int enrollmentId) {
        changeRegistration(enrollmentId, EnrollmentStatus.DROPPED);
    }

    /**
     * Withdraws from a class after the drop deadline has gone.
     *
     * <p>The seat is released either way, so the queue is offered it.</p>
     */
    public void withdraw(int enrollmentId) {
        changeRegistration(enrollmentId, EnrollmentStatus.WITHDRAWN);
    }

    /** The credits a student is carrying this semester. */
    public int currentCreditLoad(int studentId, int semesterId) {
        return enrollmentDao.sumCreditsInSemester(studentId, semesterId);
    }

    /** True when the load has reached the full-time minimum. */
    public boolean isFullTime(int studentId, int semesterId) {
        return currentCreditLoad(studentId, semesterId) >= MIN_CREDITS_PER_SEMESTER;
    }

    /** The ceiling that applies to one student, tightened while on probation. */
    public int creditCapFor(Student student) {
        return student.getAcademicStanding() == AcademicStanding.PROBATION
                ? MAX_CREDITS_ON_PROBATION
                : MAX_CREDITS_PER_SEMESTER;
    }

    /**
     * Tells a student why they cannot take a section, without taking a seat.
     *
     * @return the reason, or an empty optional when registration would succeed
     */
    public Optional<String> whyCannotRegister(int studentId, int sectionId) {
        try {
            Student student = requireStudent(studentId);
            Section section = requireSection(sectionId);
            Semester semester = requireSemester(section.getSemesterId());
            Course course = requireCourse(section.getCourseId());

            if (!student.getStatus().canRegister()) {
                return Optional.of("Your status is " + student.getStatus() + ".");
            }
            if (section.getStatus() != SectionStatus.OPEN) {
                return Optional.of("The section is " + section.getStatus() + ".");
            }
            if (!semester.isRegistrationOpen(LocalDateTime.now())) {
                return Optional.of("Registration is not open.");
            }
            if (enrollmentDao.isAlreadyRegisteredForCourse(studentId, course.getCourseId(),
                    semester.getSemesterId())) {
                return Optional.of("You already take this course this semester.");
            }
            List<Course> unmet =
                    prerequisiteDao.findUnmetPrerequisiteCourses(studentId, course.getCourseId());
            if (!unmet.isEmpty()) {
                return Optional.of("Prerequisites outstanding: " + unmet.stream()
                        .map(Course::getCourseCode).collect(Collectors.joining(", ")) + ".");
            }
            if (scheduleDao.countClashes(studentId, semester.getSemesterId(), sectionId) > 0) {
                return Optional.of("It clashes with your timetable.");
            }
            int cap = creditCapFor(student);
            int load = enrollmentDao.sumCreditsInSemester(studentId, semester.getSemesterId());
            if (load + course.getCredits() > cap) {
                return Optional.of("It would take you past your " + cap + " credit limit.");
            }
            if (section.isFull()) {
                return Optional.of("The section is full.");
            }
            return Optional.empty();
        } catch (ServiceException e) {
            return Optional.of(e.getMessage());
        }
    }

    /**
     * Moves a registration to dropped or withdrawn, frees the seat and offers
     * it to whoever is first in the queue.
     */
    private void changeRegistration(int enrollmentId, EnrollmentStatus target) {
        ValidationException.requireId(enrollmentId, "Enrolment");

        Enrollment enrollment = enrollmentDao.findById(enrollmentId)
                .orElseThrow(() -> new ServiceException("That registration no longer exists."));
        if (enrollment.getStatus() != EnrollmentStatus.ENROLLED) {
            throw new ServiceException("This registration is already " + enrollment.getStatus() + ".");
        }

        Section section = requireSection(enrollment.getSectionId());
        Semester semester = requireSemester(section.getSemesterId());
        Course course = requireCourse(section.getCourseId());
        Student student = requireStudent(enrollment.getStudentId());
        LocalDate today = LocalDate.now();

        if (target == EnrollmentStatus.DROPPED) {
            if (semester.getDropDeadline() != null && today.isAfter(semester.getDropDeadline())) {
                throw new ServiceException("The deadline to drop passed on "
                        + semester.getDropDeadline() + ". You may withdraw instead.");
            }
        } else if (semester.getWithdrawDeadline() != null
                && today.isAfter(semester.getWithdrawDeadline())) {
            throw new ServiceException("The deadline to withdraw passed on "
                    + semester.getWithdrawDeadline() + ".");
        }

        Connection connection = transactions.beginTransaction();
        try {
            enrollmentDao.setStatus(connection, enrollmentId, target);
            sectionDao.changeEnrolledCount(connection, section.getSectionId(), -1);

            notifications.notify(connection, student.getUserId(), NotificationType.REGISTRATION,
                    course.getCourseCode() + " "
                            + (target == EnrollmentStatus.DROPPED ? "dropped" : "withdrawn"),
                    "You are no longer registered for " + course.getCourseCode() + " section "
                            + section.getSectionNumber() + ".",
                    "enrollments", enrollmentId);

            promoteNextInQueue(connection, section, course, semester);

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
    }

    /**
     * Gives the seat just released to whoever is at the front of the queue.
     *
     * <p>Runs on the connection of the change that freed the seat, so the
     * promotion is undone too if that change is rolled back.</p>
     */
    private void promoteNextInQueue(Connection connection, Section section, Course course,
                                    Semester semester) {
        Optional<Waitlist> next = waitlistDao.findNextInQueue(section.getSectionId());
        if (next.isEmpty()) {
            return;
        }
        Waitlist entry = next.get();

        // Only proceed if the seat really is available.
        if (!sectionDao.changeEnrolledCount(connection, section.getSectionId(), 1)) {
            return;
        }

        Enrollment promoted = new Enrollment();
        promoted.setStudentId(entry.getStudentId());
        promoted.setSectionId(section.getSectionId());
        promoted.setStatus(EnrollmentStatus.ENROLLED);
        promoted.setRepeat(enrollmentDao.hasPassedCourse(entry.getStudentId(), course.getCourseId()));
        promoted.setCountsInGpa(true);
        int enrollmentId = enrollmentDao.insert(connection, promoted);

        waitlistDao.setStatus(connection, entry.getWaitlistId(), WaitlistStatus.PROMOTED);
        waitlistDao.closeGap(connection, section.getSectionId(), entry.getPosition());

        studentDao.findById(entry.getStudentId()).ifPresent(waiting ->
                notifications.notify(connection, waiting.getUserId(), NotificationType.WAITLIST,
                        "A seat opened in " + course.getCourseCode(),
                        "You have been moved off the waiting list into "
                                + course.getCourseCode() + " section " + section.getSectionNumber()
                                + " for " + semester.getSemesterName() + ".",
                        "enrollments", enrollmentId));
    }

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
