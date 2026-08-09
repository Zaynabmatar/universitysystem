package com.university.controller;

import com.university.model.Instructor;
import com.university.model.Student;
import com.university.model.User;
import com.university.dao.UserDAO;
import com.university.service.AccountService;
import com.university.service.InstructorService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.service.StudentService;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import com.university.util.ValidationUtil;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/**
 * Shows the signed-in user's own name, id and email, and lets them edit the
 * email. Where that lives (and how it is validated) depends on the role:
 * Student/Instructor already have {@code email} on their own table, reused
 * through {@link StudentService#update}/{@link InstructorService#update};
 * Admin has no profile row of its own, so its email lives directly on
 * {@code dbo.users} and is written through {@link UserDAO#updateContact}.
 */
public class MyEmailController implements ReturnNavigable {

    @FXML private Label fullNameValue;
    @FXML private Label idCaption;
    @FXML private Label idValue;
    @FXML private Label emailValue;
    @FXML private TextField emailEditField;
    @FXML private Label errorLabel;
    @FXML private Button editButton;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    private final StudentService studentService = new StudentService();
    private final InstructorService instructorService = new InstructorService();
    private final UserDAO userDao = new UserDAO();
    private final AccountService accountService = new AccountService();

    private String returnFxml;
    private String returnTitle;

    @Override
    public void setReturnTarget(String fxml, String title) {
        this.returnFxml = fxml;
        this.returnTitle = title;
    }

    @FXML
    private void initialize() {
        Session session = Session.current();

        fullNameValue.setText(session.getDisplayName());
        idCaption.setText(session.getRole().getLabel() + " ID");
        idValue.setText(String.valueOf(session.getUser().getUserId()));

        refreshEmailLabel();
    }

    @FXML
    private void handleBack() {
        SceneManager.getInstance().navigateTo(returnFxml, returnTitle);
    }

    private String currentEmail() {
        Session session = Session.current();
        return switch (session.getRole()) {
            case STUDENT -> session.getStudent().getEmail();
            case INSTRUCTOR -> session.getInstructor().getEmail();
            case ADMIN -> session.getUser().getEmail();
        };
    }

    private void refreshEmailLabel() {
        String email = currentEmail();
        emailValue.setText(ValidationUtil.isBlank(email) ? "(not set)" : email);
    }

    @FXML
    private void handleEdit() {
        hideError();
        emailEditField.setText(currentEmail());

        emailValue.setVisible(false);
        emailValue.setManaged(false);
        emailEditField.setVisible(true);
        emailEditField.setManaged(true);

        editButton.setVisible(false);
        editButton.setManaged(false);
        saveButton.setVisible(true);
        saveButton.setManaged(true);
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);

        emailEditField.requestFocus();
    }

    @FXML
    private void handleCancel() {
        exitEditMode();
    }

    @FXML
    private void handleSave() {
        hideError();
        String newEmail = emailEditField.getText();

        try {
            Session session = Session.current();
            switch (session.getRole()) {
                case STUDENT -> {
                    Student student = session.getStudent();
                    student.setEmail(newEmail);
                    studentService.update(student);
                }
                case INSTRUCTOR -> {
                    Instructor instructor = session.getInstructor();
                    instructor.setEmail(newEmail);
                    instructorService.update(instructor);
                }
                case ADMIN -> {
                    User user = session.getUser();
                    String normalized = accountService.validateAdminEmail(newEmail, user.getUserId());
                    userDao.updateContact(user.getUserId(), normalized, user.getAddress());
                    user.setEmail(normalized);
                }
            }

            refreshEmailLabel();
            exitEditMode();
            AlertUtil.success("Email updated", "Your email address was updated successfully.");

        } catch (ServiceException se) {
            showError(se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not update email", "Your email address could not be updated.", e);
        }
    }

    private void exitEditMode() {
        emailValue.setVisible(true);
        emailValue.setManaged(true);
        emailEditField.setVisible(false);
        emailEditField.setManaged(false);

        editButton.setVisible(true);
        editButton.setManaged(true);
        saveButton.setVisible(false);
        saveButton.setManaged(false);
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
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
