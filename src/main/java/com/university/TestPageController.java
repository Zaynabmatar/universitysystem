package com.university;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/**
 * Controller for TestPage.fxml.
 */
public class TestPageController {

    @FXML
    private Label titleLabel;

    @FXML
    private Label subtitleLabel;

    @FXML
    private Button continueButton;

    /**
     * Called by the FXML loader once the view is built.
     */
    @FXML
    private void initialize() {
        subtitleLabel.setText("This is a test page.");
    }

    @FXML
    private void handleContinue() {
        subtitleLabel.setText("Continue clicked.");
    }
}
