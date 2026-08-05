import com.university.enums.UserRole;
import com.university.model.Instructor;
import com.university.model.Student;
import com.university.service.AuthService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

/**
 * Loads the screens this phase touched, with a real admin session open, and
 * prints the first row of each table. Catches the class of failure that only
 * shows up when FXML meets controller: a renamed {@code fx:id} that no longer
 * has a field to bind to, or a column that binds but shows nothing.
 *
 * <p>Not part of the build. Read-only — it opens screens, it does not save.</p>
 */
public class UiProbe extends Application {

    @Override
    public void start(Stage stage) {
        int failures = 0;
        try {
            new AuthService().login(UserRole.ADMIN, "1", "1@iuL");
            System.out.println("signed in as admin 1");

            failures += show("admin_students.fxml", "studentTable");
            failures += show("admin_instructors.fxml", "instructorTable");
            failures += show("admin_reports.fxml", null);
            failures += show("instructor_grades.fxml", "gradeTable");
        } catch (Throwable t) {
            failures++;
            System.out.println("FAILED: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println(failures == 0 ? "ALL SCREENS OK" : failures + " SCREEN FAILURE(S)");
        Platform.exit();
        System.exit(0);
    }

    @SuppressWarnings("unchecked")
    private int show(String fxml, String tableId) {
        System.out.println("=== " + fxml + " ===");
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/" + fxml));
            Parent root = loader.load();
            new javafx.scene.Scene(root, 1200, 800);   // forces CSS + layout

            if (tableId != null) {
                TableView<?> table = (TableView<?>) root.lookup("#" + tableId);
                System.out.println("  rows: " + table.getItems().size());
                for (TableColumn<?, ?> column : table.getColumns()) {
                    Object first = table.getItems().isEmpty() ? "(no rows)"
                            : ((TableColumn<Object, ?>) column)
                                    .getCellObservableValue(table.getItems().get(0)) == null ? "(unbound)"
                            : ((TableColumn<Object, ?>) column)
                                    .getCellObservableValue(table.getItems().get(0)).getValue();
                    System.out.println("  " + pad(column.getText()) + " -> " + first);
                }
            } else {
                System.out.println("  loaded");
            }
            return 0;
        } catch (Throwable t) {
            System.out.println("  FAILED: " + t);
            t.printStackTrace(System.out);
            return 1;
        }
    }

    private String pad(String text) {
        String s = text == null ? "" : text;
        return s.length() >= 18 ? s : s + " ".repeat(18 - s.length());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
