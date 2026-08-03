package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.Session;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** Placeholder. Grades and GPA arrive in Phase 11. */
public class StudentDashboardController {

    @FXML private Label welcomeLabel;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        welcomeLabel.setText("Welcome, " + Session.current().getDisplayName() + ".");
    }
}
