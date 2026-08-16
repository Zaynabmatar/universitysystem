package com.university.service;

import com.university.dao.UserDAO;
import com.university.enums.UserRole;
import com.university.model.User;
import com.university.util.ValidationUtil;

import java.sql.Connection;
import java.text.Normalizer;
import java.util.Locale;

/**
 * The one place a login account is created, named, addressed and re-passworded.
 *
 * <p>Everything about an account's identity is decided here and nowhere else,
 * so that no screen can grow its own slightly different version of it:</p>
 *
 * <ul>
 *   <li><b>The id.</b> {@code dbo.users.user_id} is an {@code IDENTITY(1,1)}
 *       column; SQL Server assigns it and {@code OUTPUT INSERTED.user_id}
 *       hands it back. That value <em>is</em> the Student ID of a student and
 *       the Instructor ID of an instructor. Nothing counts rows, reads the
 *       last row, adds one to anything, or asks an administrator to type an
 *       id.</li>
 *   <li><b>The email.</b> Built from the person's name
 *       ({@code first initial . last name @ role domain}), lower-cased,
 *       stripped of accents and punctuation, and made unique by a numeric
 *       suffix when the obvious address is already taken. It is generated here
 *       but stored by the caller on {@code students.email} /
 *       {@code instructors.email}, the only two columns that hold one —
 *       {@code dbo.users} has no email column.</li>
 *   <li><b>The password.</b> The role's mandatory default (see
 *       {@link PasswordHasher#defaultPasswordFor(int, UserRole)}), hashed
 *       with BCrypt the moment the id exists. Only the hash reaches the
 *       database. The plain text is returned once, to be shown on screen,
 *       and is never logged, printed or stored.</li>
 * </ul>
 *
 * <p>Every method that writes takes the caller's {@link Connection}, so account
 * creation is part of the same transaction as the student or instructor row it
 * belongs to. If either half fails, neither is kept.</p>
 */
public class AccountService {

    /** Where a student's university address lives. */
    public static final String STUDENT_EMAIL_DOMAIN = "student.university.edu.lb";

    /** Where an instructor's university address lives. */
    public static final String INSTRUCTOR_EMAIL_DOMAIN = "instructor.university.edu.lb";

    /**
     * The address every instructor had before the role domains were split.
     *
     * <p>Still accepted when an administrator edits an existing instructor, so
     * that fifty people who have used {@code name@university.edu.lb} for years
     * are not forced to change address to correct a phone number. New accounts
     * are always given {@link #INSTRUCTOR_EMAIL_DOMAIN}.</p>
     */
    public static final String LEGACY_INSTRUCTOR_EMAIL_DOMAIN = "university.edu.lb";

    /**
     * The longest an address may be: {@code students.email} and
     * {@code instructors.email} are both NVARCHAR(100).
     */
    private static final int EMAIL_MAX_LENGTH = 100;

    /** Gives up rather than looping forever if a hundred variants are all taken. */
    private static final int MAX_EMAIL_ATTEMPTS = 100;

    private final UserDAO userDao = new UserDAO();

    /**
     * What was generated for a brand-new account.
     *
     * @param userId            the id SQL Server assigned — the Student ID or Instructor ID
     * @param email             the generated university address
     * @param temporaryPassword the plain-text role default, to be shown
     *                          on screen once and then forgotten
     */
    public record NewAccount(int userId, String email, String temporaryPassword) {
    }

    // ================================================================ create

    /**
     * Creates the {@code dbo.users} row for a new student or instructor.
     *
     * <p>Order matters and is not negotiable: the row goes in, SQL Server
     * returns the id, and only then can the password — which is built from
     * that id — be hashed and stored.</p>
     *
     * <p>The generated address is returned rather than written here: it belongs
     * on the {@code students} or {@code instructors} row, which is the caller's
     * to insert, inside this same transaction.</p>
     *
     * @param connection the caller's open transaction
     * @param role       STUDENT or INSTRUCTOR
     * @return the generated id, address and one-time password
     */
    public NewAccount createAccount(Connection connection, UserRole role,
                                    String firstName, String lastName) {
        String email = generateEmail(connection, role, firstName, lastName);

        User account = new User();
        account.setUsername(uniqueUsernameFor(connection, email));
        account.setRole(role);
        account.setActive(true);

        // SQL Server assigns user_id here; nothing below ever second-guesses it.
        int userId = userDao.insert(connection, account);

        userDao.finalizePassword(connection, userId, role);

        return new NewAccount(userId, email, PasswordHasher.defaultPasswordFor(userId, role));
    }

