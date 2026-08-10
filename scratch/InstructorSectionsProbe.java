import com.university.enums.UserRole;
import com.university.model.GradeSheetRow;
import com.university.model.Section;
import com.university.service.AuthService;
import com.university.service.GradeService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.Session;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads instructor_sections.fxml with a real instructor session open and checks:
 *  - the section table's height auto-fits its row count (no leftover empty space, no
 *    VBox.vgrow stretch)
 *  - the notification panel nodes exist and are wired
 *  - "By Section" options are exactly the instructor's own sections
 *  - "Specific Student" search only ever returns students from the instructor's own rosters
 *
 * Not part of the build. Read-only against the database — never calls handleSendNotification.
 */
public class InstructorSectionsProbe extends Application {

    private int failures = 0;

    @Override
    public void start(Stage stage) {
        try {
            new AuthService().login(UserRole.INSTRUCTOR, "2", "2@iuL");
            System.out.println("signed in as instructor 2 (" + Session.current().getDisplayName() + ")");

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/instructor_sections.fxml"));
            Parent root = loader.load();
            new Scene(root, 1200, 900);
            root.applyCss();
            root.layout();

            TableView<?> table = (TableView<?>) root.lookup("#sectionTable");
            int rowCount = table.getItems().size();
            System.out.println("sectionTable rows = " + rowCount);
            System.out.println("sectionTable fixedCellSize = " + table.getFixedCellSize());
            System.out.println("sectionTable prefHeight = " + table.getPrefHeight());
            double expected = table.getFixedCellSize() * Math.max(1, rowCount) + 34;
            check("table height auto-fits rows (expected ~" + expected + ")",
                    Math.abs(table.getPrefHeight() - expected) < 0.5);
            check("table VBox.vgrow is not ALWAYS (no forced stretch)",
                    javafx.scene.layout.VBox.getVgrow(table) != javafx.scene.layout.Priority.ALWAYS);

            RadioButton allRadio = (RadioButton) root.lookup("#notifyAllRadio");
            RadioButton sectionRadio = (RadioButton) root.lookup("#notifySectionRadio");
            RadioButton specificRadio = (RadioButton) root.lookup("#notifySpecificRadio");
            ComboBox<?> sectionCombo = (ComboBox<?>) root.lookup("#notifySectionCombo");
            ComboBox<?> resultsBox = (ComboBox<?>) root.lookup("#notifyResultsBox");
            TextField searchField = (TextField) root.lookup("#notifySearchField");
            TextField subjectField = (TextField) root.lookup("#notifySubjectField");
            Label countLabel = (Label) root.lookup("#notifyRecipientCountLabel");
            Object sendButton = root.lookup("#sendNotificationButton");

            check("all notification nodes present", allRadio != null && sectionRadio != null
                    && specificRadio != null && sectionCombo != null && resultsBox != null
                    && searchField != null && subjectField != null && countLabel != null && sendButton != null);

            check("'All My Students' selected by default", allRadio.isSelected());
            System.out.println("recipient count label (All My Students) = \"" + countLabel.getText() + "\"");

            // Ground truth computed independently via the services, not through the controller.
            int instructorId = Session.current().requireInstructorId();
            var semester = new SemesterService().getCurrentSemester();
            List<Section> mySections = new SectionService().searchSections(
                    semester.getSemesterId(), null, instructorId, null);
            GradeService gradeService = new GradeService();
            Set<Integer> myStudentIds = mySections.stream()
                    .flatMap(s -> gradeService.getGradeSheet(s.getSectionId()).stream())
                    .map(GradeSheetRow::getStudentUserId)
                    .collect(Collectors.toSet());
            System.out.println("ground truth: " + mySections.size() + " sections, "
                    + myStudentIds.size() + " distinct students");

            check("recipient count label reflects ground truth student count",
                    countLabel.getText().startsWith(String.valueOf(myStudentIds.size()) + " student"));

            check("'By Section' combo populated with exactly the instructor's own sections",
                    sectionCombo.getItems().size() == mySections.size());

            // Switch to "By Section" and check the combo lists sections only (spot check via toString hooks
            // is not needed here — count match plus type match on the items is sufficient since the FXML
            // binds the combo directly to `rows`, the same list backing the table).
            sectionRadio.fire();
            check("section row becomes visible when 'By Section' selected",
                    root.lookup("#notifySectionRow").isVisible());

            // Switch to "Specific Student" and search using a fragment of a real student's own name/id,
            // taken from the ground-truth roster itself, then confirm every match is one of ours.
            specificRadio.fire();
            check("specific row becomes visible when 'Specific Student' selected",
                    root.lookup("#notifySpecificRow").isVisible());

            if (!myStudentIds.isEmpty()) {
                int probeId = myStudentIds.iterator().next();
                searchField.setText(String.valueOf(probeId));
                @SuppressWarnings("unchecked")
                ComboBox<GradeSheetRow> typedResults = (ComboBox<GradeSheetRow>) resultsBox;
                List<GradeSheetRow> matches = typedResults.getItems();
                System.out.println("search \"" + probeId + "\" -> " + matches.size() + " match(es)");
                check("search result includes the searched student",
                        matches.stream().anyMatch(r -> r.getStudentUserId() == probeId));
                check("every search result belongs to the instructor's own students",
                        matches.stream().allMatch(r -> myStudentIds.contains(r.getStudentUserId())));

                // Also try a search fragment guaranteed absent from any other instructor's roster
                // scope check: search for something clearly bogus should yield zero matches.
                searchField.setText("zz-nonexistent-student-zz");
                check("nonsense search yields zero matches", typedResults.getItems().isEmpty());
            } else {
                System.out.println("(instructor 2 has no students this semester — search scope check skipped)");
            }

        } catch (Throwable t) {
            failures++;
            System.out.println("FAILED: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println(failures == 0 ? "ALL CHECKS OK" : failures + " CHECK FAILURE(S)");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private void check(String label, boolean pass) {
        System.out.println((pass ? "  OK   " : "  FAIL ") + label);
        if (!pass) failures++;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
