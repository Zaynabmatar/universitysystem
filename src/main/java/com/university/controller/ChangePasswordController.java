package com.university.controller;

import com.university.service.AuthService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import com.university.util.ValidationUtil;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.shape.SVGPath;

/**
 * Lets the signed-in user (any role) change their own password.
 * {@link AuthService#changePassword} already does the real work — verifying
 * the current password and hashing the new one — this screen only collects
 * and validates the three fields around that one call.
 */
public class ChangePasswordController implements ReturnNavigable {

    @FXML private PasswordField currentPasswordField;
    @FXML private TextField currentPasswordTextField;
    @FXML private SVGPath currentEyeIcon;

    @FXML private PasswordField newPasswordField;
    @FXML private TextField newPasswordTextField;
    @FXML private SVGPath newEyeIcon;

    @FXML private PasswordField confirmPasswordField;
    @FXML private TextField confirmPasswordTextField;
    @FXML private SVGPath confirmEyeIcon;

    @FXML private Label errorLabel;

    private static final String EYE_OPEN =
            "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zm0 12.5c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z";
    private static final String EYE_CLOSED =
            "M12 6.5c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.44-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16c.57-.23 1.18-.36 1.82-.36zM2.71 3.16 1.29 4.58 3.7 7c-1.68 1.31-3 3.03-3.71 5 1.73 4.39 6 7.5 11 7.5 1.79 0 3.47-.41 4.97-1.14l2.54 2.54 1.41-1.41L2.71 3.16zM12 16.5c-2.76 0-5-2.24-5-5 0-.77.18-1.5.49-2.15l1.57 1.57c-.03.19-.06.38-.06.58 0 1.66 1.34 3 3 3 .2 0 .38-.03.57-.07l1.57 1.57c-.65.32-1.37.5-2.14.5zm2.97-5.33-3.64-3.64.02-.02c1.66 0 3 1.34 3 3 0 .02-.01.03-.01.05z";

    private final AuthService authService = new AuthService();

    private String returnFxml;
    private String returnTitle;

    @Override
    public void setReturnTarget(String fxml, String title) {
        this.returnFxml = fxml;
        this.returnTitle = title;
    }

    @FXML
    private void initialize() {
        currentPasswordTextField.textProperty().bindBidirectional(currentPasswordField.textProperty());
        newPasswordTextField.textProperty().bindBidirectional(newPasswordField.textProperty());
        confirmPasswordTextField.textProperty().bindBidirectional(confirmPasswordField.textProperty());
    }

    @FXML
    private void handleBack() {
        SceneManager.getInstance().navigateTo(returnFxml, returnTitle);
    }

    @FXML
    private void toggleCurrentVisibility() {
        toggle(currentPasswordField, currentPasswordTextField, currentEyeIcon);
    }

    @FXML
    private void toggleNewVisibility() {
        toggle(newPasswordField, newPasswordTextField, newEyeIcon);
    }

    @FXML
    private void toggleConfirmVisibility() {
        toggle(confirmPasswordField, confirmPasswordTextField, confirmEyeIcon);
    }

    private void toggle(PasswordField passwordField, TextField textField, SVGPath eyeIcon) {
        boolean showingText = textField.isVisible();
        textField.setVisible(!showingText);
        textField.setManaged(!showingText);
        passwordField.setVisible(showingText);
        passwordField.setManaged(showingText);
        eyeIcon.setContent(showingText ? EYE_CLOSED : EYE_OPEN);

        TextField nowVisible = showingText ? passwordField : textField;
        nowVisible.requestFocus();
        nowVisible.positionCaret(nowVisible.getText().length());
    }

    @FXML
    private void handleUpdatePassword() {
        hideError();

        String current = currentPasswordField.getText();
        String updated = newPasswordField.getText();
        String confirm = confirmPasswordField.getText();

        if (ValidationUtil.isBlank(current)) {
            showError("Please enter your current password.");
            return;
        }
        if (ValidationUtil.isBlank(updated)) {
            showError("Please enter a new password.");
            return;
        }
        if (!updated.equals(confirm)) {
            showError("New password and confirmation password do not match.");
            return;
        }

        try {
            int userId = Session.current().getUser().getUserId();
            authService.changePassword(userId, current, updated);

            currentPasswordField.clear();
            newPasswordField.clear();
            confirmPasswordField.clear();
            AlertUtil.success("Password updated", "Your password was changed successfully.");

        } catch (ServiceException se) {
            showError(se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not update password", "Your password could not be updated.", e);
        }
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
