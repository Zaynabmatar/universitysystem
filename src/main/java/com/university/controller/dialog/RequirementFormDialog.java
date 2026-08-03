package com.university.controller.dialog;

import com.university.model.Course;
import com.university.model.Program;
import com.university.model.ProgramRequirement;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;

/**
 * Add a course to a program's degree plan, or edit an existing entry's type
 * and recommended semester. Built in Java rather than FXML on purpose:
 * project_details.md Section 7 lists the complete set of FXML files and no
 * dialog appears in it.
 *
 * <p>Only the add path lets the admin pick the course — the plan is scoped to
 * one program at a time, and moving an entry to a different course would
 * really be a different plan row.</p>
 */
public class RequirementFormDialog extends Dialog<ProgramRequirement> {

    private static final String MANDATORY = "Mandatory";
    private static final String ELECTIVE = "Elective";

    private final ComboBox<Course> courseBox = new ComboBox<>();
    private final ComboBox<String> typeBox = new ComboBox<>();
    private final Spinner<Integer> semesterSpinner = new Spinner<>(1, 8, 1);
    private final Label errorLabel = new Label();

    private final boolean editMode;
    private final ProgramRequirement model;

    /**
     * @param existing null = add mode; non-null = edit mode (type and semester only)
     * @param program  the plan being edited, shown read-only
     * @param courses  every active course, for the add-mode combo box
     */
    public RequirementFormDialog(ProgramRequirement existing, Program program, List<Course> courses) {
        this.editMode = existing != null;
        this.model = editMode ? existing : new ProgramRequirement();
        if (!editMode) {
            model.setProgramId(program.getProgramId());
        }

        setTitle(editMode ? "Edit Degree Plan Entry" : "Add Course to Degree Plan");
        setHeaderText("Program: " + program);
        getDialogPane().setMinWidth(480);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());

        typeBox.getItems().setAll(MANDATORY, ELECTIVE);
        typeBox.setValue(MANDATORY);
        typeBox.setMaxWidth(Double.MAX_VALUE);
        semesterSpinner.setEditable(true);
        semesterSpinner.setMaxWidth(Double.MAX_VALUE);
        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14));
        int r = 0;

        if (editMode) {
            Course course = courses.stream()
                    .filter(c -> c.getCourseId() == existing.getCourseId())
                    .findFirst().orElse(null);
            g.addRow(r++, new Label("Course"), new Label(course == null ? "#" + existing.getCourseId() : course.toString()));
            typeBox.setValue(existing.isMandatory() ? MANDATORY : ELECTIVE);
            semesterSpinner.getValueFactory().setValue(existing.getRecommendedSemester());
        } else {
            courseBox.getItems().setAll(courses);
            courseBox.setConverter(new StringConverter<>() {
                @Override public String toString(Course c) { return c == null ? "" : c.toString(); }
                @Override public Course fromString(String s) { return null; }
            });
            courseBox.setMaxWidth(Double.MAX_VALUE);
            g.addRow(r++, new Label("Course *"), courseBox);
        }
        g.addRow(r++, new Label("Type *"), typeBox);
        g.addRow(r, new Label("Recommended semester *"), semesterSpinner);

        VBox box = new VBox(6, g, errorLabel);
        box.setPadding(new Insets(0, 14, 12, 14));
        box.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(box);

        // Block the dialog from closing while anything is invalid.
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

    private String validate() {
        clearErrorStyles();
        if (!editMode && courseBox.getValue() == null) {
            return "Select a course.";
        }
        if (typeBox.getValue() == null) {
            return "Select mandatory or elective.";
        }
        return null;
    }

    private void writeToModel() {
        if (!editMode) {
            model.setCourseId(courseBox.getValue().getCourseId());
        }
        model.setMandatory(MANDATORY.equals(typeBox.getValue()));
        model.setRecommendedSemester(semesterSpinner.getValue());
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
