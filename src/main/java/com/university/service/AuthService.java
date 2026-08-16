package com.university.service;

import com.university.dao.InstructorDAO;
import com.university.dao.StudentDAO;
import com.university.dao.UserDAO;
import com.university.database.DBConnection;
import com.university.enums.UserRole;
import com.university.model.Instructor;
import com.university.model.Student;
import com.university.model.User;
import com.university.util.ValidationUtil;

import java.time.LocalDateTime;

/**
 * Signing in, signing out and changing a password.
 *
 * <p>A failed sign-in always gives the same message whether the User ID was
 * unknown or the password was wrong. Saying which one was at fault would tell
 * a stranger that an ID exists, which is worth more to them than to anybody
 * honest.</p>
 *
 * <p>The number typed on the sign-in screen is always {@code users.user_id} —
 * the one identity in the system — for every role, Admin included. It is
 * never {@code students.student_id} or {@code instructors.instructor_id}:
 * those are separate IDENTITY sequences from {@code user_id} and from each
 * other, so reading the typed number against the wrong one used to resolve to
 * a different person (or nobody) depending on how far the sequences had
 * drifted apart.</p>
 *
 * <p>The role picked on the role-selection screen is a <em>door</em>, not a
 * lookup table: the typed {@code user_id} is read from {@code dbo.users}
 * directly, and the role selected must match {@code users.role} on the
 * account it names, checked after the password so a stranger cannot use the
 * three buttons to learn which role an ID belongs to. Once the account is
 * found, its student or instructor profile row (if any) is loaded by
 * {@code user_id}, and {@code users.role} is what the caller uses afterwards
 * to route to the STUDENT, INSTRUCTOR or ADMIN area — never the button that
 * was clicked to get here.</p>
 */
public class AuthService {

    /** Deliberately vague, for the reason above. */
    public static final String SIGN_IN_FAILED = "Incorrect ID or password.";

    /**
     * Consecutive failed attempts a STUDENT or INSTRUCTOR account tolerates
     * before it locks. ADMIN is never subject to this — see {@link #login}.
     */
    private static final int LOCKOUT_THRESHOLD = 5;

    private static final String ACCOUNT_LOCKED_MESSAGE =
            "Account locked. Please contact the administrator.";

    private final UserDAO userDao = new UserDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final InstructorDAO instructorDao = new InstructorDAO();

    /**
     * Console trace of a sign-in, off unless the application is started with
     * {@code -Duniversity.debug.login=true}.
     *
     * <p>Off by default because the console is not the place to announce who is
     * signing in. On, it names the server and database actually reached, both
     * IDs, the role on the account and the step that refused — enough to tell a
     * wrong ID, a wrong password, a wrong door and a wrong database apart on a
     * machine where sign-in misbehaves. It never prints the password or the
     * stored hash.</p>
     */
    private static final boolean TRACE = Boolean.getBoolean("university.debug.login");

    private static void trace(String message) {
        if (TRACE) {
            System.out.println("[LOGIN] " + message);
        }
    }

