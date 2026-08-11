package com.university.util;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

public final class AnimationUtility {

    private static final Duration PAGE_TRANSITION_DURATION = Duration.millis(280);
    private static final double PAGE_SLIDE_DISTANCE = 18.0;

    private AnimationUtility() {
    }

    public static void playPageEnter(Node node) {
        node.setOpacity(0.0);
        node.setTranslateX(PAGE_SLIDE_DISTANCE);

        FadeTransition fade =
                new FadeTransition(PAGE_TRANSITION_DURATION, node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);

        TranslateTransition slide =
                new TranslateTransition(PAGE_TRANSITION_DURATION, node);
        slide.setFromX(PAGE_SLIDE_DISTANCE);
        slide.setToX(0.0);

        new ParallelTransition(fade, slide).play();
    }

    public static void playDropIn(Node node) {
        Duration duration = Duration.millis(320);

        node.setOpacity(0.0);
        node.setTranslateY(-18.0);

        FadeTransition fade =
                new FadeTransition(duration, node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);

        TranslateTransition slide =
                new TranslateTransition(duration, node);
        slide.setFromY(-18.0);
        slide.setToY(0.0);

        new ParallelTransition(fade, slide).play();
    }

    public static void playBell(Node node, Runnable onFinished) {
        try {
    javafx.scene.media.AudioClip bellSound =
        new javafx.scene.media.AudioClip(
            AnimationUtility.class.getResource("/sounds/bell-reference-fast.wav").toExternalForm()
        );
    bellSound.play();
    javafx.animation.PauseTransition stopSound =
        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
    stopSound.setOnFinished(e -> bellSound.stop());
    stopSound.play();
} catch (Exception ex) {
    ex.printStackTrace();
}
        node.setScaleX(1.65);
        node.setScaleY(1.65);

        RotateTransition shake =
                new RotateTransition(Duration.millis(125), node);

        shake.setFromAngle(-25);
        shake.setToAngle(25);
        shake.setAutoReverse(true);
        shake.setCycleCount(20);

        shake.setOnFinished(e -> {
            node.setRotate(0);
            node.setScaleX(1.0);
            node.setScaleY(1.0);

            if (onFinished != null) {
                onFinished.run();
            }
        });

        shake.play();
    }
}