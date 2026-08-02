package com.university.dao;

import com.university.model.Department;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.departments}.
 */
public class DepartmentDAO extends AbstractDAO implements GenericDAO<Department> {

    private static final String SELECT =
            "SELECT dept_id, dept_code, dept_name, is_active FROM dbo.departments";

    private static final String INSERT =
            "INSERT INTO dbo.departments (dept_code, dept_name, is_active) VALUES (?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.departments SET dept_code = ?, dept_name = ?, is_active = ? WHERE dept_id = ?";

    private static final String DELETE = "DELETE FROM dbo.departments WHERE dept_id = ?";

    private static final RowMapper<Department> MAPPER = DepartmentDAO::mapRow;

    static Department mapRow(ResultSet rs) throws SQLException {
        Department department = new Department();
        department.setDepartmentId(rs.getInt("dept_id"));
        department.setDepartmentCode(rs.getString("dept_code"));
        department.setDepartmentName(rs.getString("dept_name"));
        department.setActive(rs.getBoolean("is_active"));
        return department;
    }

    @Override
    public Optional<Department> findById(int id) {
        return queryOne(SELECT + " WHERE dept_id = ?", MAPPER, id);
    }

    @Override
    public List<Department> findAll() {
        return queryList(SELECT + " ORDER BY dept_code", MAPPER);
    }

    /** The departments that are still in use, for a combo box. */
    public List<Department> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY dept_code", MAPPER);
    }

    /** Finds a department by its unique code, for example CS. */
    public Optional<Department> findByCode(String departmentCode) {
        return queryOne(SELECT + " WHERE dept_code = ?", MAPPER, departmentCode);
    }

    @Override
    public int insert(Department entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Department entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Department entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Department entity) {
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

    private Object[] insertParams(Department entity) {
        return new Object[]{
                entity.getDepartmentCode(),
                entity.getDepartmentName(),
                entity.isActive()
        };
    }

    private Object[] updateParams(Department entity) {
        return new Object[]{
                entity.getDepartmentCode(),
                entity.getDepartmentName(),
                entity.isActive(),
                entity.getDepartmentId()
        };
    }
}
