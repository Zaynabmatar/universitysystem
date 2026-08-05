package com.university.service;

import com.university.dao.AdminDAO;
import com.university.dao.InstructorDAO;
import com.university.dao.StudentDAO;
import com.university.dao.UserDAO;
import com.university.database.DBConnection;
import com.university.enums.UserRole;
import com.university.model.Instructor;
import com.university.model.Student;
import com.university.model.User;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Signing in, signing out and changing a password.
 *
 * <p>A failed sign-in always gives the same message whether the User ID was
 * unknown or the password was wrong. Saying which one was at fault would tell
 * a stranger that an ID exists, which is worth more to them than to anybody
 * honest.</p>
 *
 * <p>There is one login identifier in this system and it is
 * {@code users.user_id} — the same number the screens call Student ID or
 * Instructor ID. Because it is an IDENTITY column it is unique across every
 * role, so the account is found by that number alone.</p>
 *
 * <p>The role picked on the role-selection screen is still required, and is
 * still checked: it is the door the account came in through, and an account
 * may only come in through its own. An instructor who clicks STUDENT is told
 * the same vague thing as somebody who mistyped a password — which door an
 * account belongs to is no more a stranger's business than whether an ID
 * exists.</p>
 */
public class AuthService {

    /** Deliberately vague, for the reason above. */
    public static final String SIGN_IN_FAILED = "Incorrect ID or password.";

    private final UserDAO userDao = new UserDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final InstructorDAO instructorDao = new InstructorDAO();
    private final AdminDAO adminDao = new AdminDAO();

    /**
     * Checks a password and opens a session.
     *
     * <p>The student or instructor record behind the account is loaded here,
     * so no screen has to do it later.</p>
     *
     * @param selectedRole the role picked on the role-selection screen; the
     *                     account must belong to it. {@code null} means no role
     *                     was picked and any account may sign in — nothing in
     *                     the application passes null, since every route to the
     *                     sign-in screen goes through role selection first.
     * @param idText       the User ID as typed on the sign-in screen — a
     *                     student's Student ID, an instructor's Instructor ID,
     *                     and in every case {@code users.user_id}
     * @return the open session, also reachable through {@link Session#current()}
     * @throws ServiceException if the details are wrong or the account is disabled
     */
    public Session login(UserRole selectedRole, String idText, String password) {
        ValidationException.requireText(idText, "User ID");
        ValidationException.requireText(password, "Password");

        int userId;
        try {
            userId = Integer.parseInt(idText.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(SIGN_IN_FAILED);
        }
        if (userId <= 0) {
            throw new ServiceException(SIGN_IN_FAILED);
        }

        // ===================== TEMPORARY LOGIN DEBUG — START =====================
        // Prints what the sign-in screen sent, what the database answered, and
        // the full stack trace of any SQL failure. Delete this block (and the
        // one it closes below, plus the [DB] line in DBConnection) once the
        // restored database is confirmed good.
        System.out.println("[LOGIN] ---------------------------------------------");
        System.out.println("[LOGIN] " + DBConnection.describeConnected());
        System.out.println("[LOGIN] entered user_id = " + userId);
        System.out.println("[LOGIN] selected role   = " + selectedRole);

        Optional<User> found;
        try {
            found = userDao.findById(userId);
        } catch (RuntimeException e) {
            System.out.println("[LOGIN] lookup threw — full stack trace follows:");
            e.printStackTrace(System.out);
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                System.out.println("[LOGIN] caused by:");
                cause.printStackTrace(System.out);
            }
            throw e;
        }
        System.out.println("[LOGIN] user found      = " + found.isPresent());
        found.ifPresent(u -> {
            System.out.println("[LOGIN] role from DB    = " + u.getRole());
            System.out.println("[LOGIN] is_active       = " + u.isActive());
            System.out.println("[LOGIN] hash matches <user_id>@iuL = "
                    + PasswordHasher.verify(PasswordHasher.defaultPasswordFor(userId),
                                            u.getPasswordHash()));
        });
        // ====================== TEMPORARY LOGIN DEBUG — END ======================

        User user = found.orElseThrow(() -> new ServiceException(SIGN_IN_FAILED));

        if (!PasswordHasher.verify(password, user.getPasswordHash())) {
            System.out.println("[LOGIN] password did not verify against the stored hash");
            throw new ServiceException(SIGN_IN_FAILED);
        }
        // Wrong door. Checked after the password so that a stranger cannot use
        // the three buttons to learn which role an ID belongs to.
        if (selectedRole != null && user.getRole() != selectedRole) {
            System.out.println("[LOGIN] role mismatch: selected " + selectedRole
                    + " but the account is " + user.getRole());
            throw new ServiceException(SIGN_IN_FAILED);
        }
        if (!user.isActive()) {
            throw new ServiceException("This account has been deactivated. "
                    + "Please contact the registrar.");
        }

        UserRole role = user.getRole();
        Student student = role == UserRole.STUDENT ? studentDao.findByUserId(userId).orElse(null) : null;
        Instructor instructor = role == UserRole.INSTRUCTOR
                ? instructorDao.findByUserId(userId).orElse(null) : null;

        requireRoleRecordUsable(role, student, instructor, userId);

        LocalDateTime now = LocalDateTime.now();
        userDao.touchLastLogin(user.getUserId(), now);
        user.setLastLogin(now);

        Session.begin(user, student, instructor);
        return Session.current();
    }

    /**
     * The account is real and the password was right; this is the last gate.
     *
     * <p>An instructor or admin can be switched off on their own record as
     * well as on {@code users.is_active}, and a role row can be missing
     * outright if somebody edited the database by hand. Neither is the
     * password's fault, so neither gets the vague message — except a missing
     * row, which is exactly the case where saying nothing specific is right.</p>
     *
     * <p>Students have no {@code is_active} column of their own, only
     * {@code status}, so {@code users.is_active} (already checked) is their
     * only gate.</p>
     */
    private void requireRoleRecordUsable(UserRole role, Student student, Instructor instructor,
                                         int userId) {
        switch (role) {
            case STUDENT -> {
                if (student == null) {
                    throw new ServiceException(SIGN_IN_FAILED);
                }
            }
            case INSTRUCTOR -> {
                if (instructor == null) {
                    throw new ServiceException(SIGN_IN_FAILED);
                }
                if (!instructor.isActive()) {
                    throw new ServiceException("This account has been deactivated. "
                            + "Please contact the registrar.");
                }
            }
            case ADMIN -> {
                boolean usable = adminDao.findByUserId(userId).filter(a -> a.isActive()).isPresent();
                if (!usable) {
                    throw new ServiceException("This account has been deactivated. "
                            + "Please contact the registrar.");
                }
            }
        }
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
