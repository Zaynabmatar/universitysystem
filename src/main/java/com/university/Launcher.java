package com.university;

import javafx.application.Application;

/**
 * The one and only entry point.
 *
 * <p>This class deliberately does <em>not</em> extend {@link Application}. When
 * the {@code <mainClass>} is an {@code Application} subclass, the launcher
 * checks for the JavaFX modules up front and refuses to start with "JavaFX
 * runtime components are missing" — the single most common way these projects
 * die on the day of the demonstration. Going through a plain class sidesteps
 * that check entirely.</p>
 *
 * <p>project_details.md Section 8, CRITICAL BUILD RULE.</p>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
