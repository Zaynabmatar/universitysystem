import com.university.database.DBConnection;
import com.university.enums.AcademicRank;
import com.university.enums.UserRole;
import com.university.model.Instructor;
import com.university.model.Student;
import com.university.service.AccountService;
import com.university.service.AuthService;
import com.university.service.InstructorService;
import com.university.service.Session;
import com.university.service.StudentService;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;

/**
 * Verifies Phase 17 end to end against the live database, then removes the two
 * rows it created and nothing else.
 *
 * <p>Not part of the build. Run with:
 * {@code javac -cp target/classes:$(cat scratch/cp.txt) -d scratch/out scratch/Probe.java}</p>
 */
public class Probe {

    public static void main(String[] args) throws Exception {
        checkExistingPasswords();

        int studentUserId = checkAddStudent();
        int instructorUserId = checkAddInstructor();

        checkEmailEdit();
        checkLogin(studentUserId, UserRole.STUDENT);
        checkLogin(instructorUserId, UserRole.INSTRUCTOR);
        checkWrongDoor(studentUserId);

        cleanUp(studentUserId, instructorUserId);
        DBConnection.shutdown();
    }

    /** Do the accounts already in the database still sign in with <user_id>@iuL? */
    private static void checkExistingPasswords() throws Exception {
        System.out.println("=== EXISTING PASSWORD HASHES ===");
        try (Connection c = DBConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT TOP 20 user_id, role, password_hash FROM dbo.users ORDER BY NEWID()")) {
            int ok = 0, bad = 0;
            while (rs.next()) {
                int id = rs.getInt("user_id");
                String hash = rs.getString("password_hash");
                boolean matches;
                try {
                    matches = BCrypt.checkpw(id + "@iuL", hash);
                } catch (IllegalArgumentException e) {
                    matches = false;
                }
                if (matches) {
                    ok++;
                } else {
                    bad++;
                    System.out.println("  MISMATCH user_id=" + id + " role=" + rs.getString("role"));
                }
            }
            System.out.println("  " + ok + " of " + (ok + bad) + " sampled accounts accept <user_id>@iuL");
        }
    }

    private static int checkAddStudent() {
        System.out.println("=== ADD STUDENT (no id, no email typed) ===");
        Student s = new Student();
        s.setFirstName("Zaynab");
        s.setLastName("Matar");
        s.setPhone("+96170000000");
        s.setProgramId(1);
        s.setAdmissionDate(LocalDate.now());
        AccountService.NewAccount a = new StudentService().create(s);
        System.out.println("  Student ID       : " + a.userId());
        System.out.println("  University Email : " + a.email());
        System.out.println("  Temp Password    : " + a.temporaryPassword());
        System.out.println("  students.student_id=" + s.getStudentId() + " users.user_id=" + s.getUserId());
        return a.userId();
    }

    private static int checkAddInstructor() {
        System.out.println("=== ADD INSTRUCTOR (no id, no email typed) ===");
        Instructor i = new Instructor();
        i.setFirstName("Zaynab");
        i.setLastName("Matar");
        i.setPhone("+96170000001");
        i.setDepartmentId(1);
        i.setAcademicRank(AcademicRank.LECTURER);
        i.setHireDate(LocalDate.now());
        AccountService.NewAccount a = new InstructorService().create(i);
        System.out.println("  Instructor ID    : " + a.userId());
        System.out.println("  University Email : " + a.email());
        System.out.println("  Temp Password    : " + a.temporaryPassword());
        return a.userId();
    }

    /** An address already in use must be refused; a free one must be accepted. */
    private static void checkEmailEdit() {
        System.out.println("=== EDIT STUDENT EMAIL ===");
        StudentService service = new StudentService();
        java.util.List<Student> all = service.search(null);
        Student first = all.get(0);
        Student second = all.get(1);

        String original = first.getEmail();
        first.setEmail(second.getEmail());
        try {
            service.update(first);
            System.out.println("  FAIL: a duplicate address was accepted");
        } catch (RuntimeException e) {
            System.out.println("  duplicate refused: " + e.getMessage());
        }

        first.setEmail(original);
        service.update(first);
        System.out.println("  unchanged address still saves: " + original);
    }

    private static void checkLogin(int userId, UserRole role) {
        System.out.println("=== LOGIN " + role + " " + userId + " ===");
        try {
            new AuthService().login(role, String.valueOf(userId), userId + "@iuL");
            System.out.println("  signed in as " + Session.current().getUser().getUsername()
                    + " (" + Session.current().getUser().getEmail() + ")");
            new AuthService().logout();
        } catch (RuntimeException e) {
            System.out.println("  FAIL: " + e.getMessage());
        }
    }

    /** The role-selection screen must stay a real gate. */
    private static void checkWrongDoor(int studentUserId) {
        System.out.println("=== LOGIN THROUGH THE WRONG DOOR ===");
        try {
            new AuthService().login(UserRole.ADMIN, String.valueOf(studentUserId), studentUserId + "@iuL");
            System.out.println("  FAIL: a student signed in through ADMIN");
            new AuthService().logout();
        } catch (RuntimeException e) {
            System.out.println("  refused: " + e.getMessage());
        }
    }

    /**
     * Deletes ONLY the two accounts created above. Nothing pre-existing is
     * touched and no identity value is reset.
     */
    private static void cleanUp(int studentUserId, int instructorUserId) throws Exception {
        System.out.println("=== CLEAN UP (only the rows this probe created) ===");
        try (Connection c = DBConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                exec(c, "DELETE FROM dbo.students WHERE user_id = ?", studentUserId);
                exec(c, "DELETE FROM dbo.instructors WHERE user_id = ?", instructorUserId);
                exec(c, "DELETE FROM dbo.audit_log WHERE record_id IN (?, ?) AND table_name IN ('students','instructors','users')",
                        studentUserId, instructorUserId);
                exec(c, "DELETE FROM dbo.users WHERE user_id IN (?, ?)", studentUserId, instructorUserId);
                c.commit();
                System.out.println("  removed test user_id " + studentUserId + " and " + instructorUserId);
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static void exec(Connection c, String sql, int... ids) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int n = 0; n < ids.length; n++) {
                ps.setInt(n + 1, ids[n]);
            }
            ps.executeUpdate();
        }
    }
}
