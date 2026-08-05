package com.university.service;

import com.university.dao.AdminDAO;
import com.university.dao.InstructorDAO;
import com.university.dao.StudentDAO;
import com.university.dao.UserDAO;
import com.university.database.DBConnection;
import com.university.enums.UserRole;
import com.university.model.Admin;
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
 * <p>The number typed on the sign-in screen is the one the signer-in actually
 * knows: their Student ID, Instructor ID or Admin ID — which is
 * {@code students.student_id}, {@code instructors.instructor_id} or
 * {@code admins.admin_id}, exactly as the field's own prompt says. It is
 * <em>not</em> {@code users.user_id}. Those two numbers are separate IDENTITY
 * sequences and they diverge almost immediately: instructor 1 is user 2,
 * student 3 is user 55, admin 2 is user 405. Reading the typed number as a
 * user_id therefore looked up a different person's account — the reason a
 * valid ID and a valid password were still refused.</p>
 *
 * <p>So the role picked on the role-selection screen does two jobs. It chooses
 * which table the typed number is looked up in, and it remains the door the
 * account came in through — an account may only come in through its own. An
 * unknown ID and a wrong password are told the same vague thing, because which
 * IDs exist is no more a stranger's business than which door they belong to.</p>
 */
public class AuthService {

    /** Deliberately vague, for the reason above. */
    public static final String SIGN_IN_FAILED = "Incorrect ID or password.";

    private final UserDAO userDao = new UserDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final InstructorDAO instructorDao = new InstructorDAO();
    private final AdminDAO adminDao = new AdminDAO();

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
     * @param selectedRole the role picked on the role-selection screen. It
     *                     selects the table {@code idText} is looked up in, and
     *                     the account must belong to it. {@code null} means no
     *                     role was picked, in which case the number can only be
     *                     read as {@code users.user_id} — nothing in the
     *                     application passes null, since every route to the
     *                     sign-in screen goes through role selection first.
     * @param idText       the ID as typed on the sign-in screen: a student's
     *                     Student ID, an instructor's Instructor ID, an admin's
     *                     Admin ID — the key of the role's own table, not
     *                     {@code users.user_id}
     * @return the open session, also reachable through {@link Session#current()}
     * @throws ServiceException if the details are wrong or the account is disabled
     */
    public Session login(UserRole selectedRole, String idText, String password) {
        ValidationException.requireText(idText, "User ID");
        ValidationException.requireText(password, "Password");

        int typedId;
        try {
            typedId = Integer.parseInt(idText.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(SIGN_IN_FAILED);
        }
        if (typedId <= 0) {
            throw new ServiceException(SIGN_IN_FAILED);
        }

        if (TRACE) {
            trace("---------------------------------------------");
            trace(DBConnection.describeConnected());
            trace("typed " + (selectedRole == null ? "ID" : selectedRole.getLabel() + " ID")
                    + " = " + typedId);
        }

        int userId = resolveUserId(selectedRole, typedId);
        trace("resolved user_id = " + userId);

        User user = userDao.findById(userId).orElseThrow(() -> {
            trace("no dbo.users row for user_id " + userId);
            return new ServiceException(SIGN_IN_FAILED);
        });
        trace("role on account  = " + user.getRole() + ", is_active = " + user.isActive());

        if (!PasswordHasher.verify(password, user.getPasswordHash())) {
            trace("refused: password did not verify against the stored hash");
            throw new ServiceException(SIGN_IN_FAILED);
        }
        trace("password         = verified");
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

        UserRole role = user.getRole();
        Student student = role == UserRole.STUDENT ? studentDao.findByUserId(userId).orElse(null) : null;
        Instructor instructor = role == UserRole.INSTRUCTOR
                ? instructorDao.findByUserId(userId).orElse(null) : null;

        requireRoleRecordUsable(role, student, instructor, userId);
        trace("signed in        = OK");

        LocalDateTime now = LocalDateTime.now();
        userDao.touchLastLogin(user.getUserId(), now);
        user.setLastLogin(now);

        Session.begin(user, student, instructor);
        return Session.current();
    }

    /**
     * Turns the number typed on the sign-in screen into a {@code users.user_id}.
     *
     * <p>The field's prompt is "Student ID", "Instructor ID" or "Admin ID", and
     * that is precisely what it receives: the key of {@code dbo.students},
     * {@code dbo.instructors} or {@code dbo.admins}. Each of those is its own
     * IDENTITY sequence, independent of the one behind {@code users.user_id},
     * so the two numbers agree only by accident — in the shipped data for
     * exactly one account out of 682. The lookup therefore has to go through
     * the role's own table; treating the typed number as a user_id directly is
     * what silently resolved a valid ID to somebody else's account.</p>
     *
     * <p>Scoping the lookup by role also means an Admin ID and a Student ID may
     * validly be the same number without either being able to sign in through
     * the other's button.</p>
     *
     * @param selectedRole which table to look in; {@code null} only when no role
     *                     was picked, where the number can only be a user_id
     * @return the {@code users.user_id} behind the typed ID
     * @throws ServiceException with the vague message when no such ID exists,
     *                          so that an unknown ID is indistinguishable from
     *                          a wrong password
     */
    private int resolveUserId(UserRole selectedRole, int typedId) {
        if (selectedRole == null) {
            return typedId;
        }
        Optional<Integer> userId = switch (selectedRole) {
            case STUDENT -> studentDao.findById(typedId).map(Student::getUserId);
            case INSTRUCTOR -> instructorDao.findById(typedId).map(Instructor::getUserId);
            case ADMIN -> adminDao.findById(typedId).map(Admin::getUserId);
        };
        return userId.orElseThrow(() -> {
            trace("refused: no " + selectedRole.getLabel() + " has ID " + typedId);
            return new ServiceException(SIGN_IN_FAILED);
        });
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
