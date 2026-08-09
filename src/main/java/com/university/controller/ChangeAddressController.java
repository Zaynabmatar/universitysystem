package com.university.controller;

import com.university.dao.UserDAO;
import com.university.model.Instructor;
import com.university.model.Student;
import com.university.model.User;
import com.university.service.InstructorService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.service.StudentService;
import com.university.util.AlertUtil;
import com.university.util.SceneManager;
import com.university.util.ValidationUtil;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

/**
 * Lets the signed-in user (any role) update their own address. Student and
 * Instructor already have {@code address} on their own table, reused through
 * {@link StudentService#update}/{@link InstructorService#update}; Admin has
 * no profile row of its own, so its address lives directly on
 * {@code dbo.users} and is written through {@link UserDAO#updateContact}.
 */
public class ChangeAddressController implements ReturnNavigable {

    private static final int ADDRESS_MAX_LENGTH = 200;

    @FXML private TextArea currentAddressArea;
    @FXML private TextArea newAddressArea;
    @FXML private Label errorLabel;

    private final StudentService studentService = new StudentService();
    private final InstructorService instructorService = new InstructorService();
    private final UserDAO userDao = new UserDAO();

    private String returnFxml;
    private String returnTitle;

    @Override
    public void setReturnTarget(String fxml, String title) {
        this.returnFxml = fxml;
        this.returnTitle = title;
    }

    @FXML
    private void initialize() {
        refreshCurrentAddress();
    }

    @FXML
    private void handleBack() {
        SceneManager.getInstance().navigateTo(returnFxml, returnTitle);
    }

    private String currentAddress() {
        Session session = Session.current();
        return switch (session.getRole()) {
            case STUDENT -> session.getStudent().getAddress();
            case INSTRUCTOR -> session.getInstructor().getAddress();
            case ADMIN -> session.getUser().getAddress();
        };
    }

    private void refreshCurrentAddress() {
        String address = currentAddress();
        currentAddressArea.setText(ValidationUtil.isBlank(address) ? "" : address);
    }

    @FXML
    private void handleSaveChanges() {
        hideError();
        String newAddress = newAddressArea.getText();

        if (ValidationUtil.isBlank(newAddress)) {
            showError("Please enter a new address.");
            return;
        }
        if (!ValidationUtil.maxLength(newAddress, ADDRESS_MAX_LENGTH)) {
            showError("Address must be " + ADDRESS_MAX_LENGTH + " characters or fewer.");
            return;
        }

        try {
            Session session = Session.current();
            switch (session.getRole()) {
                case STUDENT -> {
                    Student student = session.getStudent();
                    student.setAddress(newAddress);
                    studentService.update(student);
                }
                case INSTRUCTOR -> {
                    Instructor instructor = session.getInstructor();
                    instructor.setAddress(newAddress);
                    instructorService.update(instructor);
                }
                case ADMIN -> {
                    User user = session.getUser();
                    userDao.updateContact(user.getUserId(), user.getEmail(), newAddress);
                    user.setAddress(newAddress);
                }
            }

            refreshCurrentAddress();
            newAddressArea.clear();
            AlertUtil.success("Address updated", "Your address was updated successfully.");

        } catch (ServiceException se) {
            showError(se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not update address", "Your address could not be updated.", e);
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
