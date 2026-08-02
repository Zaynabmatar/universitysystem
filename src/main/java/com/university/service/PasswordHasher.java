package com.university.service;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Hashes and checks passwords with BCrypt.
 *
 * <p>The database column holds the hash and nothing else. A plain password
 * exists only inside the sign-in call and is never stored, logged or put in
 * an exception message.</p>
 *
 * <p><strong>The sample data will not sign in.</strong> The hashes shipped in
 * {@code universitymanagmentDB.sql} are placeholders in BCrypt shape that
 * match no password at all, which the file says at line 1017. Run
 * {@link #main} to print real hashes and paste them into the {@code users}
 * table yourself.</p>
 */
public final class PasswordHasher {

    /**
     * BCrypt work factor. Each step up doubles the time taken. Twelve is the
     * usual choice: slow enough to be a nuisance to an attacker, fast enough
     * that signing in still feels immediate.
     */
    private static final int COST = 12;

    /** The shortest password the program will accept. */
    public static final int MINIMUM_LENGTH = 8;

    private PasswordHasher() {
    }

    /**
     * Hashes a password ready for storage.
     *
     * @throws ValidationException if the password is missing or too short
     */
    public static String hash(String plainPassword) {
        ValidationException.requireText(plainPassword, "Password");
        if (plainPassword.length() < MINIMUM_LENGTH) {
            throw new ValidationException(
                    "Password must be at least " + MINIMUM_LENGTH + " characters.");
        }
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(COST));
    }

    /**
     * Checks a password against a stored hash.
     *
     * <p>Returns false rather than throwing when the stored value is not a
     * real BCrypt hash. That is exactly the case for the placeholder hashes in
     * the sample data, and a failed sign-in is the right answer for them.</p>
     *
     * @param plainPassword what was typed, may be null
     * @param storedHash    the value from {@code users.password_hash}
     * @return true only when the password matches
     */
    public static boolean verify(String plainPassword, String storedHash) {
        if (plainPassword == null || plainPassword.isEmpty()
                || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // Not a valid BCrypt hash, so nothing can match it.
            return false;
        }
    }

    /**
     * Prints a hash for each password given on the command line.
     *
     * <p>Nothing is written to the database. Copy the output into an UPDATE
     * statement yourself.</p>
     */
    public static void main(String[] args) {
        String[] passwords = args.length > 0 ? args : new String[]{"ChangeMe123"};
        System.out.println("-- Paste the hash into dbo.users.password_hash --");
        for (String password : passwords) {
            System.out.println(password + "  ->  " + hash(password));
        }
    }
}
