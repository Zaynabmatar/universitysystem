import javafx.application.Application;

/**
 * Plain launcher for {@link BackArrowProbe}. The project is non-modular, so a
 * main class that extends Application reproduces "JavaFX runtime components are
 * missing" — same rule as com.university.Launcher.
 */
public class BackArrowProbeLauncher {
    public static void main(String[] args) {
        Application.launch(BackArrowProbe.class, args);
    }
}
