import com.university.enums.CalendarEventType;
import com.university.enums.UserRole;
import com.university.model.Semester;
import com.university.service.AcademicCalendarService;
import com.university.service.AuthService;
import com.university.service.SemesterService;
import com.university.service.Session;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Loads the redesigned academic_calendar.fxml (Student "My Calendar") and
 * admin_academic_calendar.fxml (Admin "Academic Calendar") with a real signed-in session
 * against the real database, applies CSS/layout, and checks the new single semester week
 * table (Week | Mon..Sat | Remarks) actually rendered: right row/column count, a colored
 * day cell, a populated legend, populated notes, and (Admin only) that a day cell is wired
 * to open the Add/Edit dialog. Saves a snapshot PNG of each screen to scratch/.
 *
 * Not part of the build. Read-only against the database.
 */
public class AcademicCalendarProbe extends Application {

    private int failures = 0;

    @Override
    public void start(Stage stage) {
        try {
            probeStudent();
            probeAdmin();
        } catch (Throwable t) {
            failures++;
            System.out.println("FAILED: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println(failures == 0 ? "ALL CHECKS OK" : failures + " CHECK FAILURE(S)");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private void probeStudent() throws Exception {
        new AuthService().login(UserRole.STUDENT, "4", "4@iuL");
        System.out.println("signed in as student 4 (" + Session.current().getDisplayName() + ")");

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/academic_calendar.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root, 1500, 1700);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        root.applyCss();
        root.layout();

        VBox calendarBox = (VBox) root.lookup("#calendarBox");
        VBox legendBox = (VBox) root.lookup("#legendBox");
        VBox notesBox = (VBox) root.lookup("#notesBox");
        check("STUDENT: calendarBox present", calendarBox != null);
        check("STUDENT: legendBox present", legendBox != null);
        check("STUDENT: notesBox present", notesBox != null);

        checkWeekTable("STUDENT", calendarBox, legendBox, notesBox, false);
        shoot(root, "student_my_calendar");
    }

    private void probeAdmin() throws Exception {
        new AuthService().login(UserRole.ADMIN, "1", "1@iuL");
        System.out.println("signed in as admin 1 (" + Session.current().getDisplayName() + ")");

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/admin_academic_calendar.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root, 1500, 1200);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        root.applyCss();
        root.layout();

        VBox calendarBox = (VBox) root.lookup("#calendarBox");
        VBox legendBox = (VBox) root.lookup("#legendBox");
        VBox notesBox = (VBox) root.lookup("#notesBox");
        TableView<?> eventTable = (TableView<?>) root.lookup("#eventTable");
        check("ADMIN: calendarBox present", calendarBox != null);
        check("ADMIN: legendBox present", legendBox != null);
        check("ADMIN: notesBox present", notesBox != null);
        check("ADMIN: custom-events management table present", eventTable != null);

        checkWeekTable("ADMIN", calendarBox, legendBox, notesBox, true);
        shoot(root, "admin_academic_calendar");
    }

    private void checkWeekTable(String label, VBox calendarBox, VBox legendBox, VBox notesBox,
                                 boolean expectEditable) {
        check(label + ": calendarBox has exactly one child (the week table)",
                calendarBox.getChildren().size() == 1);
        Node tableNode = calendarBox.getChildren().get(0);
        check(label + ": week table is a GridPane with the 'week-table' style class",
                tableNode instanceof GridPane && tableNode.getStyleClass().contains("week-table"));
        if (!(tableNode instanceof GridPane grid)) {
            return;
        }

        Semester semester = new SemesterService().getCurrentSemester();
        List<AcademicCalendarService.WeekRow> weeks =
                new AcademicCalendarService().weeksForSemester(semester, List.of());
        System.out.println(label + ": semester = " + semester.getSemesterName()
                + " (" + semester.getStartDate() + " to " + semester.getEndDate() + "), "
                + weeks.size() + " week rows expected");

        int maxRow = 0;
        int maxCol = 0;
        for (Node n : grid.getChildren()) {
            maxRow = Math.max(maxRow, GridPane.getRowIndex(n) == null ? 0 : GridPane.getRowIndex(n));
            maxCol = Math.max(maxCol, GridPane.getColumnIndex(n) == null ? 0 : GridPane.getColumnIndex(n));
        }
        check(label + ": table has 8 columns (Week, Mon..Sat, Remarks)", maxCol == 7);
        check(label + ": table has one row per week plus the header ("
                + (maxRow + 1) + " rows, expected " + (weeks.size() + 1) + ")",
                maxRow + 1 == weeks.size() + 1);

        long coloredCells = grid.lookupAll(".week-day-number").stream()
                .filter(n -> java.util.Arrays.stream(CalendarEventType.values())
                        .anyMatch(t -> n.getStyleClass().contains(t.getCssClass())))
                .count();
        System.out.println(label + ": colored day cells = " + coloredCells);
        check(label + ": at least one day cell is painted with an event color", coloredCells > 0);

        boolean anyEditable = grid.lookupAll(".week-day-editable").stream()
                .anyMatch(n -> n.getOnMouseClicked() != null);
        check(label + ": day cells " + (expectEditable ? "are" : "are NOT") + " wired for click-to-edit",
                anyEditable == expectEditable);

        check(label + ": legend has at least one row", legendBox.getChildren().size() > 1);
        check(label + ": Academic Notes has at least one line", !notesBox.getChildren().isEmpty());
    }

    private void shoot(Parent root, String name) {
        try {
            WritableImage image = root.getScene().snapshot(null);
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();
            PixelReader reader = image.getPixelReader();
            BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    buffered.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            File out = new File("scratch/" + name + ".png");
            ImageIO.write(buffered, "png", out);
            System.out.println("   saved " + out.getPath());
        } catch (Exception e) {
            System.out.println("   snapshot " + name + " failed: " + e);
        }
    }

    private void check(String label, boolean pass) {
        System.out.println((pass ? "  OK   " : "  FAIL ") + label);
        if (!pass) failures++;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
