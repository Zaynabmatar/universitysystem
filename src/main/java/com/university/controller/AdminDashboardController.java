package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.ReportService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.Async;
import com.university.util.SceneManager;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * project_details.md Section 10, row 1 — the KPI cards — plus three summary charts. The
 * complete set of seven reports lives on {@code admin_reports.fxml}; this is the landing page
 * an administrator sees immediately after logging in.
 *
 * <p>The KPI/chart queries run on a background thread ({@link Async}) so this screen's own FXML
 * (shell, empty charts) paints immediately after login instead of blocking on SQL Server; the
 * fetched data is applied back on the FX thread once it arrives.</p>
 */
public class AdminDashboardController {

    @FXML private Label semesterLabel;
    @FXML private Label secondaryLabel;
    @FXML private Label kpiStudents;
    @FXML private Label kpiInstructors;
    @FXML private Label kpiCourses;
    @FXML private Label kpiSections;
    @FXML private Label kpiAvgGpa;
    @FXML private BarChart<String, Number> deptChart;
    @FXML private PieChart gradeChart;
    @FXML private LineChart<String, Number> trendChart;
    @FXML private Label deptEmpty;
    @FXML private Label gradeEmpty;
    @FXML private Label trendEmpty;

    @FXML private AIAssistantPanelController aiAssistantController;
    @FXML private Button aiAssistantToggleButton;
    @FXML private VBox   aiAssistantExpandedBox;

    private final ReportService reportService = new ReportService();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);
        load();

        aiAssistantController.configure(
                "Hi! I'm the University AI Assistant. Ask me about programs, courses, instructors, "
                        + "students, the academic calendar, or university policies — or use one of the "
                        + "quick questions above.",
                List.of("How many active students are there?", "What courses are in the catalogue?",
                        "What is the current semester?", "Show department overview"));
    }

    @FXML
    private void handleRefresh() {
        load();
    }

    @FXML
    private void handleOpenReports() {
        SceneManager.getInstance().navigateTo("admin_reports.fxml", "Reports & Analytics");
    }

    /**
     * The AI panel starts collapsed to a single top-right button and reserves no layout space;
     * this toggles it open — a vertical panel overlaying the dashboard, anchored below that
     * button — or closed. The dashboard beneath never resizes or reflows either way.
     */
    @FXML
    private void handleToggleAiAssistant() {
        boolean expand = !aiAssistantExpandedBox.isVisible();
        aiAssistantExpandedBox.setVisible(expand);
        aiAssistantExpandedBox.setManaged(expand);
        aiAssistantToggleButton.setVisible(!expand);
        aiAssistantToggleButton.setManaged(!expand);
    }

    /** Everything {@link #load()} needs from the database, fetched together off the FX thread. */
    private record DashboardData(ReportService.Kpis kpis, List<ReportService.Slice> byDept,
                                  List<ReportService.Slice> byGrade, List<ReportService.Slice> trend) {
    }

    private void load() {
        Async.run(
                () -> new DashboardData(reportService.getKpis(), reportService.enrollmentPerDepartment(),
                        reportService.gradeDistribution(), reportService.enrollmentTrend()),
                this::applyDashboardData,
                error -> AlertUtil.error("Dashboard",
                        "The dashboard could not be loaded. Check that SQL Server is running, then press Refresh."));
    }

    private void applyDashboardData(DashboardData data) {
        ReportService.Kpis k = data.kpis();
        kpiStudents.setText(String.valueOf(k.totalStudents));
        kpiInstructors.setText(String.valueOf(k.totalInstructors));
        kpiCourses.setText(String.valueOf(k.totalCourses));
        kpiSections.setText(String.valueOf(k.activeSections));
        kpiAvgGpa.setText(k.averageGpa == null ? "—" : String.format("%.2f", k.averageGpa));
        semesterLabel.setText("Current semester: " + k.currentSemester);
        secondaryLabel.setText(k.enrollmentsThisSemester + " enrollments this semester   •   "
                + k.studentsOnProbation + " students on probation");

        ChartUtil.fillBar(deptChart, deptEmpty, data.byDept(), "Enrollments");
        ChartUtil.fillPie(gradeChart, gradeEmpty, data.byGrade());
        ChartUtil.fillLine(trendChart, trendEmpty, data.trend(), "Enrollments");
    }
}
