package com.university.controller.dialog;

import com.university.model.Course;
import com.university.model.CoursePrerequisite;
import com.university.util.GradeScale;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;

/**
 * Add a prerequisite link, or edit the minimum grade of an existing one.
 * Built in Java rather than FXML on purpose: project_details.md Section 7
 * lists the complete set of FXML files and no dialog appears in it.
 *
 * <p>Only the add path lets the admin choose the two courses. Editing an
 * existing link changes its minimum grade only — the courses it connects
 * are shown read-only, because changing them would really be a different
 * link (and the service has no "move" operation, only add/remove).</p>
 */
public final class PrerequisiteFormDialog extends Dialog<CoursePrerequisite> {

    private final ComboBox<Course> courseBox = new ComboBox<>();
    private final ComboBox<Course> prereqBox = new ComboBox<>();
    private final ComboBox<String> minGradeBox = new ComboBox<>();
    private final Label errorLabel = new Label();

    private final boolean editMode;
    private final CoursePrerequisite model;

    /**
     * @param existing null = add mode; non-null = edit mode (minimum grade only)
     * @param courses  every course, active or not
     */
    public PrerequisiteFormDialog(CoursePrerequisite existing, List<Course> courses) {
        this.editMode = existing != null;
        this.model = editMode ? existing : new CoursePrerequisite();

        setTitle(editMode ? "Edit Minimum Grade" : "Add Prerequisite");
        getDialogPane().setMinWidth(520);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());

        minGradeBox.getItems().setAll(GradeScale.labels());

        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14));

        if (existing != null) {
            setHeaderText("Editing the rule that " + courseOf(courses, existing.getPrerequisiteCourseId())
                    + " must be passed before " + courseOf(courses, existing.getCourseId()) + ".");
            minGradeBox.setValue(GradeScale.labelFor(existing.getMinGradePoints()));
            g.addRow(0, new Label("Minimum grade *"), minGradeBox);
        } else {
            setHeaderText("Choose the course and the prerequisite it requires.");
            courseBox.getItems().setAll(courses);
            prereqBox.getItems().setAll(courses);
            StringConverter<Course> converter = new StringConverter<>() {
                @Override public String toString(Course c) { return c == null ? "" : c.toString(); }
                @Override public Course fromString(String s) { return null; }
            };
            courseBox.setConverter(converter);
            prereqBox.setConverter(converter);
            courseBox.setMaxWidth(Double.MAX_VALUE);
            prereqBox.setMaxWidth(Double.MAX_VALUE);
            minGradeBox.setMaxWidth(Double.MAX_VALUE);
            minGradeBox.setValue(GradeScale.DEFAULT_LABEL);

            g.addRow(0, new Label("This course *"), courseBox);
            g.addRow(1, new Label("...requires this course to be passed first *"), prereqBox);
            g.addRow(2, new Label("Minimum grade *"), minGradeBox);
        }

        VBox box = new VBox(6, g, errorLabel);
        box.setPadding(new Insets(0, 14, 12, 14));
        box.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(box);

        // Block the dialog from closing while anything is invalid. The cycle check itself
        // runs in CourseService.addPrerequisite and is reported by the caller after OK.
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String problem = validate();
            if (problem != null) {
                showError(problem);
                event.consume();
            }
        });

        setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            writeToModel();
            return model;
        });
    }

    private String courseOf(List<Course> courses, int courseId) {
        return courses.stream()
                .filter(c -> c.getCourseId() == courseId)
                .findFirst()
                .map(c -> c.getCourseCode())
                .orElse("#" + courseId);
    }

    private String validate() {
        clearErrorStyles();

        if (!editMode) {
            if (courseBox.getValue() == null || prereqBox.getValue() == null) {
                errorLabel.getStyleClass().add("error-text");
                return "Select both courses.";
            }
            if (courseBox.getValue().getCourseId() == prereqBox.getValue().getCourseId()) {
                return "A course cannot be its own prerequisite.";
            }
        }
        if (minGradeBox.getValue() == null) {
            return "Select a minimum grade.";
        }
        return null;
    }

    private void writeToModel() {
        if (!editMode) {
            model.setCourseId(courseBox.getValue().getCourseId());
            model.setPrerequisiteCourseId(prereqBox.getValue().getCourseId());
        }
        model.setMinGradePoints(GradeScale.pointsFor(minGradeBox.getValue()));
    }

    private void clearErrorStyles() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }
}
