package com.university.controller;

import com.university.service.AuthService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import com.university.util.ValidationUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * The sign-in screen.
 *
 * <p>The two "please enter your…" checks happen here rather than in the service
 * so the wording is a prompt rather than a complaint. Everything else — the
 * password itself, whether the account is active — is the service's business,
 * and its message is shown exactly as it comes back.</p>
 */
public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Button loginButton;

    private final AuthService authService = new AuthService();

    @FXML
    private void initialize() {
        Platform.runLater(() -> usernameField.requestFocus());
        usernameField.textProperty().addListener((o, a, b) -> hideError());
        passwordField.textProperty().addListener((o, a, b) -> hideError());
        // Enter in the password field submits.
        passwordField.setOnAction(e -> handleLogin());
    }

    @FXML
    private void handleLogin() {
        hideError();

        if (ValidationUtil.isBlank(usernameField.getText())) {
            showError("Please enter your username.");
            usernameField.requestFocus();
            return;
        }
        if (ValidationUtil.isBlank(passwordField.getText())) {
            showError("Please enter your password.");
            passwordField.requestFocus();
            return;
        }

        loginButton.setDisable(true);
        try {
            authService.login(usernameField.getText(), passwordField.getText());
            passwordField.clear();

            String username = Session.current().getUser().getUsername();
            SceneManager.getInstance().switchRoot("main_shell.fxml",
                    "University Registration System — " + username);

        } catch (ServiceException se) {
            // Covers ValidationException too — it extends ServiceException. The
            // message is already written for the person reading it.
            showError(se.getMessage());
        } catch (Exception ex) {
            AlertUtil.error("Login failed",
                    "Something went wrong while signing in. Please try again.", ex);
        } finally {
            loginButton.setDisable(false);
        }
    }

    // The usernames the spec lists (instructor1, student1) do not exist in this
    // database; these are the real seeded accounts.
    @FXML private void fillAdmin()      { fill("admin", "Admin@123"); }
    @FXML private void fillInstructor() { fill("a.khoury", "Instructor@123"); }
    @FXML private void fillStudent()    { fill("z.matar", "Student@123"); }

    private void fill(String u, String p) {
        usernameField.setText(u);
        passwordField.setText(p);
        hideError();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
