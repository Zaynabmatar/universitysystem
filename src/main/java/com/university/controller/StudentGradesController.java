package com.university.controller;

import com.university.enums.AcademicStanding;
import com.university.enums.UserRole;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.model.StudentGradeRow;
import com.university.service.AcademicService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.GradeCalculator;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.util.List;

/**
 * My Grades — project_details.md Section 2.3: "See grades, term GPA, cumulative GPA and academic
 * standing". Read-only in every direction; the student may look but never change anything, and a
 * grade the instructor has only saved as a draft is not shown at all (rule G6's other half —
 * marks appear the moment they are submitted, and not before).
 */
public class StudentGradesController {

    /** The whole record rather than one term — the first entry of the semester chooser. */
    private static final Semester ALL_SEMESTERS = null;

    @FXML private ComboBox<Semester> semesterCombo;
    @FXML private Label termGpaCaption;
    @FXML private Label termGpaLabel;
    @FXML private Label cumulativeGpaLabel;
    @FXML private Label standingLabel;
    @FXML private Label creditsLabel;
    @FXML private Label repeatNote;
    @FXML private Label emptyLabel;
    @FXML private TableView<StudentGradeRow> gradesTable;
    @FXML private TableColumn<StudentGradeRow, String> colCourseCode;
    @FXML private TableColumn<StudentGradeRow, String> colCourseTitle;
    @FXML private TableColumn<StudentGradeRow, Number> colCredits;
    @FXML private TableColumn<StudentGradeRow, BigDecimal> colCoursework;
    @FXML private TableColumn<StudentGradeRow, BigDecimal> colMidterm;
    @FXML private TableColumn<StudentGradeRow, BigDecimal> colLab;
    @FXML private TableColumn<StudentGradeRow, BigDecimal> colFinal;
    @FXML private TableColumn<StudentGradeRow, BigDecimal> colTotal;
    @FXML private TableColumn<StudentGradeRow, String> colLetter;
    @FXML private TableColumn<StudentGradeRow, BigDecimal> colPoints;
    @FXML private TableColumn<StudentGradeRow, String> colStatus;

    private final AcademicService academicService = new AcademicService();
    private final ObservableList<StudentGradeRow> rows = FXCollections.observableArrayList();

    private int studentId;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        studentId = Session.current().requireStudentId();

