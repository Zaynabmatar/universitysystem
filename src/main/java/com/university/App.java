package com.university;

import com.university.database.DBConnection;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.application.Application;
import javafx.stage.Stage;

import java.sql.Connection;

/**
 * The JavaFX application.
 *
 * <p>NOTE: this class is never the {@code <mainClass>}. {@link Launcher#main}
 * starts it — see project_details.md Section 8, CRITICAL BUILD RULE.</p>
 */
public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        // 1. Theme. The spec asks for AtlantaFX; that artefact could not be
        //    downloaded here, so this is the documented fallback from
        //    phase-05/context/preflight.md §4. css/app.css carries the styling.
        setUserAgentStylesheet(STYLESHEET_MODENA);

        // 2. Fail fast, and fail politely, if SQL Server is not reachable.
        //    Throwable, not Exception: DBConnection builds its pool in a static
        //    initialiser, so the first touch of the class throws
        //    ExceptionInInitializerError — an Error, which catch (Exception)
        //    misses. That is what turned a wrong password into a stack trace on
        //    the console instead of the popup Section 13 asks for.
        try (Connection ignored = DBConnection.getConnection()) {
            // connection OK
        } catch (Throwable e) {
            AlertUtil.error("Database connection failed",
                    "Cannot connect to SQL Server. Check that the service is running, and that the "
                    + "login details in DBConnection are correct — see DATABASE_SETUP.md",
                    e);
            return;   // do not open a broken application
        }

        // 3. Open the login screen.
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
