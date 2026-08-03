package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.DepartmentDAO;
import com.university.dao.InstructorDAO;
import com.university.dao.UserDAO;
import com.university.enums.UserRole;
import com.university.model.Instructor;
import com.university.model.User;
import com.university.util.ValidationUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the admin does to instructor records.
 *
 * <p>Mirrors {@link StudentService} in every structural way: creating an
 * instructor writes a {@code users} row and an {@code instructors} row in one
 * transaction, with {@link UserDAO#insert(Connection, User)} assigning the
 * mandatory {@code <user_id>@iuL} password the same way every account gets
 * one; nothing is ever deleted (project_details.md Section 6.8) — "removing"
 * an instructor sets {@code instructors.is_active = 0} and
 * {@code users.is_active = 0} together, and the row stays for every section
 * and grade that references it.</p>
 */
public class InstructorService {

    private final InstructorDAO instructorDao = new InstructorDAO();
    private final UserDAO userDao = new UserDAO();
    private final DepartmentDAO departmentDao = new DepartmentDAO();

    /** Gives access to the connection helpers without exposing a whole data access object. */
    private final AbstractDAO transactions = new AbstractDAO() {
    };

    // ------------------------------------------------------------------ read

    /** Every instructor, for a section combo box or a report that must include inactive staff too. */
    public List<Instructor> listActive() {
        return instructorDao.findAllActive();
    }

    /** Matched against employee number, first name, last name and email. Blank/null returns everyone. */
    public List<Instructor> search(String searchText) {
        if (ValidationUtil.isBlank(searchText)) {
            return instructorDao.findAll();
        }
        String term = searchText.trim().toLowerCase();
        return instructorDao.findAll().stream()
                .filter(i -> i.getEmployeeNumber().toLowerCase().contains(term)
                        || i.getFirstName().toLowerCase().contains(term)
                        || i.getLastName().toLowerCase().contains(term)
                        || i.getEmail().toLowerCase().contains(term))
                .toList();
    }

    public Instructor findById(int instructorId) {
        return requireInstructor(instructorId);
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates the login account and the instructor record together.
     *
     * @param instructor everything except instructorId and userId, which are generated
     * @return the same instructor, with instructorId and userId now filled in
     */
    public Instructor create(Instructor instructor) {
        normalizeEmployeeNumber(instructor);
        validate(instructor);
        if (employeeNumberExists(instructor.getEmployeeNumber(), null)) {
            throw new ValidationException("That employee number already exists.");
        }
        if (emailExists(instructor.getEmail(), null)) {
            throw new ValidationException("That email address is already registered.");
        }
        requireDepartment(instructor.getDepartmentId());

        User user = new User();
        user.setUsername(instructor.getEmployeeNumber());
        user.setRole(UserRole.INSTRUCTOR);
        user.setActive(true);

        instructor.setActive(true);

        Connection connection = transactions.beginTransaction();
        try {
            int userId = userDao.insert(connection, user);
            instructor.setUserId(userId);

            int instructorId = instructorDao.insert(connection, instructor);
            instructor.setInstructorId(instructorId);

            connection.commit();
            return instructor;
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The instructor could not be created. No changes were saved.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    // ------------------------------------------------------------------ update

    /** Updates the editable columns only. */
    public void update(Instructor instructor) {
        requireInstructor(instructor.getInstructorId());
        normalizeEmployeeNumber(instructor);
        validate(instructor);
        if (employeeNumberExists(instructor.getEmployeeNumber(), instructor.getInstructorId())) {
            throw new ValidationException("That employee number already exists.");
        }
        if (emailExists(instructor.getEmail(), instructor.getInstructorId())) {
            throw new ValidationException("That email address is already registered.");
        }
        requireDepartment(instructor.getDepartmentId());

        instructorDao.update(instructor);
    }

    // ------------------------------------------------------------------ soft delete

    /**
     * SOFT DELETE (project_details.md Section 6.8). Nothing is removed:
     * {@code instructors.is_active -> 0} and {@code users.is_active -> 0}
     * together, so the instructor can no longer sign in or be assigned a
     * section, but every section and grade they are already on stays intact.
     */
    public void deactivate(int instructorId) {
        setActiveState(instructorId, false);
    }

    /** Undo of {@link #deactivate}: the instructor and their login are re-enabled. */
    public void reactivate(int instructorId) {
        setActiveState(instructorId, true);
    }

    private void setActiveState(int instructorId, boolean active) {
        Instructor instructor = requireInstructor(instructorId);

        Connection connection = transactions.beginTransaction();
        try {
            instructor.setActive(active);
            instructorDao.update(connection, instructor);
            userDao.setActive(instructor.getUserId(), active);
            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("Could not change the instructor's account state.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    // ------------------------------------------------------------------ password reset

    /**
     * Resets the instructor's password back to the mandatory {@code <user_id>@iuL} default.
     *
     * @return the plain-text password to show once to the admin; it is not stored anywhere
     */
    public String resetPasswordToDefault(int instructorId) {
        Instructor instructor = requireInstructor(instructorId);
        userDao.updatePasswordHash(instructor.getUserId(),
                PasswordHasher.hashDefaultPassword(instructor.getUserId()));
        return PasswordHasher.defaultPasswordFor(instructor.getUserId());
    }

    // ------------------------------------------------------------------ uniqueness

    /** @param excludeInstructorId pass the id being edited, or null when creating. */
    public boolean employeeNumberExists(String employeeNumber, Integer excludeInstructorId) {
        return instructorDao.findByEmployeeNumber(employeeNumber)
                .map(Instructor::getInstructorId)
                .filter(id -> excludeInstructorId == null || !id.equals(excludeInstructorId))
                .isPresent();
    }

    public boolean emailExists(String email, Integer excludeInstructorId) {
        return instructorDao.findByEmail(email)
                .map(Instructor::getInstructorId)
                .filter(id -> excludeInstructorId == null || !id.equals(excludeInstructorId))
                .isPresent();
    }

    // ------------------------------------------------------------------ helpers

    private Instructor requireInstructor(int instructorId) {
        ValidationException.requireId(instructorId, "Instructor");
        return instructorDao.findById(instructorId)
                .orElseThrow(() -> new ServiceException("That instructor record was not found."));
    }

    private void normalizeEmployeeNumber(Instructor instructor) {
        ValidationException.requireText(instructor.getEmployeeNumber(), "Employee number");
        instructor.setEmployeeNumber(instructor.getEmployeeNumber().trim().toUpperCase());
    }

    private void requireDepartment(int departmentId) {
        ValidationException.requireId(departmentId, "Department");
        if (departmentDao.findById(departmentId).isEmpty()) {
            throw new ValidationException("That department does not exist.");
        }
    }

    private void validate(Instructor instructor) {
        if (!ValidationUtil.isShortCode(instructor.getEmployeeNumber())) {
            throw new ValidationException("Employee number must be 2-10 letters/digits, e.g. EMP9001.");
        }
        if (ValidationUtil.isBlank(instructor.getFirstName()) || !ValidationUtil.maxLength(instructor.getFirstName(), 50)) {
            throw new ValidationException("First name is required (maximum 50 characters).");
        }
        if (ValidationUtil.isBlank(instructor.getLastName()) || !ValidationUtil.maxLength(instructor.getLastName(), 50)) {
            throw new ValidationException("Last name is required (maximum 50 characters).");
        }
        if (!ValidationUtil.isEmail(instructor.getEmail()) || !ValidationUtil.maxLength(instructor.getEmail(), 100)) {
            throw new ValidationException("Enter a valid email address, e.g. ahmad@university.edu.");
        }
        if (ValidationUtil.notBlank(instructor.getPhone()) && !ValidationUtil.isPhone(instructor.getPhone())) {
            throw new ValidationException("Phone number may contain digits, spaces, +, ( ) and -, 7-20 characters.");
        }
        ValidationException.requireId(instructor.getDepartmentId(), "Department");
        if (instructor.getAcademicRank() == null) {
            throw new ValidationException("Select an academic rank.");
        }
        LocalDate hireDate = instructor.getHireDate();
        if (hireDate != null && hireDate.isAfter(LocalDate.now())) {
            throw new ValidationException("Hire date cannot be in the future.");
        }
    }
}
