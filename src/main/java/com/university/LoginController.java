package com.university;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * Controller for Login.fxml.
 */
public class LoginController {

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private Button loginButton;

    @FXML
    private Label statusLabel;

    @FXML
    private void handleLogin() {
        var username = usernameField.getText().trim();
        var password = passwordField.getText();

        if (username.isEmpty() && password.isEmpty()) {
            statusLabel.setText("Please enter your username and password.");
            return;
        }

        if (username.isEmpty()) {
            statusLabel.setText("Please enter your username.");
            usernameField.requestFocus();
            return;
        }

        if (password.isEmpty()) {
            statusLabel.setText("Please enter your password.");
            passwordField.requestFocus();
            return;
        }

        statusLabel.setText("");
        // Credential verification is not implemented yet.
    }
}
