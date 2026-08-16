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
 * Reads and writes {@code dbo.users} — the one account table, and the source
 * of the one identity every screen shows.
 *
 * <p>{@code user_id} is an {@code IDENTITY(1,1)} column: SQL Server alone
 * decides the next value. Nothing here computes one, and nothing anywhere
 * else may either — no {@code MAX(user_id) + 1}, no {@code COUNT(*)}, no
 * reading the last row. {@link #insert} hands the row to the database and
 * reads the assigned id back through {@link AbstractDAO#insertAndReturnKey},
 * the same {@code SCOPE_IDENTITY()}-based mechanism every other DAO in this
 * package uses — {@code OUTPUT INSERTED.user_id} without an {@code INTO}
 * clause is what this table used before {@code trg_User_Audit}
 * (migration {@code 0014_audit_log_triggers.sql}) started auditing it: SQL
 * Server refuses that exact form on any table that has an enabled trigger.</p>
 *
 * <p>This class moves password hashes around but never judges them; that is
 * {@link PasswordHasher}'s job.</p>
 */
public class UserDAO extends AbstractDAO implements GenericDAO<User> {

    // users.email / users.address are the ADMIN role's own contact details only
    // (see User.java). STUDENT/INSTRUCTOR keep using dbo.students / dbo.instructors
    // for theirs; this class never writes those columns for those two roles.
    private static final String SELECT =
            "SELECT user_id, username, password_hash, role, is_active, last_login, created_at, "
            + "email, address FROM dbo.users";

    private static final String INSERT =
            "INSERT INTO dbo.users (username, password_hash, role, is_active) VALUES (?, ?, ?, ?)";

    /**
     * Column-filler only: {@code password_hash} is NOT NULL, but the real
     * value can't be computed until the IDENTITY column assigns a user_id.
     * {@link #finalizePassword} overwrites it immediately after the row exists.
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
        user.setEmail(rs.getString("email"));
        user.setAddress(rs.getString("address"));
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

    /**
     * Is this address already spoken for?
     *
     * <p>The one place the question is asked, for both roles at once — that is
     * the whole reason the column lives on {@code users} rather than on
     * {@code students} and {@code instructors} separately.</p>
     *
     * @param excludeUserId the account being edited, so it never collides with
     *                      the address it already holds; null when creating
     */
    public boolean emailExists(String email, Integer excludeUserId) {
        return emailExists(null, email, excludeUserId);
    }

    /**
     * {@link #emailExists(String, Integer)} inside a transaction already running.
     *
     * <p>{@code dbo.users} has no {@code email} column of its own — the address
     * lives on {@code students.email} and {@code instructors.email} only, so
     * those are the two tables checked. Each carries its own UNIQUE index, and
     * finding the clash here turns a raw constraint violation into a sentence
     * the administrator can act on.</p>
     */
    public boolean emailExists(Connection connection, String email, Integer excludeUserId) {
        if (email == null || email.isBlank()) {
            return false;
        }
        // No account has user_id 0 (IDENTITY starts at 1), so 0 excludes nothing.
        int exclude = excludeUserId == null ? 0 : excludeUserId;
        return count(connection, "SELECT COUNT(*) FROM dbo.students WHERE email = ? AND user_id <> ?",
                        email, exclude)
                + count(connection, "SELECT COUNT(*) FROM dbo.instructors WHERE email = ? AND user_id <> ?",
                        email, exclude) > 0;
    }

    private int count(Connection connection, String sql, Object... params) {
        return connection == null ? queryInt(sql, params) : queryInt(connection, sql, params);
    }

    /** Is this login name already taken? Used only to keep the legacy username column unique. */
    public boolean usernameExists(Connection connection, String username) {
        return queryInt(connection, "SELECT COUNT(*) FROM dbo.users WHERE username = ?", username) > 0;
    }

    /**
     * No-op for STUDENT/INSTRUCTOR: their address lives on
     * {@code students.email} / {@code instructors.email}, and
     * {@code StudentDAO.update} / {@code InstructorDAO.update} already write it
     * there. {@code users.email} exists only for ADMIN's own contact details —
     * see {@link #updateContact} for that.
     */
    public boolean updateEmail(Connection connection, int userId, String email) {
        return false;
    }

    /**
     * Writes an ADMIN account's own email and address — the only role that
     * uses {@code users.email} / {@code users.address}. Deliberately narrower
     * than {@link #update(User)}, which also writes username/role/active, so a
     * self-service edit can never touch those.
     */
    public boolean updateContact(int userId, String email, String address) {
        return executeUpdate("UPDATE dbo.users SET email = ?, address = ? WHERE user_id = ?",
                email, address, userId) > 0;
    }

    /**
     * Is this address already used by another ADMIN account? The only place
     * {@code users.email} is checked for uniqueness — students and instructors
     * are checked separately by {@link #emailExists(String, Integer)}.
     */
    public boolean emailExistsAmongAdmins(String email, int excludeUserId) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return queryInt("SELECT COUNT(*) FROM dbo.users WHERE email = ? AND user_id <> ?",
                email, excludeUserId) > 0;
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
     * {@link #setActive(int, boolean)} inside a transaction already running, so
     * that disabling the login and marking the profile withdrawn either both
     * happen or neither does.
     */
    public boolean setActive(Connection connection, int userId, boolean active) {
        return executeUpdate(connection, "UPDATE dbo.users SET is_active = ? WHERE user_id = ?",
                active, userId) > 0;
    }

    /**
     * Inserts a new account with the placeholder hash still in place. Any
     * password hash on {@code entity} is ignored.
     *
     * <p>The mandatory initial password is {@code <user_id>@iuL}, and that id
     * does not exist until SQL Server's IDENTITY column assigns it during this
     * very statement. The caller finishes the job with
     * {@link #finalizePassword} on the returned id, inside the same
     * transaction, so an account can never be committed with the placeholder
     * still in the column.</p>
     */
    @Override
    public int insert(User entity) {
        try (Connection connection = openConnection()) {
            return insert(connection, entity);
        } catch (SQLException e) {
            throw new DataAccessException("Could not close the connection used by: " + INSERT, e);
        }
    }

    /**
     * @return the {@code user_id} SQL Server generated — never computed here
     */
    @Override
    public int insert(Connection connection, User entity) {
        int userId = insertAndReturnKey(connection, INSERT, insertParams(entity));
        entity.setUserId(userId);
        entity.setPasswordHash(PLACEHOLDER_HASH);
        return userId;
    }

    /**
     * Assigns the mandatory initial password for a newly created account —
     * BCrypt of the role's default ({@code <user_id>@iuL} for STUDENT,
     * {@code <user_id>@ADm} for ADMIN, {@code <user_id>@iNS} for INSTRUCTOR).
     * Called once the identity column has handed out the id, inside the
     * transaction that created the row.
     */
    public void finalizePassword(Connection connection, int userId, UserRole role) {
        String defaultHash = PasswordHasher.hashDefaultPassword(userId, role);
        executeUpdate(connection, "UPDATE dbo.users SET password_hash = ? WHERE user_id = ?",
                defaultHash, userId);
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
