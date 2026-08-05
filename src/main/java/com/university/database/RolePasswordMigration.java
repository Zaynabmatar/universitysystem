package com.university.database;

import com.university.dao.AdminDAO;
import com.university.dao.InstructorDAO;
import com.university.dao.StudentDAO;
import com.university.dao.UserDAO;
import com.university.model.Admin;
import com.university.model.Instructor;
import com.university.model.Student;
import com.university.service.PasswordHasher;

/**
 * One-off migration: rehashes every account's password to the current rule,
 * BCrypt of {@code <user_id>@iuL} — the one id in the system, the same number
 * shown as Student ID or Instructor ID and typed on the sign-in screen.
 *
 * <p>Run this exactly once, after applying
 * {@code migrations/phase16_admins_and_role_login.sql} (or after building
 * {@code universitymanagmentDB.sql} fresh). Every account created by the
 * application from then on already gets the right hash at creation time
 * ({@link UserDAO#finalizePassword}), so this class has nothing left to fix
 * on a second run beyond re-confirming the same hashes.</p>
 */
public final class RolePasswordMigration {

    private RolePasswordMigration() {
    }

    public static void main(String[] args) {
        UserDAO userDao = new UserDAO();

        int admins = 0;
        for (Admin admin : new AdminDAO().findAll()) {
            userDao.updatePasswordHash(admin.getUserId(), PasswordHasher.hashDefaultPassword(admin.getUserId()));
            admins++;
        }

        int instructors = 0;
        for (Instructor instructor : new InstructorDAO().findAll()) {
            userDao.updatePasswordHash(instructor.getUserId(),
                    PasswordHasher.hashDefaultPassword(instructor.getUserId()));
            instructors++;
        }

        int students = 0;
        for (Student student : new StudentDAO().findAll()) {
            userDao.updatePasswordHash(student.getUserId(),
                    PasswordHasher.hashDefaultPassword(student.getUserId()));
            students++;
        }

        System.out.println("Rehashed " + admins + " admin, " + instructors
                + " instructor and " + students + " student password(s) to <user_id>@iuL.");
        DBConnection.shutdown();
    }
}
