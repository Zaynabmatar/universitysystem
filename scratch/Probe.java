import com.university.model.Student;
import com.university.model.Instructor;
import com.university.enums.AcademicRank;
import com.university.service.StudentService;
import com.university.service.InstructorService;
import java.time.LocalDate;

public class Probe {
    public static void main(String[] args) throws Exception {
        System.out.println("=== STUDENT CREATE ===");
        try {
            Student s = new Student();
            s.setStudentNumber("2099" + System.currentTimeMillis() % 100000);
            s.setFirstName("Probe");
            s.setLastName("Test");
            s.setEmail("probe.test." + System.currentTimeMillis() + "@university.edu");
            s.setPhone("+96170000000");
            s.setProgramId(1);
            s.setAdmissionDate(LocalDate.now().minusDays(1));
            Student created = new StudentService().create(s);
            System.out.println("OK student_id=" + created.getStudentId() + " user_id=" + created.getUserId());
        } catch (Throwable t) {
            System.out.println("FAILED with " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            Throwable c = t.getCause();
            while (c != null) {
                System.out.println("  caused by " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
        }

        System.out.println();
        System.out.println("=== INSTRUCTOR CREATE ===");
        try {
            Instructor i = new Instructor();
            i.setEmployeeNumber("PRB" + (System.currentTimeMillis() % 10000));
            i.setFirstName("Probe");
            i.setLastName("Instructor");
            i.setEmail("probe.instr." + System.currentTimeMillis() + "@university.edu");
            i.setPhone("+96170000001");
            i.setDepartmentId(16);
            i.setAcademicRank(AcademicRank.values()[0]);
            i.setHireDate(LocalDate.now().minusDays(1));
            Instructor created = new InstructorService().create(i);
            System.out.println("OK instructor_id=" + created.getInstructorId() + " user_id=" + created.getUserId());
        } catch (Throwable t) {
            System.out.println("FAILED with " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            Throwable c = t.getCause();
            while (c != null) {
                System.out.println("  caused by " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
        }
    }
}
