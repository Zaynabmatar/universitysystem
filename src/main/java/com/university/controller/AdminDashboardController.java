package com.university.controller;

import com.university.enums.UserRole;
import com.university.service.Session;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** Placeholder. The KPI cards and charts arrive in Phase 14. */
public class AdminDashboardController {

    @FXML private Label welcomeLabel;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);
        welcomeLabel.setText("Welcome, " + Session.current().getDisplayName() + ".");
    }
}
