package com.university.controller;

import com.university.controller.dialog.StudentServiceRequestDialog;
import com.university.enums.UserRole;
import com.university.service.Session;
import com.university.util.AlertUtil;
import javafx.fxml.FXML;

public final class StudentServicesController {

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
    }

    @FXML
    private void handleEnrollmentCertificate() {
        StudentServiceRequestDialog dialog =
                new StudentServiceRequestDialog("Enrollment Certificate");

        var result = dialog.showAndWait().orElse(null);

        if (result == null) {
            return;
        }

        AlertUtil.success(
                "Request Submitted",
                "Your Enrollment Certificate request was submitted successfully.\n\n"
                        + "Status: Ready for Pickup\n"
                        + "Language: " + result.language() + "\n"
                        + "Purpose: " + result.purpose() + "\n"
                        + "Copy Type: " + result.copyType()
        );
    }

    @FXML
    private void handleOfficialTranscript() {
        StudentServiceRequestDialog dialog =
                new StudentServiceRequestDialog("Official Transcript");

        var result = dialog.showAndWait().orElse(null);

        if (result == null) {
            return;
        }

        AlertUtil.success(
                "Request Submitted",
                "Your Official Transcript request was submitted successfully.\n\n"
                        + "Status: Ready for Pickup\n"
                        + "Language: " + result.language() + "\n"
                        + "Purpose: " + result.purpose() + "\n"
                        + "Copy Type: " + result.copyType()
        );
    }
    @FXML
    private void handleStudentIdCard() {
        StudentServiceRequestDialog dialog =
                new StudentServiceRequestDialog("Student ID Card");

        var result = dialog.showAndWait().orElse(null);

        if (result == null) {
            return;
        }

        AlertUtil.success(
                "Request Submitted",
                "Your Student ID Card request was submitted successfully.\n\n"
                        + "Status: Ready for Pickup\n"
                        + "Language: " + result.language() + "\n"
                        + "Purpose: " + result.purpose() + "\n"
                        + "Copy Type: " + result.copyType()
        );
    }
    @FXML
    private void handleTransportationService() {
        com.university.controller.dialog.TransportationRequestDialog dialog =
                new com.university.controller.dialog.TransportationRequestDialog();

        var result = dialog.showAndWait().orElse(null);

        if (result == null) {
            return;
        }

        AlertUtil.success(
                "Request Submitted",
                "Your Transportation Service request was submitted successfully.\n\n"
                        + "Status: Request Received\n"
                        + "Pickup Area: " + result.pickupArea() + "\n"
                        + "Pickup Point: " + result.pickupPoint() + "\n"
                        + "Schedule: " + result.schedule() + "\n"
                        + "Start Date: " + result.startDate()
        );
    }
    @FXML
    private void handleFinancialService() {
        com.university.controller.dialog.FinancialServiceRequestDialog dialog =
                new com.university.controller.dialog.FinancialServiceRequestDialog();

        var result = dialog.showAndWait().orElse(null);

        if (result == null) {
            return;
        }

        AlertUtil.success(
                "Request Submitted",
                "Your Financial Service request was submitted successfully.\n\n"
                        + "Status: Request Received\n"
                        + "Request Type: " + result.requestType() + "\n"
                        + "Semester: " + result.semester()
        );
    }
    @FXML
    private void handleITSupport() {
        com.university.controller.dialog.ITSupportRequestDialog dialog =
                new com.university.controller.dialog.ITSupportRequestDialog();

        var result = dialog.showAndWait().orElse(null);

        if (result == null) {
            return;
        }

        AlertUtil.success(
                "Request Submitted",
                "Your IT Support request was submitted successfully.\n\n"
                        + "Status: Request Received\n"
                        + "Problem Type: " + result.problemType()
        );
    }
    @FXML
    private void handleInternetService() {

        com.university.controller.dialog.InternetServiceRequestDialog dialog =
                new com.university.controller.dialog.InternetServiceRequestDialog();

        var result = dialog.showAndWait().orElse(null);

        if (result == null) {
            return;
        }

        AlertUtil.success(
                "Request Submitted",
                "Your University Internet Service request was submitted successfully.\n\n"
                        + "Status: Request Received\n"
                        + "Mobile Number: " + result.mobileNumber() + "\n"
                        + "Network: " + result.networkType() + "\n"
                        + "Package: " + result.packageSize() + "\n"
                        + "Activation: " + result.activationType()
        );
    }}
