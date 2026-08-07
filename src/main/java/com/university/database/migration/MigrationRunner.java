package com.university.database.migration;

import com.university.database.DBConnection;
import com.university.database.migration.MigrationCatalog.Migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies pending database migrations at startup.
 *
 * <p>Every schema change is a numbered {@code .sql} file in
 * {@code src/main/java/com/university/database/unidatabase/migrations}, so it
 * travels through Git with the code that needs it. On startup this class
 * compares that directory against {@code dbo.schema_migrations} and runs only
 * what is missing, in filename order, each file in its own transaction. A file
 * that fails is rolled back, is not recorded, and stops the run — later
 * migrations are never attempted on a database in an unknown state.</p>
 *
 * <p>It never creates or drops a database, never deletes data, and never
 * re-runs anything already recorded. It uses {@link DBConnection}, so there is
 * still exactly one place that knows how to reach SQL Server.</p>
 *
 * <h2>Legacy scripts</h2>
 * <p>The three {@code phaseNN_*.sql} files predate this runner. They were
 * applied by hand, and one of them (phase 11) renames columns and drops
 * constraints — running it a second time would fail, and against a database
 * where it half-applied it could do real damage. They are therefore
 * <em>baselined</em>: recorded as applied without being executed. See
 * {@code migrations/README.md}.</p>
 */
public final class MigrationRunner {

    /**
     * Scripts that are recorded as applied but never executed by this runner.
     *
     * <p>These were applied by hand before the runner existed. They are not
     * safe to re-run — phase 11 renames columns and drops constraints — and the
     * live database already has their effects.</p>
     */
    private static final Set<String> BASELINE = Set.of(
            "phase11_grades_scale.sql",
            "phase16_admins_and_role_login.sql",
            "phase17_user_id_as_the_only_identity.sql");

    /**
     * Guards the whole run, so two copies of the application starting at the
     * same moment against one database cannot both decide a migration is
     * pending. Session-scoped, released explicitly in a finally block.
     */
    private static final String LOCK_NAME = "universitysystem_schema_migrations";

    private static final int LOCK_TIMEOUT_MILLIS = 30_000;

    private MigrationRunner() {
    }

    /** What a run did, for the startup log and for the tests. */
    public record Result(List<String> applied, List<String> baselined, int alreadyApplied) {

        /** True when the database was already up to date. */
        public boolean upToDate() {
            return applied.isEmpty() && baselined.isEmpty();
        }

        public String summary() {
            if (upToDate()) {
                return "database schema is up to date (" + alreadyApplied + " migration(s) already applied)";
            }
            StringBuilder text = new StringBuilder();
            if (!baselined.isEmpty()) {
                text.append("baselined ").append(baselined.size())
                        .append(" legacy migration(s) without running them: ")
                        .append(String.join(", ", baselined));
            }
            if (!applied.isEmpty()) {
                if (text.length() > 0) {
                    text.append("; ");
                }
                text.append("applied ").append(applied.size()).append(" new migration(s): ")
                        .append(String.join(", ", applied));
            }
            return text.toString();
        }
    }

