package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.ReportService;
import com.university.service.ReportService.Slice;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;

import java.util.List;

/**
 * project_details.md Section 10, row 1 — the KPI cards — plus three summary charts. The
 * complete set of seven reports lives on {@code admin_reports.fxml}; this is the landing page
 * an administrator sees immediately after logging in.
 *
 * <p>Everything is loaded synchronously. This screen is a handful of small queries against a
 * database with a few hundred rows; a background {@code Task} would add a class of threading
 * bugs ("Not on FX application thread") for no visible gain (context/CHART_RULES.md).</p>
 */
public class AdminDashboardController {

    @FXML private Label semesterLabel;
    @FXML private Label secondaryLabel;
    @FXML private Label kpiStudents;
    @FXML private Label kpiInstructors;
    @FXML private Label kpiCourses;
    @FXML private Label kpiSections;
    @FXML private BarChart<String, Number> deptChart;
    @FXML private PieChart gradeChart;
    @FXML private LineChart<String, Number> trendChart;
    @FXML private Label deptEmpty;
    @FXML private Label gradeEmpty;
    @FXML private Label trendEmpty;

    private final ReportService reportService = new ReportService();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);
        load();
    }

    @FXML
    private void handleRefresh() {
        load();
    }

    @FXML
    private void handleOpenReports() {
        SceneManager.getInstance().navigateTo("admin_reports.fxml", "Reports & Analytics");
    }

    private void load() {
        try {
            ReportService.Kpis k = reportService.getKpis();
            kpiStudents.setText(String.valueOf(k.totalStudents));
            kpiInstructors.setText(String.valueOf(k.totalInstructors));
            kpiCourses.setText(String.valueOf(k.totalCourses));
            kpiSections.setText(String.valueOf(k.activeSections));
            semesterLabel.setText("Current semester: " + k.currentSemester);
            secondaryLabel.setText(k.enrollmentsThisSemester + " enrollments this semester   •   "
                    + k.studentsWaiting + " students on waitlists   •   "
                    + k.studentsOnProbation + " students on probation");

            fillBar(deptChart, deptEmpty, reportService.enrollmentPerDepartment(), "Enrollments");
            fillPie(gradeChart, gradeEmpty, reportService.gradeDistribution());
            fillLine(trendChart, trendEmpty, reportService.enrollmentTrend(), "Enrollments");
        } catch (RuntimeException e) {
            AlertUtil.error("Dashboard",
                    "The dashboard could not be loaded. Check that SQL Server is running, then press Refresh.");
        }
    }

    // =====================================================================
    // Chart helpers — every one clears first, guards against empty data, and
    // never leaves a blank chart on screen without an explanation.
    // =====================================================================

    private void fillBar(BarChart<String, Number> chart, Label empty, List<Slice> data, String seriesName) {
        chart.getData().clear();
        boolean hasData = data.stream().anyMatch(s -> s.value > 0);
        if (hasData) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(seriesName);
            for (Slice s : data) {
                series.getData().add(new XYChart.Data<>(s.label, s.value));
            }
            chart.getData().add(series);
        }
        showOrExplain(chart, empty, hasData);
    }

    private void fillPie(PieChart chart, Label empty, List<Slice> data) {
        chart.getData().clear();
        boolean hasData = !data.isEmpty();
        if (hasData) {
            for (Slice s : data) {
                chart.getData().add(new PieChart.Data(s.label + " (" + (int) s.value + ")", s.value));
            }
        }
        showOrExplain(chart, empty, hasData);
    }

    private void fillLine(LineChart<String, Number> chart, Label empty, List<Slice> data, String seriesName) {
        chart.getData().clear();
        boolean hasData = !data.isEmpty();
        if (hasData) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(seriesName);
            for (Slice s : data) {
                series.getData().add(new XYChart.Data<>(s.label, s.value));
            }
            chart.getData().add(series);
        }
        showOrExplain(chart, empty, hasData);
    }

    /** Shows the chart when there is data, and a friendly sentence when there is not. */
    private void showOrExplain(Node chart, Label emptyLabel, boolean hasData) {
        chart.setVisible(hasData);
        chart.setManaged(hasData);
        emptyLabel.setVisible(!hasData);
        emptyLabel.setManaged(!hasData);
    }
}
