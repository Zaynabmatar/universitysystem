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
        try (Connection ignored = DBConnection.getConnection()) {
            // connection OK
        } catch (Exception e) {
            AlertUtil.error("Database connection failed",
                    "Cannot connect to SQL Server. Check that the service is running — see DATABASE_SETUP.md",
                    e);
            return;   // do not open a broken application
        }

        // 3. Open the login screen.
        SceneManager.getInstance().init(primaryStage);
        SceneManager.getInstance().switchRoot("login.fxml", "University Registration System — Login");
        primaryStage.show();
        primaryStage.centerOnScreen();
    }

    public static void main(String[] args) {
        Launcher.main(args);
    }

}
