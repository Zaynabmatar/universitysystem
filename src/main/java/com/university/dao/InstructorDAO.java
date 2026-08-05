package com.university.dao;

import com.university.enums.AcademicRank;
import com.university.model.Instructor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.instructors}.
 */
public class InstructorDAO extends AbstractDAO implements GenericDAO<Instructor> {

    private static final String SELECT =
            "SELECT instructor_id, user_id, employee_number, first_name, last_name, email, phone, "
            + "dept_id, academic_rank, hire_date, is_active FROM dbo.instructors";

    private static final String INSERT =
            "INSERT INTO dbo.instructors (user_id, employee_number, first_name, last_name, email, "
            + "phone, dept_id, academic_rank, hire_date, is_active) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.instructors SET employee_number = ?, first_name = ?, last_name = ?, "
            + "email = ?, phone = ?, dept_id = ?, academic_rank = ?, hire_date = ?, is_active = ? "
            + "WHERE instructor_id = ?";

    private static final String DELETE = "DELETE FROM dbo.instructors WHERE instructor_id = ?";

    private static final RowMapper<Instructor> MAPPER = InstructorDAO::mapRow;

    static Instructor mapRow(ResultSet rs) throws SQLException {
        Instructor instructor = new Instructor();
        instructor.setInstructorId(rs.getInt("instructor_id"));
        instructor.setUserId(rs.getInt("user_id"));
        instructor.setEmployeeNumber(rs.getString("employee_number"));
        instructor.setFirstName(rs.getString("first_name"));
        instructor.setLastName(rs.getString("last_name"));
        instructor.setEmail(rs.getString("email"));
        instructor.setPhone(rs.getString("phone"));
        instructor.setDepartmentId(rs.getInt("dept_id"));
        instructor.setAcademicRank(AcademicRank.fromDb(rs.getString("academic_rank")));
        instructor.setHireDate(DaoUtils.getLocalDate(rs, "hire_date"));
        instructor.setActive(rs.getBoolean("is_active"));
        return instructor;
    }

    @Override
    public Optional<Instructor> findById(int id) {
        return queryOne(SELECT + " WHERE instructor_id = ?", MAPPER, id);
    }

    @Override
    public List<Instructor> findAll() {
        return queryList(SELECT + " ORDER BY last_name, first_name", MAPPER);
    }

    /** Finds the instructor record behind an Instructor ID ({@code users.user_id}). */
    public Optional<Instructor> findByUserId(int userId) {
        return queryOne(SELECT + " WHERE user_id = ?", MAPPER, userId);
    }

    /** Finds an instructor by their email address, which is also unique. */
    public Optional<Instructor> findByEmail(String email) {
        return queryOne(SELECT + " WHERE email = ?", MAPPER, email);
    }

    /** Every instructor in one department. */
    public List<Instructor> findByDepartment(int departmentId) {
        return queryList(SELECT + " WHERE dept_id = ? ORDER BY last_name, first_name",
                MAPPER, departmentId);
    }

    /** The instructors available to teach, for a section combo box. */
    public List<Instructor> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY last_name, first_name", MAPPER);
    }

    @Override
    public int insert(Instructor entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Instructor entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Instructor entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Instructor entity) {
        return executeUpdate(connection, UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean deleteById(int id) {
        return executeUpdate(DELETE, id) > 0;
    }

    @Override
    public boolean deleteById(Connection connection, int id) {
        return executeUpdate(connection, DELETE, id) > 0;
    }

    private Object[] insertParams(Instructor entity) {
        return new Object[]{
                entity.getUserId(),
                entity.getEmployeeNumber(),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getEmail(),
                entity.getPhone(),
                entity.getDepartmentId(),
                entity.getAcademicRank(),
                entity.getHireDate(),
                entity.isActive()
        };
    }

    private Object[] updateParams(Instructor entity) {
        return new Object[]{
                entity.getEmployeeNumber(),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getEmail(),
                entity.getPhone(),
                entity.getDepartmentId(),
                entity.getAcademicRank(),
                entity.getHireDate(),
                entity.isActive(),
                entity.getInstructorId()
        };
    }
}
