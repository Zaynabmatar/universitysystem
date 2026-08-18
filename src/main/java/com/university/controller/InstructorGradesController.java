package com.university.controller;

import com.university.enums.LetterGrade;
import com.university.model.Course;
import com.university.model.GradeSheetRow;
import com.university.model.Section;
import com.university.model.Semester;
import com.university.service.CourseService;
import com.university.service.GradeService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.service.ValidationException;
import com.university.util.AlertUtil;
import com.university.util.CsvExporter;
import com.university.util.GradeCalculator;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.util.StringConverter;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The grade sheet. Marks are edited in place and the Total / Letter / Points columns are
 * recomputed live by {@link GradeCalculator} on every committed edit — nothing reaches the
 * database until Save Draft or Submit and Lock is pressed (project_details.md Section 5.1/5.2).
 *
 * <p>ADMIN MODE: the same screen is reused for the registrar, so there is no
 * {@code admin_grades.fxml}. When the signed-in user is an ADMIN the sheet is read-only — no
 * Save Draft, Publish or Submit — and the only action available is "Unlock Grades", which reopens
 * a whole Submit-&amp;-Locked section for the instructor via {@link GradeService#unlockSection}.
 * The role is re-checked in the service — this screen only decides what to show.</p>
 */
public class InstructorGradesController {

    @FXML private Label titleLabel;
    @FXML private Label sectionLabel;
    @FXML private Label windowLabel;
    @FXML private Label lockBanner;
    @FXML private Label statsLabel;
    @FXML private Label weightingLabel;
    @FXML private ComboBox<SectionChoice> sectionCombo;
    @FXML private TableView<GradeSheetRow> gradeTable;
    @FXML private TableColumn<GradeSheetRow, String> colStudentId;
    @FXML private TableColumn<GradeSheetRow, String> colStudentName;
    @FXML private TableColumn<GradeSheetRow, BigDecimal> colCoursework;
    @FXML private TableColumn<GradeSheetRow, BigDecimal> colMidterm;
    @FXML private TableColumn<GradeSheetRow, BigDecimal> colLab;
    @FXML private TableColumn<GradeSheetRow, BigDecimal> colFinal;
    @FXML private TableColumn<GradeSheetRow, BigDecimal> colTotal;
    @FXML private TableColumn<GradeSheetRow, String> colLetter;
    @FXML private TableColumn<GradeSheetRow, BigDecimal> colPoints;
    @FXML private Button saveDraftButton;
    @FXML private Button publishButton;
    @FXML private Button submitButton;
    @FXML private Button unlockButton;
    @FXML private Button exportButton;

    private final GradeService gradeService = new GradeService();
    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();

    private final ObservableList<GradeSheetRow> rows = FXCollections.observableArrayList();

    private int sectionId;
    private String sectionTitle = "";
    private boolean adminMode;
    /** Guards the combo listener while {@link #load} is setting the selection itself. */
    private boolean loading;

    /** One entry of the section chooser — the screen is reachable from the sidebar as well. */
    public record SectionChoice(int sectionId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    @FXML
    private void initialize() {
        adminMode = Session.current().isAdmin();

        colStudentId.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getStudentUserId())));
        colStudentName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getStudentName()));
        colCoursework.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getCourseworkMark()));
        colMidterm.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getMidtermMark()));
        colLab.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getLabMark()));
        colFinal.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getFinalMark()));
        colTotal.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getTotalMark()));
        colPoints.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getGradePoints()));
        colLetter.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getLetterGrade() == null ? null : c.getValue().getLetterGrade().getLabel()));

        StringConverter<BigDecimal> markConverter = markConverter();
        colCoursework.setCellFactory(col -> editableMarkCell(markConverter, GradeSheetRow::getCourseworkMaxMark));
        colMidterm.setCellFactory(col -> editableMarkCell(markConverter, GradeSheetRow::getMidtermMaxMark));
        colLab.setCellFactory(col -> editableMarkCell(markConverter, GradeSheetRow::getCourseworkMaxMark));
        colFinal.setCellFactory(col -> editableMarkCell(markConverter, GradeSheetRow::getFinalMaxMark));

        colCoursework.setOnEditCommit(e -> applyEdit(e, Mark.COURSEWORK));
        colMidterm.setOnEditCommit(e -> applyEdit(e, Mark.MIDTERM));
        colLab.setOnEditCommit(e -> applyEdit(e, Mark.LAB));
        colFinal.setOnEditCommit(e -> applyEdit(e, Mark.FINAL));

        colTotal.setCellFactory(col -> plainDecimalCell());
        colPoints.setCellFactory(col -> plainDecimalCell());

        // Colour the letter so an F is obvious at a glance.
        colLetter.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String letter, boolean empty) {
                super.updateItem(letter, empty);
                getStyleClass().removeAll("grade-pass", "grade-fail");
                if (empty || letter == null) {
                    setText(null);
                    return;
                }
                setText(letter);
                LetterGrade grade = LetterGrade.fromDb(letter);
                getStyleClass().add(grade.isPassing() ? "grade-pass" : "grade-fail");
            }
        });

        gradeTable.setItems(rows);
        gradeTable.setPlaceholder(new Label("Choose one of your sections to start entering grades."));

        titleLabel.setText(adminMode ? "Grades (Registrar)" : "Enter Grades");
        saveDraftButton.setVisible(!adminMode);
        saveDraftButton.setManaged(!adminMode);
        publishButton.setVisible(!adminMode);
        publishButton.setManaged(!adminMode);
        submitButton.setVisible(!adminMode);
        submitButton.setManaged(!adminMode);
        unlockButton.setVisible(adminMode);
        unlockButton.setManaged(adminMode);
        setButtonsDisabled(true);

        fillSectionChooser();
        sectionCombo.valueProperty().addListener((obs, old, chosen) -> {
            if (loading || chosen == null) {
                return;
            }
            sectionId = chosen.sectionId();
            sectionTitle = chosen.label();
            sectionLabel.setText(sectionTitle);
            refresh();
        });
    }

    /**
     * Opens the sheet on one section. Called by {@link InstructorSectionsController} and by the
     * registrar's "Grades / Correct" button on {@code admin_sections.fxml}.
     */
    public void load(int sectionId, String sectionTitle) {
        this.sectionId = sectionId;
        this.sectionTitle = sectionTitle == null ? "" : sectionTitle;
        sectionLabel.setText(this.sectionTitle);

        loading = true;
        sectionCombo.getItems().stream()
                .filter(choice -> choice.sectionId() == sectionId)
                .findFirst()
                .ifPresent(sectionCombo::setValue);
        loading = false;

        refresh();
    }

    // ------------------------------------------------------------------ live computation

    private enum Mark { COURSEWORK, MIDTERM, LAB, FINAL }

    /** THE LIVE RECOMPUTE — Sections 5.1 and 5.2 applied on every committed edit. */
    private void applyEdit(TableColumn.CellEditEvent<GradeSheetRow, BigDecimal> event, Mark which) {
        GradeSheetRow row = event.getRowValue();
        if (row == null) {
            return;
        }
        BigDecimal value = event.getNewValue();

        boolean submittedRowLocked = row.isSubmitted();
        if (submittedRowLocked) { // rule G4
            AlertUtil.error("Locked",
                    "This section has been submitted and locked. Only the registrar can change "
                    + "these grades now — ask them to Unlock Grades first.");
            gradeTable.refresh();
            return;
        }
        BigDecimal max = switch (which) {
            case COURSEWORK, LAB -> row.getCourseworkMaxMark();
            case MIDTERM -> row.getMidtermMaxMark();
            case FINAL -> row.getFinalMaxMark();
        };
        if (value != null && !GradeCalculator.isValidMark(value, max)) { // rule G3
            AlertUtil.error("Invalid mark",
                    "Marks must be between 0 and " + max.stripTrailingZeros().toPlainString() + ".");
            gradeTable.refresh();
            return;
        }
        switch (which) {
            case COURSEWORK -> row.setCourseworkMark(value);
            case MIDTERM -> row.setMidtermMark(value);
            case LAB -> row.setLabMark(value);
            case FINAL -> row.setFinalMark(value);
        }
        row.recompute(); // <- the live letter and points
        gradeTable.refresh();
        updateStats();
    }

    // ------------------------------------------------------------------ loading

    private void fillSectionChooser() {
        try {
            Semester current = semesterService.getCurrentSemester();
            if (current == null) {
                return;
            }
            // G1 at the data level for an instructor: only sections carrying their instructor_id
            // are ever fetched. The registrar legitimately sees them all.
            Integer instructorId = adminMode ? null : Session.current().requireInstructorId();
            List<Section> sections = sectionService.searchSections(
                    current.getSemesterId(), null, instructorId, null);
            List<Course> courses = courseService.listCourses(false);

            List<SectionChoice> choices = sections.stream()
                    .map(section -> new SectionChoice(section.getSectionId(),
                            labelFor(section, courses)))
                    .toList();
            sectionCombo.setItems(FXCollections.observableArrayList(choices));
        } catch (RuntimeException e) {
            AlertUtil.error("Grades", "Your sections could not be loaded.", e);
        }
    }

    private String labelFor(Section section, List<Course> courses) {
        Optional<Course> course = courses.stream()
                .filter(c -> c.getCourseId() == section.getCourseId())
                .findFirst();
        return course.map(c -> c.getCourseCode() + "-" + section.getSectionNumber() + "  " + c.getCourseTitle())
                .orElse("Section " + section.getSectionNumber());
    }

    private void refresh() {
        if (sectionId <= 0) {
            rows.clear();
            setButtonsDisabled(true);
            colLab.setVisible(false);
            weightingLabel.setText("");
            return;
        }
        try {
            rows.setAll(gradeService.getGradeSheet(sectionId));

            boolean locked = gradeService.isSectionSubmitted(sectionId);

            gradeTable.setEditable(!adminMode);
            setButtonsDisabled(false);
            exportButton.setDisable(false);
            unlockButton.setDisable(!locked);

            applyLabVisibility();

            lockBanner.setText(locked
                    ? (adminMode
                        ? "This section is submitted. Use \"Unlock Grades\" to let the instructor "
                          + "edit the whole section again."
                        : "🔒 Submitted and locked — you can no longer edit these grades. Ask the "
                          + "registrar to Unlock Grades if a correction is needed.")
                    : "");
            lockBanner.setVisible(locked);
            lockBanner.setManaged(locked);

            showGradeWindow();
            updateStats();
        } catch (RuntimeException e) {
            rows.clear();
            setButtonsDisabled(true);
            AlertUtil.error("Grades", "The grade sheet could not be loaded. Please try again.", e);
        }
    }

    /**
     * Shows the Lab column, and switches the weighting label, only for a section whose course
     * actually has a lab component ({@code courses.has_lab}) — never for one hardcoded course.
     */
    private void applyLabVisibility() {
        boolean hasLab = rows.isEmpty() ? currentSectionHasLab() : rows.get(0).isHasLab();

        colCoursework.setVisible(!hasLab);

        colLab.setVisible(hasLab);

        weightingLabel.setText(weightingLabelText(hasLab));
    }

    /** Reads the course's own weights and max marks straight from the loaded rows — never a hardcoded string. */
    private String weightingLabelText(boolean hasLab) {
        BigDecimal componentWeight, midtermWeight, finalWeight;
        BigDecimal componentMax, midtermMax, finalMax;
        if (!rows.isEmpty()) {
            GradeSheetRow first = rows.get(0);
            componentWeight = first.getCourseworkWeight();
            midtermWeight = first.getMidtermWeight();
            finalWeight = first.getFinalWeight();
            componentMax = first.getCourseworkMaxMark();
            midtermMax = first.getMidtermMaxMark();
            finalMax = first.getFinalMaxMark();
        } else {
            Course course = currentSectionCourse();
            componentWeight = course == null ? null : course.getCourseworkWeight();
            midtermWeight = course == null ? null : course.getMidtermWeight();
            finalWeight = course == null ? null : course.getFinalWeight();
            componentMax = course == null ? null : course.getCourseworkMaxMark();
            midtermMax = course == null ? null : course.getMidtermMaxMark();
            finalMax = course == null ? null : course.getFinalMaxMark();
        }
        if (componentWeight == null || midtermWeight == null || finalWeight == null) {
            return "";
        }

        String componentLabel = (hasLab ? "lab" : "coursework") + " " + pct(componentWeight, componentMax);
        String midtermLabel = "midterm " + pct(midtermWeight, midtermMax);
        String finalLabel = "final " + pct(finalWeight, finalMax);
        return hasLab
                ? "Weighting: " + midtermLabel + "  •  " + componentLabel + "  •  " + finalLabel
                : "Weighting: " + componentLabel + "  •  " + midtermLabel + "  •  " + finalLabel;
    }

    private String pct(BigDecimal weight, BigDecimal max) {
        return weight.stripTrailingZeros().toPlainString() + "% (out of "
                + max.stripTrailingZeros().toPlainString() + ")";
    }

    private boolean currentSectionHasLab() {
        Course course = currentSectionCourse();
        return course != null && course.isHasLab();
    }

    private Course currentSectionCourse() {
        Section section = sectionService.findById(sectionId);
        if (section == null) {
            return null;
        }
        return courseService.findCourseById(section.getCourseId()).orElse(null);
    }

    /** Rule G2 made visible before the instructor types anything. */
    private void showGradeWindow() {
        Section section = sectionService.findById(sectionId);
        if (section == null) {
            windowLabel.setText("");
            return;
        }
        Semester semester = semesterService.findById(section.getSemesterId());
        if (semester == null || semester.getGradeEntryStart() == null || semester.getGradeEntryEnd() == null) {
            windowLabel.setText("Grade entry period: not set for this semester.");
            return;
        }
        windowLabel.setText("Grade entry: " + semester.getGradeEntryStart()
                + "  to  " + semester.getGradeEntryEnd());
    }

    private void setButtonsDisabled(boolean disabled) {
        saveDraftButton.setDisable(disabled);
        publishButton.setDisable(disabled);
        submitButton.setDisable(disabled);
        unlockButton.setDisable(disabled);
        exportButton.setDisable(disabled);
    }

    private void updateStats() {
        // Submit & Lock is shown to the instructor only when EVERY student
        // has the complete required grade set.
        boolean allComplete = !rows.isEmpty() && rows.stream().allMatch(row ->
                row.getMidtermMark() != null
                        && row.getFinalMark() != null
                        && (row.isHasLab()
                            ? row.getLabMark() != null
                            : row.getCourseworkMark() != null));

        boolean showSubmit = !adminMode && allComplete;
        submitButton.setVisible(showSubmit);
        submitButton.setManaged(showSubmit);
        long marked = rows.stream().filter(r -> r.getTotalMark() != null).count();
        long passing = rows.stream()
                .filter(r -> r.getLetterGrade() != null && r.getLetterGrade().isPassing())
                .count();
        long invalid = rows.stream().filter(GradeSheetRow::hasInvalidMark).count();
        statsLabel.setText(rows.size() + " students  •  " + marked + " fully marked  •  "
                + passing + " passing"
                + (invalid == 0 ? "" : "  •  " + invalid + " with a mark above the max — must be corrected"));
    }

    // ------------------------------------------------------------------ actions1

    @FXML
    private void handleRefresh() {
        refresh();
    }

    @FXML
    private void handleSaveDraft() {
        if (!requireSection()) {
            return;
        }
        try {
            gradeService.saveDraft(sectionId, rows, actingUserId());
            AlertUtil.success("Draft saved",
                    "The marks were saved. Nothing has been sent to the students yet — press "
                    + "\"Submit and Lock\" when you are finished.");
            refresh();
        } catch (ValidationException e) {
            AlertUtil.error("Cannot save", e.getMessage());
        } catch (ServiceException e) {
            AlertUtil.error("Cannot save", "The marks could not be saved. Please try again.", e);
        }
    }

    /** Releases only the Midterm mark to the students on this sheet -- Lab/Coursework, Final and
     *  the overall Total/Letter/Points stay hidden until "Submit and Lock". */
    @FXML
    private void handlePublish() {
        if (!requireSection()) {
            return;
        }
        try {
            gradeService.publishComponents(sectionId, rows, actingUserId());
            AlertUtil.success("Midterm published",
                    "The Midterm mark currently entered has been released to each student. Lab, "
                    + "Final, and the overall Total/Letter/Points stay hidden until you press "
                    + "\"Submit and Lock\".");
            refresh();
        } catch (ValidationException e) {
            AlertUtil.error("Cannot publish", e.getMessage());
        } catch (ServiceException e) {
            AlertUtil.error("Cannot publish", "The marks could not be published. Please try again.", e);
        }
    }

    @FXML
    private void handleSubmit() {
        if (!requireSection()) {
            return;
        }
        boolean confirmed = AlertUtil.confirm("Submit and lock",
                "Submitting will:\n"
                + "  • lock these grades so you can no longer change them\n"
                + "  • mark every enrollment as COMPLETED\n"
                + "  • recalculate each student's GPA and academic standing\n"
                + "  • notify every student\n\n"
                + "Only the registrar can change a grade afterwards. Continue?");
        if (!confirmed) {
            return;
        }
        try {
            gradeService.submitSection(sectionId, rows, actingUserId());
            AlertUtil.success("Grades submitted",
                    "The grades are locked and every student's GPA has been recalculated.");
            refresh();
        } catch (ValidationException e) {
            AlertUtil.error("Cannot submit", e.getMessage());
        } catch (ServiceException e) {
            AlertUtil.error("Cannot submit", "The grades could not be submitted. Please try again.", e);
        }
    }

    /** Admin-only: reopens a Submit-&amp;-Locked section so the instructor can edit it normally
     *  again. The only registrar action on this screen. */
    @FXML
    private void handleUnlock() {
        if (!requireSection()) {
            return;
        }
        if (!AlertUtil.confirm("Unlock grades",
                "Unlock " + sectionTitle + " for editing?\n\n"
                + "The instructor will be able to change every grade in this section again, and "
                + "must press \"Submit and Lock\" once more when the corrections are finished.")) {
            return;
        }
        try {
            gradeService.unlockSection(sectionId, actingUserId());
            AlertUtil.success("Section unlocked", "The instructor can now edit this section's grades again.");
            refresh();
        } catch (ValidationException e) {
            AlertUtil.error("Cannot unlock", e.getMessage());
        } catch (ServiceException e) {
            AlertUtil.error("Cannot unlock", "The section could not be unlocked. Please try again.", e);
        }
    }

    /**22
     * Section 2.2 — "Export their own section's grade sheet to CSV".
     */
    @FXML
    private void handleExportCsv() {
        if (rows.isEmpty()) {
            AlertUtil.warn("Nothing to export", "There are no students on this grade sheet.");
            return;
        }
        File file = CsvExporter.exportTable(gradeTable, gradeTable.getScene().getWindow(),
                "grades_" + sectionTitle.replaceAll("[^A-Za-z0-9_-]", "_"));
        if (file != null) {
            AlertUtil.success("Exported", "The grade sheet was saved to:\n" + file.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean requireSection() {
        if (sectionId > 0) {
            return true;
        }
        AlertUtil.error("Select a section", "Choose one of your sections first.");
        return false;
    }

    private int actingUserId() {
        return Session.current().getUser().getUserId();
    }

    /**
     * Blank means "not marked yet", never zero; anything unparseable is refused rather than
     * thrown, so a typo in a cell cannot take the screen down.
     */
    private TableCell<GradeSheetRow, BigDecimal> editableMarkCell(StringConverter<BigDecimal> converter,
                                                                    Function<GradeSheetRow, BigDecimal> maxMarkOf) {
        return new TableCell<>() {
            private final TextField editor = new TextField();

            {
                editor.setOnAction(event -> commitEditorValue());

                editor.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
                    if (wasFocused && !isFocused && isEditing()) {
                        commitEditorValue();
                    }
                });

                editor.setOnKeyPressed(event -> {
                    if (event.getCode() == KeyCode.ESCAPE) {
                        cancelEdit();
                        event.consume();
                    }
                });
            }

            private void commitEditorValue() {
                String text = editor.getText();

                if (text == null || text.isBlank()) {
                    commitEdit(null);
                    return;
                }

                BigDecimal value = converter.fromString(text);
                if (value != null) {
                    commitEdit(value);
                } else {
                    cancelEdit();
                }
            }

            @Override
            public void startEdit() {
                GradeSheetRow row = getTableRow() == null ? null : getTableRow().getItem();
                if (!isEditable()
                        || !getTableView().isEditable()
                        || !getTableColumn().isEditable()
                        || (row != null && row.isSubmitted())) {
                    return;
                }

                super.startEdit();
                editor.setText(converter.toString(getItem()));
                setText(null);
                setGraphic(editor);
                editor.requestFocus();
                editor.selectAll();
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                setGraphic(null);
                setText(converter.toString(getItem()));
            }

            @Override
            protected void updateItem(BigDecimal value, boolean empty) {
                super.updateItem(value, empty);

                getStyleClass().remove("mark-invalid");
                if (empty) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                GradeSheetRow row = getTableRow() == null ? null : getTableRow().getItem();
                if (row != null && value != null
                        && !GradeCalculator.isValidMark(value, maxMarkOf.apply(row))) {
                    getStyleClass().add("mark-invalid");
                }

                if (isEditing()) {
                    editor.setText(converter.toString(value));
                    setText(null);
                    setGraphic(editor);
                } else {
                    setGraphic(null);
                    setText(converter.toString(value));
                }
            }
        };
    }
    private StringConverter<BigDecimal> markConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(BigDecimal value) {
                return value == null ? "" : value.toPlainString();
            }

            @Override
            public BigDecimal fromString(String text) {
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return new BigDecimal(text.trim());
                } catch (NumberFormatException e) {
                    AlertUtil.error("Invalid mark", "\"" + text.trim() + "\" is not a number.");
                    return null;
                }
            }
        };
    }

    private TableCell<GradeSheetRow, BigDecimal> plainDecimalCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value.toPlainString());
            }
        };
    }

}






