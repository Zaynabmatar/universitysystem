package com.university.controller;

import com.university.enums.UserRole;
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
import javafx.scene.layout.Pane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

/**
 * The sign-in screen, and the very first screen the application shows.
 *
 * <p>There is no separate role-selection step: the ID typed is not scoped to
 * a role ahead of time, so sign-in tries each role in turn (Admin, then
 * Instructor, then Student) against the same ID and password — see
 * {@link #attemptLogin()}.</p>
 *
 * <p>The two "please enter your…" checks happen here rather than in the service
 * so the wording is a prompt rather than a complaint. Everything else — the
 * password itself, whether the account is active — is the service's business,
 * and its message is shown exactly as it comes back.</p>
 */
public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordTextField;
    @FXML private SVGPath eyeIcon;
    @FXML private Label errorLabel;
    @FXML private Button loginButton;
    @FXML private Pane dotsTopRight;
    @FXML private Pane dotsBottomLeft;

    private static final String EYE_OPEN =
            "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zm0 12.5c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z";
    private static final String EYE_CLOSED =
            "M12 6.5c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.44-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16c.57-.23 1.18-.36 1.82-.36zM2.71 3.16 1.29 4.58 3.7 7c-1.68 1.31-3 3.03-3.71 5 1.73 4.39 6 7.5 11 7.5 1.79 0 3.47-.41 4.97-1.14l2.54 2.54 1.41-1.41L2.71 3.16zM12 16.5c-2.76 0-5-2.24-5-5 0-.77.18-1.5.49-2.15l1.57 1.57c-.03.19-.06.38-.06.58 0 1.66 1.34 3 3 3 .2 0 .38-.03.57-.07l1.57 1.57c-.65.32-1.37.5-2.14.5zm2.97-5.33-3.64-3.64.02-.02c1.66 0 3 1.34 3 3 0 .02-.01.03-.01.05z";

    private final AuthService authService = new AuthService();

    @FXML
    private void initialize() {
        Platform.runLater(() -> usernameField.requestFocus());
        usernameField.textProperty().addListener((o, old, val) -> {
            if (!val.matches("\\d*")) {
                usernameField.setText(val.replaceAll("\\D", ""));
            }
        });
        usernameField.textProperty().addListener((o, a, b) -> hideError());
        passwordField.textProperty().addListener((o, a, b) -> hideError());
        // Enter in the password field submits.
        passwordField.setOnAction(e -> handleLogin());
        passwordTextField.setOnAction(e -> handleLogin());

        // The visible/hidden fields share one value; only one is shown at a time.
        passwordTextField.textProperty().bindBidirectional(passwordField.textProperty());

        // Decorative dotted halftone corners — dots fade out from the near screen corner.
        buildHalftone(dotsTopRight, dotsTopRight.getPrefWidth(), 0);
        buildHalftone(dotsBottomLeft, 0, dotsBottomLeft.getPrefHeight());
    }

    private void buildHalftone(Pane pane, double anchorX, double anchorY) {
        double width = pane.getPrefWidth();
        double height = pane.getPrefHeight();
        double spacing = 15;
        double maxDist = Math.hypot(width, height);

        for (double y = 6; y < height; y += spacing) {
            for (double x = 6; x < width; x += spacing) {
                double dist = Math.hypot(x - anchorX, y - anchorY);
                double opacity = 1 - dist / (maxDist * 0.9);
                if (opacity <= 0.03) {
                    continue;
                }
                Circle dot = new Circle(x, y, 1.4);
                dot.getStyleClass().add("halftone-dot");
                dot.setOpacity(opacity);
                pane.getChildren().add(dot);
            }
        }
    }

    @FXML
    private void togglePasswordVisibility() {
        boolean showingText = passwordTextField.isVisible();
        passwordTextField.setVisible(!showingText);
        passwordTextField.setManaged(!showingText);
        passwordField.setVisible(showingText);
        passwordField.setManaged(showingText);
        eyeIcon.setContent(showingText ? EYE_CLOSED : EYE_OPEN);

        TextField nowVisible = showingText ? passwordField : passwordTextField;
        nowVisible.requestFocus();
        nowVisible.positionCaret(nowVisible.getText().length());
    }

    @FXML
    private void handleLogin() {
        hideError();

        if (ValidationUtil.isBlank(usernameField.getText())) {
            showError("Please enter your ID.");
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
            String failureMessage = attemptLogin();
            if (failureMessage != null) {
                showError(failureMessage);
            }
        } finally {
            loginButton.setDisable(false);
        }
    }

    /**
     * Tries the ID and password against each role in turn (Admin, then
     * Instructor, then Student) since there is no longer a role-selection
     * step to scope the attempt ahead of time. Each role's ID sequence is
     * independent, so at most one of the three can ever match.
     *
     * @return null on success (the session is already open and the main
     *         shell already shown), otherwise the message to display
     */
    private String attemptLogin() {
        String id = usernameField.getText();
        String password = passwordField.getText();

        ServiceException genericFailure = null;
        for (UserRole role : UserRole.values()) {
            try {
                authService.login(role, id, password);
                passwordField.clear();

                String username = Session.current().getUser().getUsername();
                SceneManager.getInstance().switchRoot("main_shell.fxml",
                        "University Registration System — " + username);
                return null;

            } catch (ServiceException se) {
                // Covers ValidationException too — it extends ServiceException.
                // A generic "wrong ID or password" just means this role's table
                // didn't have a match — try the next role. Anything else (e.g.
                // "account deactivated") means the ID matched this role, so it
                // is the real answer — stop and show it.
                if (AuthService.SIGN_IN_FAILED.equals(se.getMessage())) {
                    genericFailure = se;
                    continue;
                }
                return se.getMessage();
            } catch (Exception ex) {
                AlertUtil.error("Login failed",
                        "Something went wrong while signing in. Please try again.", ex);
                return null;
            }
        }
        return genericFailure != null ? genericFailure.getMessage() : AuthService.SIGN_IN_FAILED;
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