    /**
     * The value written to the retired {@code student_number} /
     * {@code employee_number} columns of a newly created row.
     *
     * <p>Those columns are NOT NULL and UNIQUE and cannot simply be skipped,
     * but they are no longer an identity: nothing displays them, searches them
     * or asks anybody to choose one. Deriving them from the real id keeps them
     * unique for free and makes it obvious, to anyone reading the table
     * directly, that they are a leftover rather than a second id.</p>
     */
    public String legacyNumberFor(int userId) {
        return "U" + userId;
    }

    // ================================================================= email

    /** The domain a role's addresses belong to. */
    public String domainFor(UserRole role) {
        return role == UserRole.INSTRUCTOR ? INSTRUCTOR_EMAIL_DOMAIN : STUDENT_EMAIL_DOMAIN;
    }

    /** Trimmed and lower-cased, the only form ever compared or stored. */
    public String normalizeEmail(String rawEmail) {
        return rawEmail == null ? null : rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Builds the university address for a new account and makes sure it is free.
     *
     * <p>{@code Zaynab Matar} becomes {@code z.matar@student.university.edu.lb}.
     * If that is taken the address becomes {@code z.matar2@…}, then
     * {@code z.matar3@…}, and so on — the suffix goes before the {@code @},
     * never after it.</p>
     */
    public String generateEmail(Connection connection, UserRole role,
                                String firstName, String lastName) {
        String base = localPartFor(firstName, lastName);
        String domain = domainFor(role);

        for (int attempt = 1; attempt <= MAX_EMAIL_ATTEMPTS; attempt++) {
            // The first candidate carries no number at all: z.matar, then z.matar2.
            String candidate = base + (attempt == 1 ? "" : Integer.toString(attempt)) + "@" + domain;
            if (candidate.length() <= EMAIL_MAX_LENGTH
                    && !userDao.emailExists(connection, candidate, null)) {
                return candidate;
            }
        }
        throw new ServiceException("Could not build a free university email address for "
                + firstName + " " + lastName + ". Please add one by hand after saving.");
    }

    /**
     * Checks an address an administrator typed by hand, before it is saved.
     *
     * @param excludeUserId the account being edited — it is allowed to keep
     *                      the address it already has
     * @throws ValidationException with a message meant for the administrator
     */
    public String validateEditedEmail(Connection connection, String rawEmail, UserRole role,
                                      Integer excludeUserId) {
        String email = normalizeEmail(rawEmail);

        if (ValidationUtil.isBlank(email)) {
            throw new ValidationException("A university email address is required.");
        }
        if (email.length() > EMAIL_MAX_LENGTH) {
            throw new ValidationException(
                    "The email address must be " + EMAIL_MAX_LENGTH + " characters or fewer.");
        }
        if (!ValidationUtil.isEmail(email)) {
            throw new ValidationException("Enter a valid email address, e.g. "
                    + "z.matar@" + domainFor(role) + ".");
        }
        if (!isOfficialDomain(email, role)) {
            throw new ValidationException("A " + role.getLabel().toLowerCase(Locale.ROOT)
                    + " email address must end in @" + domainFor(role) + ".");
        }
        if (userDao.emailExists(connection, email, excludeUserId)) {
            throw new ValidationException(
                    "This university email is already assigned to another account.");
        }
        return email;
    }

    /**
     * Checks an ADMIN's own email address before it is saved.
     *
     * <p>Mirrors {@link #validateEditedEmail} but without the student/
     * instructor domain-suffix rule — an ADMIN account has no role domain of
     * its own, only {@code users.email}.</p>
     *
     * @param excludeUserId the admin's own user id, so keeping the address
     *                      it already has is never reported as a duplicate
     * @throws ValidationException with a message meant for the administrator
     */
    public String validateAdminEmail(String rawEmail, int excludeUserId) {
        String email = normalizeEmail(rawEmail);

        if (ValidationUtil.isBlank(email)) {
            throw new ValidationException("An email address is required.");
        }
        if (email.length() > EMAIL_MAX_LENGTH) {
            throw new ValidationException(
                    "The email address must be " + EMAIL_MAX_LENGTH + " characters or fewer.");
        }
        if (!ValidationUtil.isEmail(email)) {
            throw new ValidationException("Enter a valid email address.");
        }
        if (userDao.emailExistsAmongAdmins(email, excludeUserId)) {
            throw new ValidationException(
                    "This email is already assigned to another account.");
        }
        return email;
    }

    /**
     * True when the address sits on the domain its role is supposed to use.
     *
     * <p>Instructors are also allowed the pre-split {@code @university.edu.lb},
     * for the reason given on {@link #LEGACY_INSTRUCTOR_EMAIL_DOMAIN}.</p>
     */
    public boolean isOfficialDomain(String email, UserRole role) {
        if (email == null) {
            return false;
        }
        String lower = email.toLowerCase(Locale.ROOT);
        if (lower.endsWith("@" + domainFor(role))) {
            return true;
        }
        return role == UserRole.INSTRUCTOR
                && lower.endsWith("@" + LEGACY_INSTRUCTOR_EMAIL_DOMAIN);
    }

    // ============================================================== password

    /**
     * Puts an account's password back to its role's mandatory default
     * ({@code <user_id>@iuL} for STUDENT, {@code <user_id>@ADm} for ADMIN,
     * {@code <user_id>@iNS} for INSTRUCTOR).
     *
     * <p>The account keeps its id, its address and its role — only the hash in
     * {@code password_hash} changes.</p>
     *
     * @return the plain-text password, to show the administrator once
     */
    public String resetPasswordToDefault(int userId, UserRole role) {
        ValidationException.requireId(userId, "User");
        userDao.updatePasswordHash(userId, PasswordHasher.hashDefaultPassword(userId, role));
        return PasswordHasher.defaultPasswordFor(userId, role);
    }

    // =============================================================== helpers

    /**
     * {@code Zaynab}, {@code Matar} → {@code z.matar}.
     *
     * <p>Accents are folded ({@code Chaaya}, {@code Cháaya} → {@code chaaya}),
     * spaces and punctuation are dropped ({@code Abou Rjeily} →
     * {@code abourjeily}), and anything that is not a letter or a digit goes.
     * A name written entirely in characters that survive none of that leaves
     * an empty part, and {@code user} stands in so an address can still be
     * built.</p>
     */
    private String localPartFor(String firstName, String lastName) {
        String initial = simplify(firstName);
        String surname = simplify(lastName);

        String prefix = initial.isEmpty() ? "" : initial.substring(0, 1);
        if (prefix.isEmpty() && surname.isEmpty()) {
            return "user";
        }
        if (surname.isEmpty()) {
            return prefix;
        }
        return prefix.isEmpty() ? surname : prefix + "." + surname;
    }

    /** Lower case, accents folded away, everything but letters and digits removed. */
    private String simplify(String text) {
        if (text == null) {
            return "";
        }
        String folded = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * A free value for the legacy {@code users.username} column.
     *
     * <p>Sign-in is by User ID now, so this is only a label — but the column is
     * still NOT NULL and UNIQUE. The address's local part is the friendliest
     * value available ({@code z.matar}); a student and an instructor can share
     * one, since their domains differ, so a number is appended when it is
     * already spoken for.</p>
     */
    private String uniqueUsernameFor(Connection connection, String email) {
        String base = email.substring(0, email.indexOf('@'));
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        for (int attempt = 1; attempt <= MAX_EMAIL_ATTEMPTS; attempt++) {
            String candidate = base + (attempt == 1 ? "" : Integer.toString(attempt));
            if (!userDao.usernameExists(connection, candidate)) {
                return candidate;
            }
        }
        throw new ServiceException("Could not build a free username for " + email + ".");
    }
}
