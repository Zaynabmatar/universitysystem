package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.ReportService;
import com.university.service.ReportService.CourseStat;
import com.university.service.ReportService.ProbationRow;
import com.university.service.ReportService.SectionFill;
import com.university.service.ReportService.Slice;
import com.university.service.ReportService.TopGpaRow;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.CsvExporter;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Window;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * project_details.md Section 10, rows 2 to 8 — one tab per report, each exportable to CSV.
 *
 * <p>The three TABLES are the ones Section 10 requires to export. Every tab has its own
 * Export CSV button anyway — {@link CsvExporter} makes it a two-line job, and it is a good
 * thing to be able to hand the professor a file for any of the seven reports.</p>
 */
public class AdminReportsController {

    // charts
    @FXML private BarChart<String, Number> deptChart;
    @FXML private BarChart<String, Number> passChart;
    @FXML private PieChart gradeChart;
    @FXML private LineChart<String, Number> trendChart;
    @FXML private Label deptEmpty;
    @FXML private Label gradeEmpty;
    @FXML private Label passEmpty;
    @FXML private Label trendEmpty;
    @FXML private Label subtitleLabel;
    @FXML private Label deptNote;
    @FXML private Label fillNote;
    @FXML private Label probNote;

    // section fill rate table
    @FXML private TableView<SectionFill> fillTable;
    @FXML private TableColumn<SectionFill, String> fillCourse;
    @FXML private TableColumn<SectionFill, String> fillTitle;
    @FXML private TableColumn<SectionFill, String> fillSection;
    @FXML private TableColumn<SectionFill, String> fillInstructor;
    @FXML private TableColumn<SectionFill, String> fillRoom;
    @FXML private TableColumn<SectionFill, Number> fillEnrolled;
    @FXML private TableColumn<SectionFill, Number> fillCapacity;
    @FXML private TableColumn<SectionFill, Number> fillSeats;
    @FXML private TableColumn<SectionFill, Double> fillPercent;

    // probation table
    @FXML private TableView<ProbationRow> probationTable;
    @FXML private TableColumn<ProbationRow, String> probNumber;
    @FXML private TableColumn<ProbationRow, String> probName;
    @FXML private TableColumn<ProbationRow, String> probProgram;
    @FXML private TableColumn<ProbationRow, String> probStanding;
    @FXML private TableColumn<ProbationRow, String> probEmail;
    @FXML private TableColumn<ProbationRow, Number> probCredits;
    @FXML private TableColumn<ProbationRow, Number> probCount;
    @FXML private TableColumn<ProbationRow, BigDecimal> probGpa;

    // top 10 table
    @FXML private TableView<TopGpaRow> topTable;
    @FXML private TableColumn<TopGpaRow, String> topNumber;
    @FXML private TableColumn<TopGpaRow, String> topName;
    @FXML private TableColumn<TopGpaRow, String> topProgram;
    @FXML private TableColumn<TopGpaRow, String> topStanding;
    @FXML private TableColumn<TopGpaRow, Number> topRank;
    @FXML private TableColumn<TopGpaRow, Number> topCredits;
    @FXML private TableColumn<TopGpaRow, BigDecimal> topGpa;

    @FXML private javafx.scene.control.TabPane tabs;

    private final ReportService reportService = new ReportService();

    private final ObservableList<SectionFill> fillRows = FXCollections.observableArrayList();
    private final ObservableList<ProbationRow> probRows = FXCollections.observableArrayList();
    private final ObservableList<TopGpaRow> topRows = FXCollections.observableArrayList();

