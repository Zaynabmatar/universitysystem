package com.university.controller.dialog;

import com.university.model.Course;
import com.university.model.Department;
import com.university.util.ValidationUtil;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;

/**
 * Add / Edit course. Built in Java rather than FXML on purpose:
 * project_details.md Section 7 lists the complete set of FXML files and no
 * dialog appears in it.
 */
public class CourseFormDialog extends Dialog<Course> {

    private final TextField courseCodeField          = new TextField();
    private final TextField courseTitleField         = new TextField();
    private final TextArea descriptionField          = new TextArea();
    private final Spinner<Integer> creditsSpinner     = new Spinner<>(1, 6, 3);
    private final ComboBox<Department> departmentBox = new ComboBox<>();
    private final ComboBox<Integer> yearBox           = new ComboBox<>();
    private final Label errorLabel                    = new Label();

    private final boolean editMode;
    private final Course model;

    /**
     * @param existing    null = add mode; non-null = edit mode
     * @param departments every department, active or not
     */
    public CourseFormDialog(Course existing, List<Department> departments) {
        this.editMode = existing != null;
        this.model = editMode ? existing : new Course();

        setTitle(editMode ? "Edit Course" : "Add Course");
        setHeaderText(editMode ? "Editing " + existing.getCourseTitle() : "Create a new course.");
        getDialogPane().setMinWidth(560);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var css = getClass().getResource("/css/app.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());

        departmentBox.getItems().setAll(departments);
        departmentBox.setConverter(new StringConverter<>() {
            @Override public String toString(Department d) { return d == null ? "" : d.toString(); }
            @Override public Department fromString(String s) { return null; }
        });
        yearBox.getItems().setAll(1, 2, 3, 4);
        creditsSpinner.setEditable(true);
        descriptionField.setPrefRowCount(3);
        departmentBox.setMaxWidth(Double.MAX_VALUE);
        yearBox.setMaxWidth(Double.MAX_VALUE);
        creditsSpinner.setMaxWidth(Double.MAX_VALUE);
        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        g.setPadding(new Insets(14));
        int r = 0;
        g.addRow(r++, new Label("Course code *"), courseCodeField, new Label("Credits *"), creditsSpinner);
        g.addRow(r++, new Label("Title *"), courseTitleField, new Label("Study year *"), yearBox);
        g.addRow(r++, new Label("Department *"), departmentBox);
        g.addRow(r++, new Label("Description"), descriptionField);
        GridPane.setColumnSpan(descriptionField, 3);

        VBox box = new VBox(6, g, errorLabel);
        box.setPadding(new Insets(0, 14, 12, 14));
        box.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(box);

        if (editMode) fillFromModel(existing, departments);

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

    private void fillFromModel(Course c, List<Department> departments) {
        courseCodeField.setText(c.getCourseCode());
        courseTitleField.setText(c.getCourseTitle());
        descriptionField.setText(c.getDescription());
        creditsSpinner.getValueFactory().setValue(c.getCredits());
        yearBox.setValue(c.getLevelYear());
        departments.stream()
                .filter(d -> d.getDepartmentId() == c.getDepartmentId())
                .findFirst()
                .ifPresent(departmentBox::setValue);
    }

    /** @return null when everything is valid, otherwise the message to show the admin. */
    private String validate() {
        clearErrorStyles();

        if (!ValidationUtil.isCourseCode(courseCodeField.getText())) {
            return mark(courseCodeField, "Course code must be 2–4 letters followed by 3 digits, e.g. CS201.");
        }
        if (ValidationUtil.isBlank(courseTitleField.getText()) || !ValidationUtil.maxLength(courseTitleField.getText(), 100)) {
            return mark(courseTitleField, "Course title is required (maximum 100 characters).");
        }
        if (!ValidationUtil.maxLength(descriptionField.getText(), 500)) {
            return mark(descriptionField, "Description must be 500 characters or fewer.");
        }
        if (departmentBox.getValue() == null) {
            return mark(departmentBox, "Select a department.");
        }
        if (yearBox.getValue() == null) {
            return mark(yearBox, "Select the study year (1–4).");
        }
        return null;
    }

    private void writeToModel() {
        model.setCourseCode(courseCodeField.getText().trim());
        model.setCourseTitle(courseTitleField.getText().trim());
        model.setDescription(ValidationUtil.trimToNull(descriptionField.getText()));
        model.setCredits(creditsSpinner.getValue());
        model.setDepartmentId(departmentBox.getValue().getDepartmentId());
        model.setLevelYear(yearBox.getValue());
    }

    private String mark(Control field, String message) {
        field.getStyleClass().add("field-error");
        field.requestFocus();
        return message;
    }

    private void clearErrorStyles() {
        for (Control c : List.<Control>of(courseCodeField, courseTitleField, descriptionField,
                departmentBox, yearBox)) {
            c.getStyleClass().remove("field-error");
        }
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }
}
