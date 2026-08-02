package com.university.service;

import com.university.dao.InstructorDAO;
import com.university.dao.StudentDAO;
import com.university.dao.UserDAO;
import com.university.model.Instructor;
import com.university.model.Student;
import com.university.model.User;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Signing in, signing out and changing a password.
 *
 * <p>A failed sign-in always gives the same message whether the name was
 * unknown or the password was wrong. Saying which one was at fault would tell
 * a stranger that a username exists, which is worth more to them than to
 * anybody honest.</p>
 */
public class AuthService {

    /** Deliberately vague, for the reason above. */
    private static final String SIGN_IN_FAILED = "Incorrect username or password.";

    private final UserDAO userDao = new UserDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final InstructorDAO instructorDao = new InstructorDAO();

    /**
     * Checks a password and opens a session.
     *
     * <p>The student or instructor record behind the account is loaded here,
     * so no screen has to do it later.</p>
     *
     * @return the open session, also reachable through {@link Session#current()}
     * @throws ServiceException if the details are wrong or the account is disabled
     */
    public Session login(String username, String password) {
        ValidationException.requireText(username, "Username");
        ValidationException.requireText(password, "Password");

        Optional<User> found = userDao.findByUsername(username.trim());
        if (found.isEmpty()) {
            throw new ServiceException(SIGN_IN_FAILED);
        }

        User user = found.get();
        if (!PasswordHasher.verify(password, user.getPasswordHash())) {
            throw new ServiceException(SIGN_IN_FAILED);
        }
        if (!user.isActive()) {
            throw new ServiceException("This account has been deactivated. "
                    + "Please contact the registrar.");
        }

        Student student = null;
        Instructor instructor = null;
        switch (user.getRole()) {
            case STUDENT -> student = studentDao.findByUserId(user.getUserId()).orElseThrow(
                    () -> new ServiceException(
                            "This account has no student record. Please contact the administration."));
            case INSTRUCTOR -> instructor = instructorDao.findByUserId(user.getUserId()).orElseThrow(
                    () -> new ServiceException(
                            "This account has no instructor record. Please contact the administration."));
            case ADMIN -> {
                // An administrator has no second record; the user row is enough.
            }
        }

        LocalDateTime now = LocalDateTime.now();
        userDao.touchLastLogin(user.getUserId(), now);
        user.setLastLogin(now);

        Session.begin(user, student, instructor);
        return Session.current();
    }

    /** Closes the session. Safe to call when nobody is signed in. */
    public void logout() {
        Session.end();
    }

    /**
     * Changes a password after checking the old one.
     *
     * @throws ServiceException if the current password is wrong or the new one
     *                          repeats it
     */
    public void changePassword(int userId, String currentPassword, String newPassword) {
        ValidationException.requireId(userId, "User");
        ValidationException.requireText(currentPassword, "Current password");
        ValidationException.requireText(newPassword, "New password");

        User user = userDao.findById(userId)
                .orElseThrow(() -> new ServiceException("That account no longer exists."));

        if (!PasswordHasher.verify(currentPassword, user.getPasswordHash())) {
            throw new ServiceException("Your current password is not correct.");
        }
        if (currentPassword.equals(newPassword)) {
            throw new ValidationException("The new password must differ from the old one.");
        }

        userDao.updatePasswordHash(userId, PasswordHasher.hash(newPassword));
    }

    /**
     * Sets a password without knowing the old one, for an administrator
     * helping somebody locked out.
     *
     * @throws ServiceException when the caller is not an administrator
     */
    public void resetPassword(int userId, String newPassword) {
        Session.current().requireAdmin();
        ValidationException.requireId(userId, "User");
        userDao.findById(userId)
                .orElseThrow(() -> new ServiceException("That account no longer exists."));
        userDao.updatePasswordHash(userId, PasswordHasher.hash(newPassword));
    }

    /**
     * Enables or disables an account.
     *
     * @throws ServiceException when the caller is not an administrator
     */
    public void setAccountActive(int userId, boolean active) {
        Session.current().requireAdmin();
        userDao.setActive(userId, active);
    }
}
