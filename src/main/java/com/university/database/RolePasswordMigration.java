package com.university.database;

import com.university.dao.InstructorDAO;
import com.university.dao.StudentDAO;
import com.university.dao.UserDAO;
import com.university.enums.UserRole;
import com.university.model.Instructor;
import com.university.model.Student;
import com.university.model.User;
import com.university.service.PasswordHasher;

/**
 * One-off migration: rehashes every account's password to its role's current
 * default — {@code <user_id>@iuL} for STUDENT, {@code <user_id>@ADm} for
 * ADMIN, {@code <user_id>@iNS} for INSTRUCTOR — the one id in the system, the
 * same number shown as Student ID or Instructor ID and typed on the sign-in
 * screen.
 *
 * <p>Admin accounts are read by {@code users.role = 'ADMIN'} directly —
 * {@code dbo.admins} was retired in Phase 18 as a redundant, unused identity
 * table. Every account created by the application from then on already gets
 * the right hash at creation time ({@link UserDAO#finalizePassword}), so this
 * class has nothing left to fix on a second run beyond re-confirming the same
 * hashes.</p>
 */
public final class RolePasswordMigration {

    private RolePasswordMigration() {
    }

    public static void main(String[] args) {
        UserDAO userDao = new UserDAO();

        int admins = 0;
        for (User admin : userDao.findByRole(UserRole.ADMIN)) {
            userDao.updatePasswordHash(admin.getUserId(),
                    PasswordHasher.hashDefaultPassword(admin.getUserId(), UserRole.ADMIN));
            admins++;
        }

        int instructors = 0;
        for (Instructor instructor : new InstructorDAO().findAll()) {
            userDao.updatePasswordHash(instructor.getUserId(),
                    PasswordHasher.hashDefaultPassword(instructor.getUserId(), UserRole.INSTRUCTOR));
            instructors++;
        }

        int students = 0;
        for (Student student : new StudentDAO().findAll()) {
            userDao.updatePasswordHash(student.getUserId(),
                    PasswordHasher.hashDefaultPassword(student.getUserId()));
            students++;
        }

        System.out.println("Rehashed " + admins + " admin (<user_id>@ADm), " + instructors
                + " instructor (<user_id>@iNS) and " + students
                + " student (<user_id>@iuL) password(s).");
        DBConnection.shutdown();
    }
}
