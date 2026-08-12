package com.university.controller;

import com.university.service.ReportService.Slice;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

final class ChartUtil {

    private ChartUtil() {
    }

    static void fillBar(BarChart<String, Number> chart,
                        Label empty,
                        List<Slice> data,
                        String seriesName) {

        chart.setAnimated(false);
        chart.getData().clear();

        boolean hasData = data.stream().anyMatch(s -> s.value > 0);

        if (hasData) {

            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(seriesName);

            for (Slice s : data) {
                // REAL value is added immediately.
                // This keeps the Y axis completely stable.
                series.getData().add(
                        new XYChart.Data<>(s.label, s.value)
                );
            }

            chart.getData().add(series);

            prepareBarsForScrollAnimation(chart, series);
        }

        showOrExplain(chart, empty, hasData);
    }

    private static void prepareBarsForScrollAnimation(
            BarChart<String, Number> chart,
            XYChart.Series<String, Number> series) {

        final boolean[] played = {false};

        Platform.runLater(() -> {

            chart.applyCss();
            chart.layout();

            List<javafx.scene.transform.Scale> scales =
                    new ArrayList<>();

            for (XYChart.Data<String, Number> data : series.getData()) {

                Node bar = data.getNode();

                if (bar != null) {

                    double bottom =
                            bar.getBoundsInLocal().getMaxY();

                    javafx.scene.transform.Scale scale =
                            new javafx.scene.transform.Scale(
                                    1.0,
                                    0.0,
                                    0.0,
                                    bottom
                            );

                    bar.getTransforms().add(scale);
                    scales.add(scale);
                }
            }

            Runnable play = () -> {

                if (played[0] || chart.getScene() == null) {
                    return;
                }

                var bounds =
                        chart.localToScene(chart.getBoundsInLocal());

                double sceneHeight =
                        chart.getScene().getHeight();

                if (bounds.getMinY() >= 0
                        && bounds.getMaxY() <= sceneHeight) {

                    played[0] = true;

                    Timeline timeline = new Timeline();

                    for (javafx.scene.transform.Scale scale : scales) {

                        timeline.getKeyFrames().add(
                                new KeyFrame(
                                        Duration.ZERO,
                                        new KeyValue(
                                                scale.yProperty(),
                                                0.0
                                        )
                                )
                        );

                        timeline.getKeyFrames().add(
                                new KeyFrame(
                                        Duration.millis(1600),
                                        new KeyValue(
                                                scale.yProperty(),
                                                1.0
                                        )
                                )
                        );
                    }

                    timeline.play();
                }
            };

            Node parent = chart.getParent();

            while (parent != null) {

                if (parent instanceof ScrollPane scrollPane) {
                    scrollPane.vvalueProperty().addListener(
                            (obs, oldValue, newValue) -> play.run()
                    );
                }

                parent = parent.getParent();
            }

            play.run();
        });
    }
    static void fillPie(PieChart chart,
                        Label empty,
                        List<Slice> data) {

        chart.getData().clear();

        boolean hasData = !data.isEmpty();

        if (hasData) {

            for (Slice s : data) {

                chart.getData().add(
                        new PieChart.Data(
                                s.label + " (" + (int) s.value + ")",
                                s.value
                        )
                );
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

    static void fillLine(LineChart<String, Number> chart,
                         Label empty,
                         List<Slice> data,
                         String seriesName) {

        chart.setAnimated(false);
        chart.getData().clear();

        boolean hasData = !data.isEmpty();

        if (hasData) {
            XYChart.Series<String, Number> series =
                    new XYChart.Series<>();

            series.setName(seriesName);

            for (Slice s : data) {
                series.getData().add(
                        new XYChart.Data<>(s.label, s.value)
                );
            }

            chart.getData().add(series);
            animateLineWhenFullyVisible(chart);
        }

        showOrExplain(chart, empty, hasData);
    }

    static void animateLineWhenFullyVisible(
            LineChart<String, Number> chart) {

        final boolean[] played = {false};

        Platform.runLater(() -> {

            chart.setAnimated(false);
            chart.applyCss();
            chart.layout();

            List<Node> lines = new ArrayList<>();
            List<Node> symbols = new ArrayList<>();
            List<Double> startOffsets = new ArrayList<>();

            for (XYChart.Series<String, Number> series : chart.getData()) {

                Node line = series.getNode();

                if (line != null) {
                    double width = Math.max(
                            1.0,
                            line.getBoundsInLocal().getWidth()
                    );

                    line.setScaleX(0.0);
                    line.setTranslateX(-width / 2.0);

                    lines.add(line);
                    startOffsets.add(-width / 2.0);
                }

                for (XYChart.Data<String, Number> point : series.getData()) {
                    if (point.getNode() != null) {
                        point.getNode().setOpacity(0.0);
                        symbols.add(point.getNode());
                    }
                }
            }

            Runnable play = () -> {

                if (played[0] || chart.getScene() == null) {
                    return;
                }

                var chartBounds =
                        chart.localToScene(chart.getBoundsInLocal());

                double top = 0.0;
                double bottom = chart.getScene().getHeight();

                Node parent = chart.getParent();

                while (parent != null) {

                    if (parent instanceof ScrollPane scrollPane) {

                        var scrollBounds =
                                scrollPane.localToScene(
                                        scrollPane.getBoundsInLocal()
                                );

                        top = Math.max(top, scrollBounds.getMinY());
                        bottom = Math.min(bottom, scrollBounds.getMaxY());

                        break;
                    }

                    parent = parent.getParent();
                }

                // Start ONLY when the complete LineChart is visible.
                if (chartBounds.getMinY() >= top
                        && chartBounds.getMaxY() <= bottom) {

                    played[0] = true;

                    Timeline timeline = new Timeline();

                    for (int i = 0; i < lines.size(); i++) {

                        Node line = lines.get(i);

                        timeline.getKeyFrames().add(
                                new KeyFrame(
                                        Duration.ZERO,
                                        new KeyValue(
                                                line.scaleXProperty(), 0.0
                                        ),
                                        new KeyValue(
                                                line.translateXProperty(),
                                                startOffsets.get(i)
                                        )
                                )
                        );

                        timeline.getKeyFrames().add(
                                new KeyFrame(
                                        Duration.millis(1800),
                                        new KeyValue(
                                                line.scaleXProperty(), 1.0
                                        ),
                                        new KeyValue(
                                                line.translateXProperty(), 0.0
                                        )
                                )
                        );
                    }

                    int count = Math.max(1, symbols.size());

                    for (int i = 0; i < symbols.size(); i++) {

                        double time =
                                1800.0 * i / Math.max(1, count - 1);

                        timeline.getKeyFrames().add(
                                new KeyFrame(
                                        Duration.millis(time),
                                        new KeyValue(
                                                symbols.get(i).opacityProperty(),
                                                1.0
                                        )
                                )
                        );
                    }

                    timeline.play();
                }
            };

            Node parent = chart.getParent();

            while (parent != null) {

                if (parent instanceof ScrollPane scrollPane) {
                    scrollPane.vvalueProperty().addListener(
                            (obs, oldValue, newValue) -> play.run()
                    );
                }

                parent = parent.getParent();
            }

            play.run();
        });
    }
    static void showOrExplain(Node chart,
                              Label emptyLabel,
                              boolean hasData) {

        chart.setVisible(hasData);
        chart.setManaged(hasData);

        emptyLabel.setVisible(!hasData);
        emptyLabel.setManaged(!hasData);
    }
}
