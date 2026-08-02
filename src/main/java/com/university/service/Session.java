package com.university.service;

import com.university.enums.UserRole;
import com.university.model.Instructor;
import com.university.model.Student;
import com.university.model.User;

/**
 * Who is signed in for this run of the program.
 *
 * <p>A desktop program has exactly one person in front of it, so this is a
 * single value rather than something passed from screen to screen. It is set
 * by {@link AuthService#login} and cleared by {@link AuthService#logout}.</p>
 *
 * <p>The student and instructor records are resolved once at sign-in, so a
 * screen never has to turn a user back into a student on its own.</p>
 */
public final class Session {

    private static Session current;

    private final User user;
    private final Student student;
    private final Instructor instructor;

    private Session(User user, Student student, Instructor instructor) {
        this.user = user;
        this.student = student;
        this.instructor = instructor;
    }

    /** Opens a session. Called by {@link AuthService} once a password checks out. */
    static void begin(User user, Student student, Instructor instructor) {
        current = new Session(user, student, instructor);
    }

    /** Closes the session. */
    static void end() {
        current = null;
    }

    /** True when somebody is signed in. */
    public static boolean isActive() {
        return current != null;
    }

    /**
     * The open session.
     *
     * @throws ServiceException when nobody is signed in
     */
    public static Session current() {
        if (current == null) {
            throw new ServiceException("You are not signed in.");
        }
        return current;
    }

    /** The account that signed in. */
    public User getUser() {
        return user;
    }

    /** The role that decides which screens open. */
    public UserRole getRole() {
        return user.getRole();
    }

    /** The name to greet the person by. */
    public String getDisplayName() {
        if (student != null) {
            return student.getFullName();
        }
        if (instructor != null) {
            return instructor.getFullName();
        }
        return user.getUsername();
    }

    /** The student record, or null when the account is not a student. */
    public Student getStudent() {
        return student;
    }

    /** The instructor record, or null when the account is not an instructor. */
    public Instructor getInstructor() {
        return instructor;
    }

    public boolean isAdmin() {
        return user.getRole() == UserRole.ADMIN;
    }

    public boolean isInstructor() {
        return user.getRole() == UserRole.INSTRUCTOR;
    }

    public boolean isStudent() {
        return user.getRole() == UserRole.STUDENT;
    }

    /**
     * The signed-in student's key.
     *
     * @throws ServiceException when the account is not a student
     */
    public int requireStudentId() {
        if (student == null) {
            throw new ServiceException("This account is not a student account.");
        }
        return student.getStudentId();
    }

    /**
     * The signed-in instructor's key.
     *
     * @throws ServiceException when the account is not an instructor
     */
    public int requireInstructorId() {
        if (instructor == null) {
            throw new ServiceException("This account is not an instructor account.");
        }
        return instructor.getInstructorId();
    }

    /**
     * Stops anybody but an administrator going further.
     *
     * @throws ServiceException when the signed-in account is not an administrator
     */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw new ServiceException("Only an administrator may do this.");
        }
    }
}
