package com.university.controller;

import com.university.controller.dialog.StudentServiceRequestDialog;
import com.university.enums.UserRole;
import com.university.model.StudentServiceRequest;
import com.university.service.Session;
import com.university.service.StudentServiceRequestService;
import com.university.util.AlertUtil;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;

public final class StudentServicesController {

    private static final DateTimeFormatter SUBMITTED_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    @FXML private Label myRequestsTitleLabel;
    @FXML private Label noRequestsLabel;
    @FXML private VBox requestsListBox;

    private final StudentServiceRequestService requestService = new StudentServiceRequestService();

    private int studentId;

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.STUDENT);
        studentId = Session.current().requireStudentId();

        refreshMyRequests();
    }

    /**
     * Reloads "My Requests" from the database — the count and list come from
     * the student's actual submitted rows, never a hardcoded number, and this
     * is called again right after every successful submission below.
     */
    private void refreshMyRequests() {
        List<StudentServiceRequest> requests = requestService.myRequests(studentId);

        myRequestsTitleLabel.setText("My Requests (" + requests.size() + ")");

        boolean empty = requests.isEmpty();
        noRequestsLabel.setVisible(empty);
        noRequestsLabel.setManaged(empty);

        requestsListBox.getChildren().clear();
        for (StudentServiceRequest request : requests) {
            requestsListBox.getChildren().add(requestRow(request));
        }
    }

    private VBox requestRow(StudentServiceRequest request) {
        Label title = new Label(request.serviceName() + " — " + request.status());
        title.getStyleClass().add("section-title");

        Label meta = new Label("Submitted " + SUBMITTED_FMT.format(request.submittedAt()));
        meta.getStyleClass().add("muted-text");

        VBox row = new VBox(2, title, meta);
        row.getStyleClass().add("panel");
        return row;
    }

    private void recordRequest(String serviceName, String details) {
        requestService.submit(studentId, serviceName, details);
        refreshMyRequests();
    }

    @FXML
    private void handleEnrollmentCertificate() {
        StudentServiceRequestDialog dialog =
                new StudentServiceRequestDialog("Enrollment Certificate");

        var result = dialog.showAndWait().orElse(null);

        if (result == null) {
            return;
        }

        recordRequest(
                "Enrollment Certificate",
                "Language: " + result.language() + ", Purpose: " + result.purpose()
                        + ", Copy Type: " + result.copyType()
        );

        AlertUtil.success(
                "Request Submitted",
                "Your Enrollment Certificate request was submitted successfully.\n\n"
                        + "Status: Will be ready soon\n"
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

        recordRequest(
                "Official Transcript",
                "Language: " + result.language() + ", Purpose: " + result.purpose()
                        + ", Copy Type: " + result.copyType()
        );

        AlertUtil.success(
                "Request Submitted",
                "Your Official Transcript request was submitted successfully.\n\n"
                        + "Status: Will be ready soon\n"
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

        recordRequest(
                "Student ID Card",
                "Language: " + result.language() + ", Purpose: " + result.purpose()
                        + ", Copy Type: " + result.copyType()
        );

        AlertUtil.success(
                "Request Submitted",
                "Your Student ID Card request was submitted successfully.\n\n"
                        + "Status: Will be ready soon\n"
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

        recordRequest(
                "Transportation Service",
                "Pickup Area: " + result.pickupArea() + ", Pickup Point: " + result.pickupPoint()
                        + ", Schedule: " + result.schedule() + ", Start Date: " + result.startDate()
        );

        AlertUtil.success(
                "Request Submitted",
                "Your Transportation Service request was submitted successfully.\n\n"
                        + "Status: Will be ready soon\n"
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

        recordRequest(
                "Financial Service",
                "Request Type: " + result.requestType() + ", Semester: " + result.semester()
        );

        AlertUtil.success(
                "Request Submitted",
                "Your Financial Service request was submitted successfully.\n\n"
                        + "Status: Will be ready soon\n"
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

        recordRequest(
                "IT Support",
                "Problem Type: " + result.problemType()
        );

        AlertUtil.success(
                "Request Submitted",
                "Your IT Support request was submitted successfully.\n\n"
                        + "Status: Will be ready soon\n"
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

        recordRequest(
                "University Internet Service",
                "Mobile Number: " + result.mobileNumber() + ", Network: " + result.networkType()
                        + ", Package: " + result.packageSize() + ", Activation: " + result.activationType()
        );

        AlertUtil.success(
                "Request Submitted",
                "Your University Internet Service request was submitted successfully.\n\n"
                        + "Status: Will be ready soon\n"
                        + "Mobile Number: " + result.mobileNumber() + "\n"
                        + "Network: " + result.networkType() + "\n"
                        + "Package: " + result.packageSize() + "\n"
                        + "Activation: " + result.activationType()
        );
    }}
