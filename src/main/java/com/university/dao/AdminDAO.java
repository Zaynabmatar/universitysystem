package com.university.dao;

import com.university.model.Admin;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.admins}.
 */
public class AdminDAO extends AbstractDAO implements GenericDAO<Admin> {

    private static final String SELECT =
            "SELECT admin_id, user_id, is_active FROM dbo.admins";

    private static final String INSERT =
            "INSERT INTO dbo.admins (user_id, is_active) VALUES (?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.admins SET is_active = ? WHERE admin_id = ?";

    private static final String DELETE = "DELETE FROM dbo.admins WHERE admin_id = ?";

    private static final RowMapper<Admin> MAPPER = AdminDAO::mapRow;

    static Admin mapRow(ResultSet rs) throws SQLException {
        Admin admin = new Admin();
        admin.setAdminId(rs.getInt("admin_id"));
        admin.setUserId(rs.getInt("user_id"));
        admin.setActive(rs.getBoolean("is_active"));
        return admin;
    }

    @Override
    public Optional<Admin> findById(int id) {
        return queryOne(SELECT + " WHERE admin_id = ?", MAPPER, id);
    }

    @Override
    public List<Admin> findAll() {
        return queryList(SELECT + " ORDER BY admin_id", MAPPER);
    }

    /** Finds the admin record attached to a login account. */
    public Optional<Admin> findByUserId(int userId) {
        return queryOne(SELECT + " WHERE user_id = ?", MAPPER, userId);
    }

    @Override
    public int insert(Admin entity) {
        return insertAndReturnKey(INSERT, entity.getUserId(), entity.isActive());
    }

    @Override
    public int insert(Connection connection, Admin entity) {
        return insertAndReturnKey(connection, INSERT, entity.getUserId(), entity.isActive());
    }

    @Override
    public boolean update(Admin entity) {
        return executeUpdate(UPDATE, entity.isActive(), entity.getAdminId()) > 0;
    }

    @Override
    public boolean update(Connection connection, Admin entity) {
        return executeUpdate(connection, UPDATE, entity.isActive(), entity.getAdminId()) > 0;
    }

    @Override
    public boolean deleteById(int id) {
        return executeUpdate(DELETE, id) > 0;
    }

    @Override
    public boolean deleteById(Connection connection, int id) {
        return executeUpdate(connection, DELETE, id) > 0;
    }
}