    // the chart data is kept so the Export buttons can write it without a second query
    private List<Slice> deptData = List.of();
    private List<Slice> gradeData = List.of();
    private List<CourseStat> passData = List.of();
    private List<Slice> trendData = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);

        wireFillTable();
        wireProbationTable();
        wireTopTable();

        handleRefresh();

        tabs.getSelectionModel()
                .selectedItemProperty()
                .addListener((obs, oldTab, newTab) -> {
                    if (newTab != null) {
                        animateSelectedChart(newTab.getText());
                    }
                });
    }

    @FXML
    private void handleRefresh() {
        try {
            deptData = reportService.enrollmentPerDepartment();
            gradeData = reportService.gradeDistribution();
            passData = reportService.passRatePerCourse(15);
            trendData = reportService.enrollmentTrend();


            ChartUtil.fillPie(gradeChart, gradeEmpty, gradeData);



            fillRows.setAll(reportService.sectionFillRates());
            probRows.setAll(reportService.studentsOnProbation());
            topRows.setAll(reportService.topStudentsByGpa(10));

            javafx.application.Platform.runLater(() -> {
                if (tabs.getSelectionModel().getSelectedItem() != null) {
                    animateSelectedChart(
                            tabs.getSelectionModel()
                                    .getSelectedItem()
                                    .getText()
                    );
                }
            });

            ReportService.Kpis k = reportService.getKpis();
            subtitleLabel.setText("Current semester: " + k.currentSemester
                    + "   •   all seven reports from Section 10 of the specification");
            deptNote.setText("Counted by the department that owns the course, not the student's program.");
            fillNote.setText(fillRows.size() + " sections in the current semester");
            probNote.setText(probRows.size() + " students need attention");
        } catch (RuntimeException e) {
            AlertUtil.error("Reports",
                    "The reports could not be loaded. Check that SQL Server is running, then press \"Refresh all\".");
        }
    }

    // =====================================================================
    // tables
    // =====================================================================

    private void wireFillTable() {
        fillCourse.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().courseCode));
        fillTitle.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().courseTitle));
        fillSection.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().sectionNumber));
        fillInstructor.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().instructorName));
        fillRoom.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().room));
        fillEnrolled.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().enrolled));
        fillCapacity.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().capacity));
        fillSeats.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().seatsAvailable));
        fillPercent.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().fillPercent));

        // fill_rate_percent arrives as 0..100 but ProgressBar wants 0..1 — hence /100.
        fillPercent.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(0);

            @Override
            protected void updateItem(Double pct, boolean empty) {
                super.updateItem(pct, empty);
                if (empty || pct == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                bar.setProgress(Math.max(0, Math.min(1, pct / 100.0)));
                bar.setPrefWidth(90);
                bar.getStyleClass().removeAll("bar-full", "bar-low");
                if (pct >= 100) {
                    bar.getStyleClass().add("bar-full");
                } else if (pct < 40) {
                    bar.getStyleClass().add("bar-low");
                }
                setGraphic(bar);
                setText(String.format(" %.0f%%", pct));
                setContentDisplay(ContentDisplay.LEFT);
            }
        });

        fillTable.setItems(fillRows);
        fillTable.setPlaceholder(new Label("There are no sections in the current semester."));
    }

    private void wireProbationTable() {
        probNumber.setCellValueFactory(cd -> new SimpleStringProperty(String.valueOf(cd.getValue().studentUserId)));
        probName.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().studentName));
        probProgram.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().programName));
        probGpa.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().gpa));
        probCredits.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().completedCredits));
        probCount.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().probationCount));
        probStanding.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().standing));
        probEmail.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().email));

        probGpa.setCellFactory(col -> plainDecimalCell());

        probStanding.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                getStyleClass().remove("standing-bad");
                setText(empty ? null : v);
                if (!empty && v != null) {
                    getStyleClass().add("standing-bad");
                }
            }
        });

        probationTable.setItems(probRows);
        probationTable.setPlaceholder(new Label("No student is on probation. That is good news."));
    }

    private void wireTopTable() {
        topRank.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().rank));
        topNumber.setCellValueFactory(cd -> new SimpleStringProperty(String.valueOf(cd.getValue().studentUserId)));
        topName.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().studentName));
        topProgram.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().programName));
        topGpa.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().gpa));
        topCredits.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().completedCredits));
        topStanding.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().standing));

        topGpa.setCellFactory(col -> plainDecimalCell());

        topTable.setItems(topRows);
        topTable.setPlaceholder(new Label("No student has completed a course yet."));
    }

    private <S> TableCell<S, BigDecimal> plainDecimalCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value.toPlainString());
            }
        };
    }

    // =====================================================================
    // charts — every one clears first and explains itself when empty
    // (context/CHART_RULES.md)
    // =====================================================================

    private void fillPassRate() {
        passChart.getData().clear();
        boolean hasData = !passData.isEmpty();
        if (hasData) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName("Pass rate %");
            for (CourseStat s : passData) {
                double v = (s.passRatePercent == null ? 0.0 : s.passRatePercent);
                series.getData().add(new XYChart.Data<>(s.courseCode, v));
            }
            passChart.getData().add(series);
        }
        ChartUtil.showOrExplain(passChart, passEmpty, hasData);
    }

    // =====================================================================
    private void animateSelectedChart(String tabName) {

        if ("Enrollment per Department".equals(tabName)) {

            ChartUtil.fillBar(
                    deptChart,
                    deptEmpty,
                    deptData,
                    "Enrollments"
            );

        } else if ("Pass Rate per Course".equals(tabName)) {

            java.util.List<Slice> passSlices =
                    new java.util.ArrayList<>();

            for (CourseStat s : passData) {
                double value =
                        s.passRatePercent == null
                                ? 0.0
                                : s.passRatePercent;

                passSlices.add(
                        new Slice(s.courseCode, value)
                );
            }

            ChartUtil.fillBar(
                    passChart,
                    passEmpty,
                    passSlices,
                    "Pass rate %"
            );

        } else if ("Enrollment Trend".equals(tabName)) {

            ChartUtil.fillLine(
                    trendChart,
                    trendEmpty,
                    trendData,
                    "Enrollments"
            );
        }
    }

    // CSV exports — one per tab
    // =====================================================================

    @FXML
    private void handleExportDept() {
        List<List<String>> rows = new ArrayList<>();
        for (Slice s : deptData) {
            rows.add(List.of(s.label, String.valueOf((long) s.value)));
        }
        report(CsvExporter.export(window(), "enrollment_per_department",
                List.of("Department", "Enrollments"), rows));
    }

    @FXML
    private void handleExportGrades() {
        List<List<String>> rows = new ArrayList<>();
        for (Slice s : gradeData) {
            rows.add(List.of(s.label, String.valueOf((long) s.value)));
        }
        report(CsvExporter.export(window(), "grade_distribution",
                List.of("Grade band", "Number of grades"), rows));
    }

    /** Exports EVERY course, not only the 15 shown in the chart. */
    @FXML
    private void handleExportPassRate() {
        List<List<String>> rows = new ArrayList<>();
        for (CourseStat s : reportService.passRatePerCourse(0)) {
            rows.add(List.of(
                    CsvExporter.cell(s.courseCode), CsvExporter.cell(s.courseTitle),
                    CsvExporter.cell(s.deptCode), CsvExporter.cell(s.credits),
                    CsvExporter.cell(s.timesTaken), CsvExporter.cell(s.timesPassed),
                    CsvExporter.cell(s.timesFailed),
                    CsvExporter.cell(s.passRatePercent), CsvExporter.cell(s.avgGradePoints)));
        }
        report(CsvExporter.export(window(), "pass_rate_per_course",
                List.of("Course", "Title", "Department", "Credits",
                        "Times taken", "Passed", "Failed", "Pass rate %", "Average points"), rows));
    }

    @FXML
    private void handleExportTrend() {
        List<List<String>> rows = new ArrayList<>();
        for (Slice s : trendData) {
            rows.add(List.of(s.label, String.valueOf((long) s.value)));
        }
        report(CsvExporter.export(window(), "enrollment_trend",
                List.of("Semester", "Enrollments"), rows));
    }

    @FXML
    private void handleExportFill() {
        report(CsvExporter.exportTable(fillTable, window(), "section_fill_rate"));
    }

    @FXML
    private void handleExportProbation() {
        report(CsvExporter.exportTable(probationTable, window(), "students_on_probation"));
    }

    @FXML
    private void handleExportTop() {
        report(CsvExporter.exportTable(topTable, window(), "top_10_by_gpa"));
    }

    private Window window() {
        return fillTable.getScene().getWindow();
    }

    /** null means "cancelled or failed" — never leave the user guessing. */
    private void report(File file) {
        if (file == null) {
            AlertUtil.info("Export", "Nothing was saved. Either you cancelled, or that folder is read-only — try your Desktop.");
        } else {
            AlertUtil.info("Exported", "The report was saved to:\n" + file.getAbsolutePath());
        }
    }
}
