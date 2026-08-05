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
 * reads the assigned id straight back out of {@code OUTPUT INSERTED.user_id}.</p>
 *
 * <p>This class moves password hashes around but never judges them; that is
 * {@link PasswordHasher}'s job.</p>
 */
public class UserDAO extends AbstractDAO implements GenericDAO<User> {

    private static final String SELECT =
            "SELECT user_id, username, email, password_hash, role, is_active, last_login, created_at "
            + "FROM dbo.users";

    /**
     * {@code OUTPUT INSERTED.user_id} makes SQL Server return the value its
     * IDENTITY column just assigned, in the same round trip and the same
     * transaction as the insert. It is exact under concurrency in a way that
     * looking the row up afterwards never is.
     */
    private static final String INSERT =
            "INSERT INTO dbo.users (username, email, password_hash, role, is_active) "
            + "OUTPUT INSERTED.user_id "
            + "VALUES (?, ?, ?, ?, ?)";

    /**
     * Column-filler only: {@code password_hash} is NOT NULL, but the real
     * value can't be computed until the IDENTITY column assigns a user_id.
     * {@link #finalizePassword} overwrites it immediately after the row exists.
     */
    private static final String PLACEHOLDER_HASH = "PENDING-DEFAULT-PASSWORD";

    private static final String UPDATE =
            "UPDATE dbo.users SET username = ?, email = ?, password_hash = ?, role = ?, is_active = ? "
            + "WHERE user_id = ?";

    private static final String DELETE = "DELETE FROM dbo.users WHERE user_id = ?";

    private static final RowMapper<User> MAPPER = UserDAO::mapRow;

    static User mapRow(ResultSet rs) throws SQLException {
        User user = new User();
        user.setUserId(rs.getInt("user_id"));
        user.setUsername(rs.getString("username"));
        user.setEmail(rs.getString("email"));
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

    /** Finds an account by its university email address, which is unique system-wide. */
    public Optional<User> findByEmail(String email) {
        return email == null ? Optional.empty() : queryOne(SELECT + " WHERE email = ?", MAPPER, email);
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
     * <p>{@code students.email} and {@code instructors.email} are checked as
     * well as {@code users.email}. The application keeps all three in step, so
     * the extra two lookups should never be the ones that fire — but each of
     * those columns carries its own UNIQUE index, and finding the clash here
     * turns a raw constraint violation into a sentence the administrator can
     * act on.</p>
     */
    public boolean emailExists(Connection connection, String email, Integer excludeUserId) {
        if (email == null || email.isBlank()) {
            return false;
        }
        // No account has user_id 0 (IDENTITY starts at 1), so 0 excludes nothing.
        int exclude = excludeUserId == null ? 0 : excludeUserId;
        return count(connection, "SELECT COUNT(*) FROM dbo.users WHERE email = ? AND user_id <> ?",
                        email, exclude)
                + count(connection, "SELECT COUNT(*) FROM dbo.students WHERE email = ? AND user_id <> ?",
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
     * Changes the university email address of an existing account, and nothing
     * else — not the id, not the role, not the password.
     */
    public boolean updateEmail(Connection connection, int userId, String email) {
        return executeUpdate(connection, "UPDATE dbo.users SET email = ? WHERE user_id = ?",
                email, userId) > 0;
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
     * @return the {@code user_id} SQL Server generated, read back from
     *         {@code OUTPUT INSERTED.user_id} — never computed here
     */
    @Override
    public int insert(Connection connection, User entity) {
        int userId = queryOne(connection, INSERT, rs -> rs.getInt(1), insertParams(entity))
                .orElseThrow(() -> new DataAccessException(
                        "The database did not return a generated user_id: " + INSERT));
        entity.setUserId(userId);
        entity.setPasswordHash(PLACEHOLDER_HASH);
        return userId;
    }

    /**
     * Assigns the mandatory initial password for a newly created account:
     * BCrypt of {@code <user_id>@iuL}. Called once the identity column has
     * handed out the id, inside the transaction that created the row.
     */
    public void finalizePassword(Connection connection, int userId) {
        String defaultHash = PasswordHasher.hashDefaultPassword(userId);
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
                entity.getEmail(),
                PLACEHOLDER_HASH,
                entity.getRole(),
                entity.isActive()
        };
    }

    private Object[] updateParams(User entity) {
        return new Object[]{
                entity.getUsername(),
                entity.getEmail(),
                entity.getPasswordHash(),
                entity.getRole(),
                entity.isActive(),
                entity.getUserId()
        };
    }
}
