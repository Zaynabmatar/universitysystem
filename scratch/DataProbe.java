import com.university.dao.GradeDAO;
import com.university.dao.ReportDAO;
import com.university.database.DBConnection;
import com.university.model.GradeSheetRow;
import com.university.service.ReportService;
import com.university.service.TranscriptService;

/** Checks the id shown on the grade sheet, the two reports and the transcript. */
public class DataProbe {
    public static void main(String[] args) {
        System.out.println("=== GRADE SHEET (section 1) ===");
        for (GradeSheetRow r : new GradeDAO().findSectionRoster(1).stream().limit(3).toList()) {
            System.out.println("  Student ID " + r.getStudentUserId() + "  " + r.getStudentName());
        }
        System.out.println("=== PROBATION REPORT ===");
        new ReportDAO().studentsOnProbation().stream().limit(3)
                .forEach(r -> System.out.println("  Student ID " + r.studentUserId + "  " + r.studentName));
        System.out.println("=== TOP GPA REPORT ===");
        new ReportDAO().topStudentsByGpa(3)
                .forEach(r -> System.out.println("  Student ID " + r.studentUserId + "  " + r.studentName));
        System.out.println("=== TRANSCRIPT (student_id 1) ===");
        var t = new TranscriptService().getTranscript(1);
        System.out.println("  Student ID " + t.studentUserId + "  " + t.fullName());
        DBConnection.shutdown();
    }
}
