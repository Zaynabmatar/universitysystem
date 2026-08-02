package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.Session;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** Placeholder. Sections and grade entry arrive in Phase 11. */
public class InstructorDashboardController {

    @FXML private Label welcomeLabel;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.INSTRUCTOR);
        welcomeLabel.setText("Welcome, " + Session.current().getDisplayName() + ".");
    }
}