    /**
     * Checks a password and opens a session.
     *
     * <p>The student or instructor record behind the account is loaded here,
     * so no screen has to do it later.</p>
     *
     * @param selectedRole the role picked on the role-selection screen — a
     *                     door the found account must match, checked after
     *                     the password. {@code null} means no role was picked;
     *                     nothing in the application passes null, since every
     *                     route to the sign-in screen goes through role
     *                     selection first.
     * @param idText       the ID as typed on the sign-in screen — always
     *                     {@code users.user_id}, for every role
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

        if (TRACE) {
            trace("---------------------------------------------");
            trace(DBConnection.describeConnected());
            trace("typed user_id = " + userId
                    + (selectedRole == null ? "" : " through the " + selectedRole.getLabel() + " door"));
        }

        Student student = null;
        Instructor instructor = null;

        User user = userDao.findById(userId).orElseThrow(() -> {
            trace("no dbo.users row for user_id " + userId);
            return new ServiceException(SIGN_IN_FAILED);
        });
        trace("role on account  = " + user.getRole() + ", is_active = " + user.isActive());

        boolean lockable = user.getRole() == UserRole.STUDENT || user.getRole() == UserRole.INSTRUCTOR;

        // A locked account is refused before the password is even checked -
        // the correct password does not open it, only an administrator's
        // "Unlock Account" does.
        if (lockable && user.isLocked()) {
            trace("refused: account is locked");
            throw new ServiceException(ACCOUNT_LOCKED_MESSAGE);
        }

        if (!PasswordHasher.verify(password, user.getPasswordHash())) {
            trace("refused: password did not verify against the stored hash");
            if (lockable) {
                int attempts = userDao.registerFailedLogin(user.getUserId(), LOCKOUT_THRESHOLD);
                if (attempts >= LOCKOUT_THRESHOLD) {
                    trace("account locked after " + attempts + " consecutive failed attempts");
                    throw new ServiceException(ACCOUNT_LOCKED_MESSAGE);
                }
                throw new ServiceException(
                        "Incorrect ID or password. Attempt " + attempts + " of " + LOCKOUT_THRESHOLD + ".");
            }
            throw new ServiceException(SIGN_IN_FAILED);
        }
        trace("password         = verified");
        if (lockable && user.getFailedLoginAttempts() > 0) {
            userDao.resetFailedLogin(user.getUserId());
        }
        // Wrong door. Checked after the password so that a stranger cannot use
        // the three buttons to learn which role an ID belongs to.
        if (selectedRole != null && user.getRole() != selectedRole) {
            trace("refused: signed in through the " + selectedRole
                    + " door but the account is " + user.getRole());
            throw new ServiceException(SIGN_IN_FAILED);
        }
        if (!user.isActive()) {
            trace("refused: users.is_active is false");
            throw new ServiceException("This account has been deactivated. "
                    + "Please contact the registrar.");
        }

        // users.role - never the door that was clicked - decides which
        // profile row to load and, after this method returns, which area the
        // caller routes to.
        UserRole role = user.getRole();
        if (role == UserRole.STUDENT) {
            student = studentDao.findByUserId(userId).orElse(null);
        } else if (role == UserRole.INSTRUCTOR) {
            instructor = instructorDao.findByUserId(userId).orElse(null);
        }

        requireRoleRecordUsable(role, student, instructor, userId);
        trace("signed in        = OK");

        LocalDateTime now = LocalDateTime.now();
        userDao.touchLastLogin(user.getUserId(), now);
        user.setLastLogin(now);

        Session.begin(user, student, instructor);
        return Session.current();
    }

    /**
     * The account is real and the password was right; this is the last gate.
     *
     * <p>An instructor can be switched off on their own record as well as on
     * {@code users.is_active}, and a role row can be missing outright if
     * somebody edited the database by hand. Neither is the password's fault,
     * so neither gets the vague message — except a missing row, which is
     * exactly the case where saying nothing specific is right.</p>
     *
     * <p>Students have no {@code is_active} column of their own, only
     * {@code status}, so {@code users.is_active} (already checked) is their
     * only gate. Admins likewise have no profile row of their own any more —
     * {@code dbo.admins} was retired in Phase 18 — so {@code users.is_active}
     * is their only gate too.</p>
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
                // users.is_active was already checked in login(); nothing further to gate on.
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
     * <p>Applies the same {@link ValidationUtil#isStrongPassword} policy for
     * every role — Student, Instructor and Admin all go through this one
     * method, so there is nowhere for a role-specific rule to drift in.</p>
     *
     * @throws ServiceException if the current password is wrong, the new one
     *                          does not meet the password policy, or it
     *                          repeats the current password
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
        if (!ValidationUtil.isStrongPassword(newPassword)) {
            throw new ValidationException("Password must contain at least 8 characters, including an "
                    + "uppercase letter, lowercase letter, number, and special character.");
        }
        if (currentPassword.equals(newPassword)) {
            throw new ValidationException("New password must be different from the current password.");
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
