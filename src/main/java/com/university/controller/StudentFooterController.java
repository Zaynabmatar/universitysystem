package com.university.controller;

import com.university.enums.AcademicStanding;
import com.university.model.Campus;
import com.university.model.Enrollment;
import com.university.model.Program;
import com.university.model.Section;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.service.RegistrationService;
import com.university.service.SectionService;
import com.university.service.SemesterService;
import com.university.service.Session;
import com.university.service.StudentService;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Controller for the reusable "Student Info + Contact Us" footer ({@code student_footer.fxml}),
 * included at the end of every Student sidebar page. Fills itself in from the signed-in
 * student's own record — never a hardcoded sample — independently of whichever page includes it.
 */
public class StudentFooterController {

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @FXML private Label footerMajorLabel;
    @FXML private Label footerMobileLabel;
    @FXML private Label footerCampusLabel;
    @FXML private Label footerIulEmailLabel;
    @FXML private Label footerFirstRegLabel;
    @FXML private Label footerLastRegLabel;
    @FXML private Label footerProbationLabel;

    private final StudentService studentService = new StudentService();
    private final RegistrationService registrationService = new RegistrationService();
    private final SectionService sectionService = new SectionService();
    private final SemesterService semesterService = new SemesterService();

    @FXML
    private void initialize() {
        Student student = Session.current().getStudent();
        if (student == null) {
            footerMajorLabel.setText("Major: Not available");
            footerMobileLabel.setText("Mobile: Not available");
            footerCampusLabel.setText("Campus: Not available");
            footerIulEmailLabel.setText("IUL Email: Not available");
            footerFirstRegLabel.setText("Date of First Registration: Not available");
            footerLastRegLabel.setText("Date of Last Registration: Not available");
            footerProbationLabel.setText("Academic Probation: Not available");
            return;
        }

        Program program = studentService.programOf(student);
        footerMajorLabel.setText("Major: " + orNotAvailable(program == null ? null : program.getProgramName()));
        footerMobileLabel.setText("Mobile: " + orNotAvailable(student.getPhone()));
        footerCampusLabel.setText("Campus: " + orNotAvailable(currentCampusText(student.getStudentId())));
        footerIulEmailLabel.setText("IUL Email: " + orNotAvailable(student.getEmail()));

        List<Enrollment> allEnrollments = registrationService.allEnrollments(student.getStudentId());
        Enrollment first = allEnrollments.stream()
                .filter(e -> e.getEnrollmentDate() != null)
                .min(Comparator.comparing(Enrollment::getEnrollmentDate)).orElse(null);
        Enrollment last = allEnrollments.stream()
                .filter(e -> e.getEnrollmentDate() != null)
                .max(Comparator.comparing(Enrollment::getEnrollmentDate)).orElse(null);
        footerFirstRegLabel.setText("Date of First Registration: "
                + (first == null ? "Not available" : MD.format(first.getEnrollmentDate().toLocalDate())));
        footerLastRegLabel.setText("Date of Last Registration: "
                + (last == null ? "Not available" : MD.format(last.getEnrollmentDate().toLocalDate())));

        footerProbationLabel.setText("Academic Probation: "
                + (student.getAcademicStanding() == AcademicStanding.PROBATION ? "Yes" : "No"));
    }

    /** The campus of one of the student's sections registered for the current semester, or null when none. */
    private String currentCampusText(int studentId) {
        Semester currentSemester = semesterService.getCurrentSemester();
        if (currentSemester == null) {
            return null;
        }
        List<Enrollment> current = registrationService.currentRegistrations(studentId, currentSemester.getSemesterId());
        if (current.isEmpty()) {
            return null;
        }
        Section section = sectionService.findById(current.get(0).getSectionId());
        if (section == null) {
            return null;
        }
        List<Campus> campuses = sectionService.listCampuses();
        return campuses.stream().filter(c -> c.getCampusId() == section.getCampusId())
                .findFirst().map(Campus::getCampusName).orElse(null);
    }

    private static String orNotAvailable(String value) {
        return value == null || value.isBlank() ? "Not available" : value;
    }
}
