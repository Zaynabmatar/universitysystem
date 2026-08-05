package com.university;

import com.university.database.DBConnection;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The JavaFX application.
 *
 * <p>NOTE: this class is never the {@code <mainClass>}. {@link Launcher#main}
 * starts it — see project_details.md Section 8, CRITICAL BUILD RULE.</p>
 */
public class App extends Application {

    @Override
    @SuppressWarnings("try")   // the connection is opened only to prove it can be; see the try body
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

        // 3. Fail fast, and fail politely, if SQL Server is not reachable.
        //    Throwable, not Exception: a genuinely broken config (e.g. driver
        //    missing) is still an Error/RuntimeException, not a SQLException,
        //    and retrying that would just waste the whole retry window.
        //
        //    SQLException is retried with a short backoff: SQL Server Express
        //    can still be finishing crash recovery well after the Windows
        //    service reports "Running" (measured ~2m24s gap on this machine),
        //    so the first attempt right after login can legitimately fail
        //    while the database is still starting up.
        final int maxAttempts = 10;
        final long retryDelayMillis = 3_000;
        SQLException lastFailure = null;
        boolean connected = false;
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try (Connection ignored = DBConnection.getConnection()) {
                    connected = true;
                    break;
                } catch (SQLException e) {
                    lastFailure = e;
                    if (attempt < maxAttempts) {
                        Thread.sleep(retryDelayMillis);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            e.printStackTrace();
            AlertUtil.error("University Registration System",
                    "Cannot connect to SQL Server. Check that the service is running — see DATABASE_SETUP.md");
            Platform.exit();
            return;   // never fall through into a half-built login screen
        }
        if (!connected) {
            if (lastFailure != null) {
                lastFailure.printStackTrace();
            }
            // project_details.md Section 13 — the exact wording, character for character.
            AlertUtil.error("University Registration System",
                    "Cannot connect to SQL Server. Check that the service is running — see DATABASE_SETUP.md");
            Platform.exit();
            return;   // never fall through into a half-built login screen
        }

        // 4. Login is the very first screen shown.
        SceneManager.getInstance().init(primaryStage);
        SceneManager.getInstance().switchRoot("login.fxml", "University Registration System — Login");
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
