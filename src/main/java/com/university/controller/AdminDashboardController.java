package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.ReportService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Label;

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

            ChartUtil.fillBar(deptChart, deptEmpty, reportService.enrollmentPerDepartment(), "Enrollments");
            ChartUtil.fillPie(gradeChart, gradeEmpty, reportService.gradeDistribution());
            ChartUtil.fillLine(trendChart, trendEmpty, reportService.enrollmentTrend(), "Enrollments");
        } catch (RuntimeException e) {
            AlertUtil.error("Dashboard",
                    "The dashboard could not be loaded. Check that SQL Server is running, then press Refresh.");
        }
    }
}
