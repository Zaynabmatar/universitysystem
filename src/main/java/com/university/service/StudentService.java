package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.ProgramDAO;
import com.university.dao.StudentDAO;
import com.university.dao.UserDAO;
import com.university.enums.AcademicStanding;
import com.university.enums.StudentStatus;
import com.university.enums.UserRole;
import com.university.model.Student;
import com.university.model.User;
import com.university.util.ValidationUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the admin does to student records.
 *
 * <p>Two rules govern this whole class:</p>
 * <ol>
 *   <li>Creating a student writes TWO rows — {@code users} and {@code students}
 *       — inside one transaction. If either fails, neither is kept. The login
 *       row is built the same way every account in this project is: no
 *       password is chosen up front, {@link UserDAO#insert(Connection, User)}
 *       assigns the mandatory {@code <user_id>@iuL} password once the identity
 *       column exists, matching how sign-in already works by User ID
 *       ({@link AuthService}).</li>
 *   <li>Nothing is ever deleted (project_details.md Section 6.8). "Removing" a
 *       student sets {@code students.status = WITHDRAWN} and
 *       {@code users.is_active = 0}; every enrolment and grade stays put.</li>
 * </ol>
 *
 * <p>{@code cumulative_gpa}, {@code completed_credits} and
 * {@code academic_standing} are cached columns owned by {@link AcademicService}
 * — this class never writes them.</p>
 */
public class StudentService {

    private final StudentDAO studentDao = new StudentDAO();
    private final UserDAO userDao = new UserDAO();
    private final ProgramDAO programDao = new ProgramDAO();

    /** Gives access to the connection helpers without exposing a whole data access object. */
    private final AbstractDAO transactions = new AbstractDAO() {
    };

    // ------------------------------------------------------------------ read

    /** Matched against student number, first name, last name and email. Blank/null returns everyone. */
    public List<Student> search(String searchText) {
        return ValidationUtil.isBlank(searchText) ? studentDao.findAll() : studentDao.search(searchText.trim());
    }

    public Student findById(int studentId) {
        return requireStudent(studentId);
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates the login account and the student record together.
     *
     * @param student everything except studentId and userId, which are generated
     * @return the same student, with studentId and userId now filled in
     */
    public Student create(Student student) {
        validate(student);
        if (studentNumberExists(student.getStudentNumber(), null)) {
            throw new ValidationException("That student number already exists.");
        }
        if (emailExists(student.getEmail(), null)) {
            throw new ValidationException("That email address is already registered.");
        }
        requireProgram(student.getProgramId());

        User user = new User();
        user.setUsername(student.getStudentNumber());
        user.setRole(UserRole.STUDENT);
        user.setActive(true);

        student.setStatus(student.getStatus() == null ? StudentStatus.ACTIVE : student.getStatus());
        student.setAcademicStanding(AcademicStanding.NEW);
        student.setCumulativeGpa(BigDecimal.ZERO);
        student.setCompletedCredits(0);
        student.setProbationCount(0);

        Connection connection = transactions.beginTransaction();
        try {
            int userId = userDao.insert(connection, user);
            student.setUserId(userId);

            int studentId = studentDao.insert(connection, student);
            student.setStudentId(studentId);

            userDao.finalizePassword(connection, userId, studentId);

            connection.commit();
            return student;
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The student could not be created. No changes were saved.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    // ------------------------------------------------------------------ update

    /** Updates the editable columns only. Never touches gpa / credits / standing / probation_count. */
    public void update(Student student) {
        requireStudent(student.getStudentId());
        validate(student);
        if (studentNumberExists(student.getStudentNumber(), student.getStudentId())) {
            throw new ValidationException("That student number already exists.");
        }
        if (emailExists(student.getEmail(), student.getStudentId())) {
            throw new ValidationException("That email address is already registered.");
        }
        requireProgram(student.getProgramId());

        studentDao.update(student);
    }

    // ------------------------------------------------------------------ soft delete

    /**
     * SOFT DELETE (project_details.md Section 6.8). Nothing is removed:
     * {@code students.status -> WITHDRAWN} (so registration rule R2 blocks them),
     * {@code users.is_active -> 0} (so they can no longer sign in).
     */
    public void deactivate(int studentId) {
        setActiveState(studentId, false);
    }

    /** Undo of {@link #deactivate}: status back to ACTIVE, the login re-enabled. */
    public void reactivate(int studentId) {
        setActiveState(studentId, true);
    }

    private void setActiveState(int studentId, boolean active) {
        Student student = requireStudent(studentId);

        Connection connection = transactions.beginTransaction();
        try {
            student.setStatus(active ? StudentStatus.ACTIVE : StudentStatus.WITHDRAWN);
            studentDao.update(connection, student);
            userDao.setActive(student.getUserId(), active);
            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("Could not change the student's account state.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    // ------------------------------------------------------------------ password reset

    /**
     * Resets the student's password back to the mandatory {@code <user_id>@iuL} default.
     *
     * @return the plain-text password to show once to the admin; it is not stored anywhere
     */
    public String resetPasswordToDefault(int studentId) {
        Student student = requireStudent(studentId);
        userDao.updatePasswordHash(student.getUserId(), PasswordHasher.hashDefaultPassword(student.getUserId()));
        return PasswordHasher.defaultPasswordFor(student.getUserId());
    }

    // ------------------------------------------------------------------ uniqueness

    /** @param excludeStudentId pass the id being edited, or null when creating. */
    public boolean studentNumberExists(String studentNumber, Integer excludeStudentId) {
        return studentDao.findByStudentNumber(studentNumber)
                .map(Student::getStudentId)
                .filter(id -> excludeStudentId == null || !id.equals(excludeStudentId))
                .isPresent();
    }

    public boolean emailExists(String email, Integer excludeStudentId) {
        return studentDao.findByEmail(email)
                .map(Student::getStudentId)
                .filter(id -> excludeStudentId == null || !id.equals(excludeStudentId))
                .isPresent();
    }

    // ------------------------------------------------------------------ helpers

    private Student requireStudent(int studentId) {
        ValidationException.requireId(studentId, "Student");
        return studentDao.findById(studentId)
                .orElseThrow(() -> new ServiceException("That student record was not found."));
    }

    private void requireProgram(int programId) {
        ValidationException.requireId(programId, "Program");
        if (programDao.findById(programId).isEmpty()) {
            throw new ValidationException("That program does not exist.");
        }
    }

    private void validate(Student student) {
        if (!ValidationUtil.isStudentNumber(student.getStudentNumber())) {
            throw new ValidationException("Student number must be 4-20 digits, e.g. 2021001234.");
        }
        if (ValidationUtil.isBlank(student.getFirstName()) || !ValidationUtil.maxLength(student.getFirstName(), 50)) {
            throw new ValidationException("First name is required (maximum 50 characters).");
        }
        if (ValidationUtil.isBlank(student.getLastName()) || !ValidationUtil.maxLength(student.getLastName(), 50)) {
            throw new ValidationException("Last name is required (maximum 50 characters).");
        }
        if (!ValidationUtil.isEmail(student.getEmail()) || !ValidationUtil.maxLength(student.getEmail(), 100)) {
            throw new ValidationException("Enter a valid email address, e.g. sara@university.edu.");
        }
        if (ValidationUtil.notBlank(student.getPhone()) && !ValidationUtil.isPhone(student.getPhone())) {
            throw new ValidationException("Phone number may contain digits, spaces, +, ( ) and -, 7-20 characters.");
        }
        LocalDate dob = student.getDateOfBirth();
        if (dob != null) {
            if (!dob.isBefore(LocalDate.now())) {
                throw new ValidationException("Date of birth must be in the past.");
            }
            if (dob.isAfter(LocalDate.now().minusYears(15))) {
                throw new ValidationException("The student must be at least 15 years old.");
            }
        }
        if (!ValidationUtil.maxLength(student.getAddress(), 200)) {
            throw new ValidationException("Address must be 200 characters or fewer.");
        }
        ValidationException.requireId(student.getProgramId(), "Program");
        if (student.getAdmissionDate() == null) {
            throw new ValidationException("Admission date is required.");
        }
        if (student.getAdmissionDate().isAfter(LocalDate.now())) {
            throw new ValidationException("Admission date cannot be in the future.");
        }
    }
}
