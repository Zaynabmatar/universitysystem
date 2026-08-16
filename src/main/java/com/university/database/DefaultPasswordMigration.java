package com.university.database;

import com.university.enums.UserRole;
import com.university.service.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks — and on request repairs — the rule that every account's password is
 * BCrypt of its role's default: {@code <user_id>@iuL} for STUDENT,
 * {@code <user_id>@ADm} for ADMIN, {@code <user_id>@iNS} for INSTRUCTOR.
 *
 * <p>Reads {@code dbo.users} itself rather than going through the three role
 * data access objects, which is the one thing {@link RolePasswordMigration}
 * cannot do: an account whose {@code students} / {@code instructors} /
 * {@code admins} row is missing never appears in those lists, so its hash was
 * never checked and never fixed. Here the account table is the list, and every
 * row in it is covered whatever its role.</p>
 *
 * <p>No user id is written into this class. Each row's expected password is
 * derived from the id and role that row already carries, so a database
 * restored with a different set of ids needs no edit here.</p>
 *
 * <p>Nothing is ever stored in plain text: the column receives
 * {@link PasswordHasher#hashDefaultPassword(int, UserRole)} and the plain form
 * exists only as the argument to BCrypt.</p>
 *
 * <pre>
 *   (no arguments)   report only — reads, changes nothing
 *   --apply          rewrite the hash of every account that does not match
 * </pre>
 *
 * <p>{@code --apply} is safe to re-run: an account already holding the right
 * hash is skipped, so a second run reports zero repairs. It is also safe to
 * interrupt — the rewrite runs in one transaction and either commits whole or
 * rolls back whole.</p>
 *
 * <p><b>STUDENT accounts are never rewritten by {@code --apply}</b>, even one
 * whose hash does not match {@code <user_id>@iuL}. The report still counts a
 * mismatched STUDENT row so it is visible, but only ADMIN and INSTRUCTOR rows
 * are ever passed to {@link #repair}.</p>
 */
public final class DefaultPasswordMigration {

    /** One account as the audit sees it. */
    private record Account(int userId, String role, String passwordHash) {

        /** Does the stored hash accept this account's mandatory password? */
        boolean matchesDefault() {
            UserRole roleEnum = UserRole.fromDb(role);
            return roleEnum != null
                    && PasswordHasher.verify(PasswordHasher.defaultPasswordFor(userId, roleEnum), passwordHash);
        }
    }

    private DefaultPasswordMigration() {
    }

    public static void main(String[] args) {
        boolean apply = args.length > 0 && "--apply".equalsIgnoreCase(args[0]);

        try (Connection connection = DBConnection.getConnection()) {
            System.out.println(DBConnection.describeConnected(connection));
            System.out.println();

            List<Account> accounts = readAccounts(connection);
            List<Account> broken = new ArrayList<>();
            Map<String, int[]> perRole = new LinkedHashMap<>();

            for (Account account : accounts) {
                int[] tally = perRole.computeIfAbsent(account.role(), r -> new int[2]);
                if (account.matchesDefault()) {
                    tally[0]++;
                } else {
                    tally[1]++;
                    broken.add(account);
                }
            }

            report(accounts.size(), perRole, broken);

            if (broken.isEmpty()) {
                System.out.println("\nEvery account already accepts its role's default password. Nothing to do.");
                return;
            }

            List<Account> repairable = new ArrayList<>();
            int studentsSkipped = 0;
            for (Account account : broken) {
                if ("STUDENT".equalsIgnoreCase(account.role())) {
                    studentsSkipped++;
                } else {
                    repairable.add(account);
                }
            }
            if (studentsSkipped > 0) {
                System.out.println("\n" + studentsSkipped + " STUDENT account(s) do not match <user_id>@iuL"
                        + " but are never rewritten by this tool and will be left exactly as they are.");
            }
            if (repairable.isEmpty()) {
                System.out.println("\nNo ADMIN or INSTRUCTOR accounts need repair. Nothing to do.");
                return;
            }
            if (!apply) {
                System.out.println("\nReport only - nothing was written."
                        + "\nRe-run with --apply to rehash the " + repairable.size()
                        + " ADMIN/INSTRUCTOR account(s) counted above.");
                return;
            }
            repair(connection, repairable);

        } catch (SQLException e) {
            System.out.println("The audit could not run — full stack trace follows:");
            e.printStackTrace(System.out);
        } finally {
            DBConnection.shutdown();
        }
    }

    /**
     * Every account, in id order.
     *
     * <p>Only the three columns the audit judges are read; nothing else about
     * a row is loaded, so a schema difference elsewhere cannot stop the check.</p>
     */
    private static List<Account> readAccounts(Connection connection) throws SQLException {
        List<Account> accounts = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT user_id, role, password_hash FROM dbo.users ORDER BY user_id")) {
            while (rs.next()) {
                accounts.add(new Account(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
        }
        return accounts;
    }

    private static void report(int total, Map<String, int[]> perRole, List<Account> broken) {
        System.out.println("Accounts in dbo.users: " + total);
        System.out.printf("%-14s %10s %10s%n", "role", "correct", "wrong");
        perRole.forEach((role, tally) ->
                System.out.printf("%-14s %10d %10d%n", role, tally[0], tally[1]));

        if (broken.isEmpty()) {
            return;
        }
        System.out.println("\nAccounts whose hash does not accept their role's default password"
                + " (first " + Math.min(broken.size(), SAMPLE_SIZE) + " of " + broken.size() + "):");
        for (Account account : broken.subList(0, Math.min(broken.size(), SAMPLE_SIZE))) {
            // The hash is shown truncated, and the password never at all.
            String hash = account.passwordHash() == null ? "(null)"
                    : account.passwordHash().substring(0, Math.min(7, account.passwordHash().length())) + "...";
            System.out.println("  user_id " + account.userId()
                    + "  role " + account.role() + "  stored hash " + hash);
        }
        if (broken.size() > SAMPLE_SIZE) {
            System.out.println("  ... and " + (broken.size() - SAMPLE_SIZE) + " more");
        }
    }

    /** How many failing accounts to name before summarising the rest. */
    private static final int SAMPLE_SIZE = 15;

    /**
     * Rewrites the listed accounts' hashes in one transaction.
     *
     * <p>Accounts that already matched are not in the list and are not
     * touched, so a person who has changed their password away from the
     * default does not silently get it back — only accounts that can log in
     * with nothing at all are repaired.</p>
     */
    private static void repair(Connection connection, List<Account> broken) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE dbo.users SET password_hash = ? WHERE user_id = ?")) {

            for (Account account : broken) {
                UserRole roleEnum = UserRole.fromDb(account.role());
                statement.setString(1, PasswordHasher.hashDefaultPassword(account.userId(), roleEnum));
                statement.setInt(2, account.userId());
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            connection.commit();

            int rows = 0;
            for (int result : results) {
                rows += Math.max(result, 0);
            }
            System.out.println("\nRehashed " + rows + " account(s) to BCrypt of their role's default password.");

        } catch (SQLException e) {
            connection.rollback();
            System.out.println("\nRolled back - nothing was changed. Full stack trace follows:");
            e.printStackTrace(System.out);
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }
}
