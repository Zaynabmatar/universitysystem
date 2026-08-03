package com.university.dao;

import com.university.enums.UserRole;
import com.university.model.User;
import com.university.service.PasswordHasher;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.users}.
 *
 * <p>This class moves password hashes around but never judges them. Checking
 * a password against {@code password_hash} needs a BCrypt library, which the
 * build does not yet have, so verification belongs to a later phase.</p>
 */
public class UserDAO extends AbstractDAO implements GenericDAO<User> {

    private static final String SELECT =
            "SELECT user_id, username, password_hash, role, is_active, last_login, created_at "
            + "FROM dbo.users";

    private static final String INSERT =
            "INSERT INTO dbo.users (username, password_hash, role, is_active) VALUES (?, ?, ?, ?)";

    /**
     * Column-filler only: {@code password_hash} is NOT NULL, but the real
     * value can't be computed until the IDENTITY column assigns a user_id.
     * {@link #insert} overwrites this immediately after the row exists.
     */
    private static final String PLACEHOLDER_HASH = "PENDING-DEFAULT-PASSWORD";

    private static final String UPDATE =
            "UPDATE dbo.users SET username = ?, password_hash = ?, role = ?, is_active = ? "
            + "WHERE user_id = ?";

    private static final String DELETE = "DELETE FROM dbo.users WHERE user_id = ?";

    private static final RowMapper<User> MAPPER = UserDAO::mapRow;

    static User mapRow(ResultSet rs) throws SQLException {
        User user = new User();
        user.setUserId(rs.getInt("user_id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setRole(UserRole.fromDb(rs.getString("role")));
        user.setActive(rs.getBoolean("is_active"));
        user.setLastLogin(DaoUtils.getLocalDateTime(rs, "last_login"));
        user.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        return user;
    }

    @Override
    public Optional<User> findById(int id) {
        return queryOne(SELECT + " WHERE user_id = ?", MAPPER, id);
    }

    @Override
    public List<User> findAll() {
        return queryList(SELECT + " ORDER BY username", MAPPER);
    }

    /** Every account holding one role. */
    public List<User> findByRole(UserRole role) {
        return queryList(SELECT + " WHERE role = ? ORDER BY username", MAPPER, role);
    }

    /** Stamps the moment of a successful sign-in. */
    public boolean touchLastLogin(int userId, LocalDateTime moment) {
        return executeUpdate("UPDATE dbo.users SET last_login = ? WHERE user_id = ?",
                moment, userId) > 0;
    }

    /** Replaces the stored hash. The caller supplies an already hashed value. */
    public boolean updatePasswordHash(int userId, String passwordHash) {
        return executeUpdate("UPDATE dbo.users SET password_hash = ? WHERE user_id = ?",
                passwordHash, userId) > 0;
    }

    /** Enables or disables an account without deleting it. */
    public boolean setActive(int userId, boolean active) {
        return executeUpdate("UPDATE dbo.users SET is_active = ? WHERE user_id = ?",
                active, userId) > 0;
    }

    /**
     * Inserts a new account and assigns its mandatory initial password:
     * BCrypt of {@code <user_id>@iuL}, where {@code user_id} is the key the
     * database just generated. Any password hash on {@code entity} is
     * ignored — this rule is not something a caller can opt out of.
     */
    @Override
    public int insert(User entity) {
        try (Connection connection = openConnection()) {
            return insert(connection, entity);
        } catch (SQLException e) {
            throw new DataAccessException("Could not close the connection used by: " + INSERT, e);
        }
    }

    @Override
    public int insert(Connection connection, User entity) {
        int userId = insertAndReturnKey(connection, INSERT, insertParams(entity));
        String defaultHash = PasswordHasher.hashDefaultPassword(userId);
        executeUpdate(connection, "UPDATE dbo.users SET password_hash = ? WHERE user_id = ?",
                defaultHash, userId);
        entity.setUserId(userId);
        entity.setPasswordHash(defaultHash);
        return userId;
    }

    @Override
    public boolean update(User entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, User entity) {
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

    private Object[] insertParams(User entity) {
        return new Object[]{
                entity.getUsername(),
                PLACEHOLDER_HASH,
                entity.getRole(),
                entity.isActive()
        };
    }

    private Object[] updateParams(User entity) {
        return new Object[]{
                entity.getUsername(),
                entity.getPasswordHash(),
                entity.getRole(),
                entity.isActive(),
                entity.getUserId()
        };
    }
}
