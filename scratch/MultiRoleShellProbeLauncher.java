import javafx.application.Application;

/** Plain (non-Application) entry point, so JavaFX starts without the module path. */
public class MultiRoleShellProbeLauncher {
    public static void main(String[] args) {
        Application.launch(MultiRoleShellProbe.class, args);
    }
}
