package com.university.controller;

import com.university.service.ReportService.Slice;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;

import java.util.List;

/**
 * The chart-filling helpers shared by {@link AdminDashboardController} and
 * {@link AdminReportsController} — both screens draw the same three chart types from the same
 * {@link Slice} data and must obey the same rule (context/CHART_RULES.md, Phase 14): clear first,
 * and show a friendly sentence instead of a blank box when there is no data.
 */
final class ChartUtil {

    private ChartUtil() {
    }

    static void fillBar(BarChart<String, Number> chart, Label empty, List<Slice> data, String seriesName) {
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

    static void fillPie(PieChart chart, Label empty, List<Slice> data) {
        chart.getData().clear();
        boolean hasData = !data.isEmpty();
        if (hasData) {
            for (Slice s : data) {
                chart.getData().add(new PieChart.Data(s.label + " (" + (int) s.value + ")", s.value));
            }
        }
        showOrExplain(chart, empty, hasData);
        if (hasData) {
            javafx.application.Platform.runLater(() -> {
                javafx.scene.Node legend = chart.lookup(".chart-legend");
                System.err.println("DEBUG chart size=" + chart.getWidth() + "x" + chart.getHeight()
                        + " legend=" + (legend == null ? "null" : legend.getBoundsInParent().getWidth() + "x" + legend.getBoundsInParent().getHeight()));
                if (legend != null) {
                    for (javafx.scene.Node n : ((javafx.scene.layout.Pane) legend).getChildrenUnmodifiable()) {
                        System.err.println("DEBUG item bounds=" + n.getBoundsInParent() + " text=" + (n instanceof Label ? ((Label) n).getText() : n));
                    }
                }
            });
        }
    }

    static void fillLine(LineChart<String, Number> chart, Label empty, List<Slice> data, String seriesName) {
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
    static void showOrExplain(Node chart, Label emptyLabel, boolean hasData) {
        chart.setVisible(hasData);
        chart.setManaged(hasData);
        emptyLabel.setVisible(!hasData);
        emptyLabel.setManaged(!hasData);
    }
}
