package com.university.util;

import javafx.application.Platform;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs slow work (DB/service calls) off the JavaFX Application Thread, then hands the result back
 * on it. Same shape as the one background-thread precedent already in the codebase
 * ({@code AIAssistantPanelController.fetchAssistantReply}) — a daemon {@link Thread} plus a
 * {@link Platform#runLater} hop back — kept here so dashboard screens don't each re-invent it.
 */
public final class Async {

    private Async() {
    }

    public static <T> void run(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Thread worker = new Thread(() -> {
            try {
                T result = work.get();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Throwable t) {
                Platform.runLater(() -> onFailure.accept(t));
            }
        }, "dashboard-load");
        worker.setDaemon(true);
        worker.start();
    }
}
