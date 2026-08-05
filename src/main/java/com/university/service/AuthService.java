package com.university.service;

import com.university.dao.AdminDAO;
import com.university.dao.InstructorDAO;
import com.university.dao.StudentDAO;
import com.university.dao.UserDAO;
import com.university.enums.UserRole;
import com.university.model.Admin;
import com.university.model.Instructor;
import com.university.model.Student;
import com.university.model.User;

import java.time.LocalDateTime;

/**
 * Signing in, signing out and changing a password.
 *
 * <p>A failed sign-in always gives the same message whether the role-specific
 * ID was unknown or the password was wrong. Saying which one was at fault
 * would tell a stranger that an ID exists, which is worth more to them than
 * to anybody honest.</p>
 *
 * <p>Sign-in is scoped to one role per call: the caller (see
 * {@code LoginController}, which tries each role in turn) passes the role to
 * check, and the ID typed is looked up only inside that role's table
 * ({@code dbo.admins}/{@code dbo.instructors}/{@code dbo.students}), each of
 * which has its own independent {@code IDENTITY(1,1)} sequence. A Student ID
 * 1 and an Instructor ID 1 are unrelated accounts, and one can never sign in
 * through the other's button.</p>
 */
public class AuthService {

    /** Deliberately vague, for the reason above. Also read by {@code LoginController} to tell a
     *  "wrong role, try the next one" failure apart from any other sign-in failure. */
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
     * @param role       the role to check the ID against
     * @param idText     the role-specific ID as typed on the sign-in screen
     *                    ({@code admin_id}, {@code instructor_id} or
     *                    {@code student_id} — never {@code users.user_id})
     * @return the open session, also reachable through {@link Session#current()}
     * @throws ServiceException if the details are wrong or the account is disabled
     */
    public Session login(UserRole role, String idText, String password) {
        ValidationException.requireText(idText, role.getLabel() + " ID");
        ValidationException.requireText(password, "Password");

        int roleId;
        try {
            roleId = Integer.parseInt(idText.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(SIGN_IN_FAILED);
        }
        ValidationException.requireId(roleId, role.getLabel() + " ID");

        Integer userId = resolveUserId(role, roleId);
        if (userId == null) {
            throw new ServiceException(SIGN_IN_FAILED);
        }

        User user = userDao.findById(userId).orElseThrow(() -> new ServiceException(SIGN_IN_FAILED));
        if (user.getRole() != role) {
            // Defence in depth: resolveUserId already searched only the chosen
            // role's table, so this should be unreachable outside a data bug.
            throw new ServiceException(SIGN_IN_FAILED);
        }
        if (!PasswordHasher.verify(password, user.getPasswordHash())) {
            throw new ServiceException(SIGN_IN_FAILED);
        }
        if (!user.isActive()) {
            throw new ServiceException("This account has been deactivated. "
                    + "Please contact the registrar.");
        }

        Student student = role == UserRole.STUDENT ? studentDao.findById(roleId).orElse(null) : null;
        Instructor instructor = role == UserRole.INSTRUCTOR ? instructorDao.findById(roleId).orElse(null) : null;

        LocalDateTime now = LocalDateTime.now();
        userDao.touchLastLogin(user.getUserId(), now);
        user.setLastLogin(now);

        Session.begin(user, student, instructor);
        return Session.current();
    }

    /**
     * Looks up the {@code user_id} behind a role-specific ID, searching only
     * the table for {@code role}. Returns null when the ID does not exist in
     * that role, or (for admin/instructor) when the role record itself is
     * inactive — either way the caller reports the same generic failure.
     *
     * <p>Students have no {@code is_active} column of their own — only
     * {@code status} — so there is no extra gate here for them beyond the
     * {@code users.is_active} check already applied in {@link #login}.</p>
     */
    private Integer resolveUserId(UserRole role, int roleId) {
        return switch (role) {
            case ADMIN -> adminDao.findById(roleId)
                    .filter(Admin::isActive)
                    .map(Admin::getUserId)
                    .orElse(null);
            case INSTRUCTOR -> instructorDao.findById(roleId)
                    .filter(Instructor::isActive)
                    .map(Instructor::getUserId)
                    .orElse(null);
            case STUDENT -> studentDao.findById(roleId)
                    .map(Student::getUserId)
                    .orElse(null);
        };
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