    /**
     * Brings the configured database up to date.
     *
     * @return what was applied
     * @throws MigrationException if a migration fails; the failed one has been
     *         rolled back and no later one was attempted
     */
    public static Result run() {
        List<Migration> migrations = MigrationCatalog.discover();

        try (Connection connection = DBConnection.getConnection()) {
            // Autocommit for the bookkeeping around the migrations; each
            // migration itself turns it off for the duration of its own
            // transaction and turns it back on afterwards.
            connection.setAutoCommit(true);

            boolean locked = acquireLock(connection);
            try {
                createSchemaMigrationsTable(connection);
                Set<String> applied = readAppliedMigrations(connection);

                List<String> baselined = baseline(connection, migrations, applied);
                List<String> justApplied = applyPending(connection, migrations, applied);

                return new Result(justApplied, baselined, applied.size() - justApplied.size() - baselined.size());
            } finally {
                if (locked) {
                    releaseLock(connection);
                }
            }
        } catch (MigrationException e) {
            throw e;
        } catch (SQLException e) {
            throw new MigrationException(
                    "Could not apply database migrations against " + DBConnection.describeTarget()
                    + ": " + e.getMessage(), e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* the ledger                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Creates {@code dbo.schema_migrations} if it is not there.
     *
     * <p>This one table cannot itself be a migration file — something has to
     * exist before anything can be recorded as applied — so it is created here,
     * guarded, and never altered by the runner afterwards.</p>
     *
     * <p>{@code migration_name} is the primary key on purpose. "Never run the
     * same migration twice" is then enforced by the database rather than only
     * by the check in Java, so even two processes racing cannot both insert.</p>
     */
    private static void createSchemaMigrationsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    IF OBJECT_ID('dbo.schema_migrations', 'U') IS NULL
                    BEGIN
                        CREATE TABLE dbo.schema_migrations
                        (
                            migration_name NVARCHAR(260)  NOT NULL,
                            applied_at     DATETIME2(0)   NOT NULL
                                CONSTRAINT DF_schema_migrations_applied_at DEFAULT (SYSDATETIME()),

                            CONSTRAINT PK_schema_migrations PRIMARY KEY (migration_name)
                        );
                    END
                    """);
        }
    }

    private static Set<String> readAppliedMigrations(Connection connection) throws SQLException {
        Set<String> applied = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT migration_name FROM dbo.schema_migrations")) {
            while (rs.next()) {
                applied.add(rs.getString(1));
            }
        }
        return applied;
    }

    private static void record(Connection connection, String migrationName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO dbo.schema_migrations (migration_name, applied_at) "
                + "VALUES (?, SYSDATETIME())")) {
            statement.setString(1, migrationName);
            statement.executeUpdate();
        }
    }

    /* ------------------------------------------------------------------ */
    /* baselining and applying                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Records the legacy scripts as applied without executing them.
     *
     * <p>Only scripts that are actually present in the directory are recorded,
     * so removing one from the repository does not leave a phantom row.</p>
     */
    private static List<String> baseline(Connection connection, List<Migration> migrations,
                                         Set<String> applied) throws SQLException {
        List<String> baselined = new ArrayList<>();
        for (Migration migration : migrations) {
            if (BASELINE.contains(migration.name()) && !applied.contains(migration.name())) {
                record(connection, migration.name());
                applied.add(migration.name());
                baselined.add(migration.name());
            }
        }
        return baselined;
    }

    private static List<String> applyPending(Connection connection, List<Migration> migrations,
                                             Set<String> applied) {
        List<String> justApplied = new ArrayList<>();
        for (Migration migration : migrations) {
            if (applied.contains(migration.name())) {
                continue;
            }
            apply(connection, migration);
            applied.add(migration.name());
            justApplied.add(migration.name());
        }
        return justApplied;
    }

    /**
     * Runs one migration and records it, both inside one transaction.
     *
     * <p>The record is written on the same connection, in the same transaction,
     * as the change itself. That is what makes "applied but not recorded"
     * impossible: either both are committed or neither is.</p>
     */
    private static void apply(Connection connection, Migration migration) {
        System.out.println("[migration] applying " + migration.name());

        List<String> batches = SqlBatchSplitter.split(migration.sql());
        if (batches.isEmpty()) {
            throw new MigrationException(migration.name(),
                    "Migration " + migration.name() + " contains no SQL statements.", null);
        }

        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new MigrationException(migration.name(),
                    "Could not start a transaction for migration " + migration.name(), e);
        }

        try {
            try (Statement statement = connection.createStatement()) {
                for (String batch : batches) {
                    if (SqlBatchSplitter.isDatabaseSwitch(batch)) {
                        // USE is illegal inside a transaction and redundant here:
                        // the connection is already on the configured database.
                        continue;
                    }
                    statement.execute(batch);
                }
            }
            record(connection, migration.name());
            connection.commit();
            System.out.println("[migration] applied  " + migration.name());
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw new MigrationException(migration.name(),
                    "Migration " + migration.name() + " failed and was rolled back. "
                    + "No later migration was run. SQL Server said: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollbackQuietly(connection);
            throw new MigrationException(migration.name(),
                    "Migration " + migration.name() + " failed and was rolled back. "
                    + "No later migration was run.", e);
        } finally {
            restoreAutoCommit(connection);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            // The original failure is the one worth reporting; losing it behind
            // a secondary "could not roll back" would hide what actually broke.
            System.err.println("[migration] rollback failed: " + rollbackFailure.getMessage());
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            System.err.println("[migration] could not restore autocommit: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* the advisory lock                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Takes a session-scoped application lock so only one process migrates at a
     * time.
     *
     * <p>Best effort: if the lock cannot be taken the run still goes ahead. The
     * primary key on {@code migration_name} is the real guarantee — the loser
     * of a race fails its INSERT and rolls back rather than applying anything
     * twice — so a lock that is unavailable must not stop the application from
     * starting.</p>
     *
     * @return true if the lock was taken and must be released
     */
    private static boolean acquireLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DECLARE @result INT; "
                + "EXEC @result = sp_getapplock @Resource = ?, @LockMode = 'Exclusive', "
                + "@LockOwner = 'Session', @LockTimeout = ?; "
                + "SELECT @result")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_MILLIS);
            try (ResultSet rs = statement.executeQuery()) {
                // 0 = granted immediately, 1 = granted after waiting.
                return rs.next() && rs.getInt(1) >= 0;
            }
        } catch (SQLException e) {
            System.err.println("[migration] could not take the migration lock, continuing anyway: "
                    + e.getMessage());
            return false;
        }
    }

    private static void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "EXEC sp_releaseapplock @Resource = ?, @LockOwner = 'Session'")) {
            statement.setString(1, LOCK_NAME);
            statement.execute();
        } catch (SQLException e) {
            System.err.println("[migration] could not release the migration lock: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */

    /**
     * Runs the migrations from the command line, without starting JavaFX.
     *
     * <p>{@code mvn -q compile exec:java -Dexec.mainClass=com.university.database.migration.MigrationRunner}</p>
     */
    public static void main(String[] args) {
        try {
            System.out.println("[migration] scripts: " + MigrationCatalog.describeSource());
            Result result = run();
            System.out.println("[migration] " + result.summary());
        } catch (MigrationException e) {
            System.err.println("[migration] FAILED"
                    + (e.getMigrationName() != null ? " in " + e.getMigrationName() : ""));
            System.err.println("[migration] " + e.getMessage());
            e.printStackTrace();
            DBConnection.shutdown();
            System.exit(1);
        }
        DBConnection.shutdown();
    }
}
