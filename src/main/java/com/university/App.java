package com.university;

import com.university.database.DBConnection;
import com.university.database.migration.MigrationException;
import com.university.database.migration.MigrationRunner;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.sql.SQLException;

/**
 * The JavaFX application.
 *
 * <p>NOTE: this class is never the {@code <mainClass>}. {@link Launcher#main}
 * starts it — see project_details.md Section 8, CRITICAL BUILD RULE.</p>
 */
public class App extends Application {

    /**
     * How hard startup tries before giving up.
     *
     * <p>Kept small on purpose. A server that is unreachable because it is
     * misconfigured — the wrong instance, TCP/IP switched off, a bad password —
     * fails identically on every attempt, so a long retry budget buys nothing
     * and costs the user a blank screen. With {@code LOGIN_TIMEOUT_SECONDS} per
     * attempt the whole sequence is bounded at roughly twenty seconds, and a
     * hard failure surfaces in about four.</p>
     */
    private static final int MAX_ATTEMPTS = 3;

    private static final long RETRY_DELAY_MILLIS = 2_000;

    @Override
    public void start(Stage primaryStage) {
        // 1. Theme. The spec asks for AtlantaFX; that artefact could not be
        //    downloaded here, so this is the documented fallback from
        //    phase-05/context/preflight.md §4. css/app.css carries the styling.
        setUserAgentStylesheet(STYLESHEET_MODENA);

        // 2. Section 13: even an unexpected bug shows a popup instead of the app vanishing.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            Platform.runLater(() -> AlertUtil.error("Unexpected problem",
                    "Something went wrong, but the application is still running. Please try that again."));
        });

        // 3. Check that SQL Server is reachable before building any screen —
        //    but NEVER on this thread. This method runs on the JavaFX
        //    Application Thread, the one and only thread that draws the window.
        //    Connecting (and worse, sleeping between retries) here means
        //    nothing can be painted until the very last attempt has finished,
        //    so a server that is not answering shows the user no window at all,
        //    no error, and no way to tell the application from a frozen one.
        //
        //    Nothing is showing yet, so the toolkit must be told not to shut
        //    itself down while the check runs; step 4 restores the normal
        //    "last window closed ends the app" behaviour.
        Platform.setImplicitExit(false);

        Thread probe = new Thread(() -> {
            Throwable connectionFailure = connectWithRetries();

            // 3b. Bring the schema up to date before anything queries it. Also
            //     off the Application Thread, for the same reason as the
            //     connection check, and only once the server has answered —
            //     migrating a database that cannot be reached would just
            //     produce a second, less clear version of the same error.
            MigrationException migrationFailure =
                    connectionFailure == null ? applyPendingMigrations() : null;

            Platform.runLater(() -> {
                Platform.setImplicitExit(true);
                if (connectionFailure != null) {
                    reportUnreachableDatabase(connectionFailure);
                    return;   // never fall through into a half-built login screen
                }
                if (migrationFailure != null) {
                    reportFailedMigration(migrationFailure);
                    return;   // a half-migrated schema must not reach the login screen
                }
                showFirstScreen(primaryStage);
            });
        }, "database-startup-check");
        probe.setDaemon(true);   // must never keep the JVM alive after Platform.exit()
        probe.start();
    }

    /**
     * Tries to reach the database, off the JavaFX Application Thread.
     *
     * @return null once a connection succeeded, otherwise the last failure
     */
    private Throwable connectWithRetries() {
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                DBConnection.verifyConnectivity();
                return null;
            } catch (SQLException e) {
                // Retried with a short backoff: SQL Server can still be
                // finishing crash recovery for a moment after the Windows
                // service reports "Running", so one early failure is not proof
                // that the database is unusable.
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return e;
                    }
                }
            } catch (Throwable e) {
                // Not a connection problem but a broken configuration — a
                // missing credentials file, a missing driver. Every retry would
                // fail the same way, so report it immediately.
                return e;
            }
        }
        return lastFailure;
    }

    /**
     * Applies any migration files that this database has not seen yet.
     *
     * <p>Runs on every start, which is the whole point: after a {@code git
     * pull} the new {@code .sql} files are already in the working tree, and
     * nobody has to be told to run anything by hand. A database that is already
     * up to date costs one small query.</p>
     *
     * @return null when the schema is current, otherwise the failure
     */
    private MigrationException applyPendingMigrations() {
        try {
            MigrationRunner.Result result = MigrationRunner.run();
            System.out.println("[migration] " + result.summary());
            return null;
        } catch (MigrationException e) {
            return e;
        } catch (Throwable e) {
            // A broken migrations directory, a missing driver — anything that
            // is not the migration itself still has to stop startup, and still
            // has to say which stage failed.
            return new MigrationException("Database migrations could not be run.", e);
        }
    }

    /**
     * Names the migration that failed, because that is the only thing the
     * person reading this can act on.
     */
    private void reportFailedMigration(MigrationException failure) {
        failure.printStackTrace();
        String which = failure.getMigrationName() != null
                ? "Migration \"" + failure.getMigrationName() + "\" failed and was rolled back."
                : "The database migrations could not be run.";
        AlertUtil.error("University Registration System",
                "The database could not be brought up to date, so the application stopped "
                + "before opening.\n\n" + which
                + "\n\nNothing from that migration was saved, and no later migration was run."
                + "\n\nDatabase: " + DBConnection.describeTarget(),
                failure);
        Platform.exit();
    }

    /** Says what went wrong, in plain words, then exits instead of hanging. */
    private void reportUnreachableDatabase(Throwable failure) {
        failure.printStackTrace();
        // project_details.md Section 13 — the exact wording, character for character.
        // The driver's own explanation goes behind "Show details", where it can
        // name the real cause without turning the popup into a stack trace.
        AlertUtil.error("University Registration System",
                "Cannot connect to SQL Server. Check that the service is running — see DATABASE_SETUP.md"
                + "\n\nTried: " + DBConnection.describeTarget(),
                failure);
        Platform.exit();
    }

    /**
     * Role selection is the very first screen shown; it hands off to login.fxml
     * once Admin/Instructor/Student is picked.
     */
    private void showFirstScreen(Stage primaryStage) {
        SceneManager.getInstance().init(primaryStage);
        SceneManager.getInstance().switchRoot("role_selection.fxml", "University Registration System — Select Role");
        primaryStage.show();
        primaryStage.centerOnScreen();
    }

    @Override
    public void stop() {
        // If the pool never initialised, touching the class again throws
        // NoClassDefFoundError. Shutting down is best effort: a failure here must
        // not turn a clean exit into a second stack trace.
        try {
            DBConnection.shutdown();
        } catch (Throwable ignored) {
            // nothing to release
        }
    }

    public static void main(String[] args) {
        Launcher.main(args);
    }

}