        colCourseCode.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCourseCode()));
        colCourseTitle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCourseTitle()));
        colCredits.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getCredits()));
        colCoursework.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getCourseworkMark()));
        colMidterm.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getMidtermMark()));
        colLab.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getLabMark()));
        colFinal.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getFinalMark()));
        colTotal.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getTotalMark()));
        colPoints.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getGradePoints()));
        colLetter.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getLetterGrade() == null ? null : c.getValue().getLetterGrade().getLabel()));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().statusLabel()));

        // An ungraded row reads "Not graded yet" rather than showing an empty cell, so the
        // student can tell "no mark yet" apart from "a mark of nothing".
        colCoursework.setCellFactory(col -> markCell());
        colMidterm.setCellFactory(col -> markCell());
        colLab.setCellFactory(col -> markCell());
        colFinal.setCellFactory(col -> markCell());
        colTotal.setCellFactory(col -> markCell());
        colPoints.setCellFactory(col -> markCell());
        colCoursework.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal mark, boolean empty) {
                super.updateItem(mark, empty);

                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                    return;
                }

                StudentGradeRow row = (StudentGradeRow) getTableRow().getItem();

                if (row.isHasLab()) {
                    setText("—");
                } else {
                    setText(mark == null ? "Not graded yet" : mark.toPlainString());
                }
            }
        });

        colLab.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal mark, boolean empty) {
                super.updateItem(mark, empty);

                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                    return;
                }

                StudentGradeRow row = (StudentGradeRow) getTableRow().getItem();

                if (!row.isHasLab()) {
                    setText("—");
                } else {
                    setText(mark == null ? "Not graded yet" : mark.toPlainString());
                }
            }
        });

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
                var grade = com.university.enums.LetterGrade.fromDb(letter);
                if (grade.countsInGpa()) {
                    getStyleClass().add(grade.isPassing() ? "grade-pass" : "grade-fail");
                }
            }
        });

        gradesTable.setItems(rows);
        gradesTable.setEditable(false); // read-only, everywhere, always
        gradesTable.setPlaceholder(new Label("You have no enrollments yet."));

        semesterCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Semester semester) {
                return semester == null ? "All semesters" : semester.getSemesterName();
            }

            @Override
            public Semester fromString(String text) {
                return null;
            }
        });

        loadSemesters();
        reloadRows();
        semesterCombo.valueProperty().addListener((obs, old, chosen) -> reloadRows());
    }

    @FXML
    private void handleRefresh() {
        loadSemesters();
        reloadRows();
    }

    private void loadSemesters() {
        try {
            List<Semester> studied = academicService.semestersStudied(studentId);
            ObservableList<Semester> choices = FXCollections.observableArrayList();
            choices.add(ALL_SEMESTERS); // "All semesters"
            choices.addAll(studied);

            Semester previous = semesterCombo.getValue();
            semesterCombo.setItems(choices);
            if (previous != null && studied.stream().anyMatch(s -> s.getSemesterId() == previous.getSemesterId())) {
                semesterCombo.setValue(previous);
            } else {
                // Default to the newest semester the student actually studied in.
                semesterCombo.setValue(studied.isEmpty() ? ALL_SEMESTERS : studied.get(0));
            }
        } catch (RuntimeException e) {
            AlertUtil.error("My Grades", "Your semesters could not be loaded.", e);
        }
    }

    private void reloadRows() {
        try {
            Semester chosen = semesterCombo.getValue();
            Integer semesterId = chosen == null ? null : chosen.getSemesterId();
            rows.setAll(academicService.gradeRows(studentId, semesterId));

            boolean anyRepeated = rows.stream().anyMatch(row -> row.isRepeated());
            repeatNote.setVisible(anyRepeated);
            repeatNote.setManaged(anyRepeated);

            boolean empty = rows.isEmpty();
            emptyLabel.setVisible(empty);
            emptyLabel.setManaged(empty);

            updateKpis(semesterId);
        } catch (RuntimeException e) {
            rows.clear();
            AlertUtil.error("My Grades", "Your grades could not be loaded.", e);
        }
    }

    /**
     * The four figures beside the table. The cumulative average, the standing and the credits are
     * read from the student's stored record, which {@code AcademicService} rewrites whenever a
     * grade is submitted — the screen shows the same number the registration rules use.
     */
    private void updateKpis(Integer semesterId) {
        Student student = academicService.academicRecordOf(studentId);

        BigDecimal term = semesterId == null
                ? academicService.currentGpa(studentId)
                : academicService.termGpa(studentId, semesterId);
        termGpaLabel.setText(GradeCalculator.formatGpa(term));
        cumulativeGpaLabel.setText(GradeCalculator.formatGpa(student.getCumulativeGpa()));
        creditsLabel.setText(String.valueOf(student.getCompletedCredits()));

        AcademicStanding standing = student.getAcademicStanding();
        standingLabel.setText(standing == null ? "—" : standing.getLabel());
        standingLabel.getStyleClass().removeAll("standing-good", "standing-bad");
        if (standing != null) {
            boolean good = standing == AcademicStanding.GOOD
                    || standing == AcademicStanding.DEANS_LIST
                    || standing == AcademicStanding.NEW;
            standingLabel.getStyleClass().add(good ? "standing-good" : "standing-bad");
        }

        termGpaCaption.setText(semesterId == null ? "GPA (all semesters)" : "Term GPA");
    }

    /** Blank marks read "Not graded yet" — the draft a student must never see. */
    private TableCell<StudentGradeRow, BigDecimal> markCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().remove("muted-text");
                if (empty) {
                    setText(null);
                    return;
                }
                if (value == null) {
                    setText("Not graded yet");
                    getStyleClass().add("muted-text");
                    return;
                }
                setText(value.toPlainString());
            }
        };
    }
}

