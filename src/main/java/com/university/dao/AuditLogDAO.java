package com.university.dao;

import com.university.enums.AuditActionType;
import com.university.model.AuditLog;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code dbo.audit_log}, and writes the one kind of row no trigger can.
 *
 * <p>Almost everything here is read-only on purpose: those rows are written by
 * database triggers, and an insert from Java would either duplicate what a
 * trigger already recorded or record something that never happened. The class
 * therefore implements no update or delete, and does not implement
 * {@link GenericDAO}.</p>
 *
 * <p>{@link #record} is the exception. Account administration — creating a
 * student or instructor account, changing its email, resetting its password,
 * deactivating and reactivating it — is a decision an administrator made, and
 * only the application knows who made it. {@code dbo.users},
 * {@code dbo.students} and {@code dbo.instructors} carry no audit trigger
 * (only {@code dbo.grades} does), so nothing here can be a duplicate.</p>
 */
public class AuditLogDAO extends AbstractDAO {

    private static final String SELECT =
            "SELECT log_id, user_id, action_type, table_name, record_id, old_value, new_value, "
            + "description, created_at FROM dbo.audit_log";

    private static final RowMapper<AuditLog> MAPPER = AuditLogDAO::mapRow;

    static AuditLog mapRow(ResultSet rs) throws SQLException {
        AuditLog log = new AuditLog();
        log.setLogId(rs.getInt("log_id"));
        // Null when a background job or somebody working directly in the
        // database made the change, so there is no application user.
        log.setUserId(DaoUtils.getInteger(rs, "user_id"));
        log.setActionType(AuditActionType.fromDb(rs.getString("action_type")));
        log.setTableName(rs.getString("table_name"));
        log.setRecordId(DaoUtils.getInteger(rs, "record_id"));
        log.setOldValue(rs.getString("old_value"));
        log.setNewValue(rs.getString("new_value"));
        log.setDescription(rs.getString("description"));
        log.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        return log;
    }

    private static final String INSERT =
            "INSERT INTO dbo.audit_log "
            + "(user_id, action_type, table_name, record_id, old_value, new_value, description) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    /**
     * Records one administrative action against an account.
     *
     * <p>Never pass a password, hashed or otherwise, in {@code oldValue},
     * {@code newValue} or {@code description}. A reset is recorded as the
     * fact that it happened and to whom, and that is all the log is allowed
     * to know.</p>
     *
     * @param connection the transaction the audited change is part of, so the
     *                   log entry lives or dies with it; null to log on its own
     * @param actorUserId the administrator who did it, or null if unknown
     * @param recordId   the {@code user_id} of the account acted upon
     */
    public void record(Connection connection, Integer actorUserId, AuditActionType actionType,
                       String tableName, Integer recordId, String oldValue, String newValue,
                       String description) {
        Object[] params = {actorUserId, actionType, tableName, recordId, oldValue, newValue, description};
        if (connection == null) {
            executeUpdate(INSERT, params);
        } else {
            executeUpdate(connection, INSERT, params);
        }
    }

    /** Finds one entry by its key. */
    public Optional<AuditLog> findById(int logId) {
        return queryOne(SELECT + " WHERE log_id = ?", MAPPER, logId);
    }

    /** The most recent entries, newest first. */
    public List<AuditLog> findLatest(int howMany) {
        return queryList("SELECT TOP (?) log_id, user_id, action_type, table_name, record_id, "
                + "old_value, new_value, description, created_at FROM dbo.audit_log "
                + "ORDER BY created_at DESC", MAPPER, howMany);
    }

    /** Everything one person has caused. */
    public List<AuditLog> findByUser(int userId) {
        return queryList(SELECT + " WHERE user_id = ? ORDER BY created_at DESC", MAPPER, userId);
    }

    /** The history of one table. */
    public List<AuditLog> findByTable(String tableName) {
        return queryList(SELECT + " WHERE table_name = ? ORDER BY created_at DESC",
                MAPPER, tableName);
    }

    /** The history of one row, which is the usual investigation. */
    public List<AuditLog> findByRecord(String tableName, int recordId) {
        return queryList(SELECT + " WHERE table_name = ? AND record_id = ? "
                + "ORDER BY created_at DESC", MAPPER, tableName, recordId);
    }

    /** Everything of one kind, for example every deletion. */
    public List<AuditLog> findByActionType(AuditActionType actionType) {
        return queryList(SELECT + " WHERE action_type = ? ORDER BY created_at DESC",
                MAPPER, actionType);
    }

    /** Everything recorded in a date range. */
    public List<AuditLog> findBetween(LocalDate from, LocalDate to) {
        return queryList(SELECT + " WHERE CAST(created_at AS DATE) BETWEEN ? AND ? "
                + "ORDER BY created_at DESC", MAPPER, from, to);
    }

    /** How many entries a table has collected. */
    public int countByTable(String tableName) {
        return queryInt("SELECT COUNT(*) FROM dbo.audit_log WHERE table_name = ?", tableName);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    //  Phase 15 — the audit log viewer. Everything below this line is new.
    // ────────────────────────────────────────────────────────────────────────────────

    /** The screen's four filters plus a free-text search. Any field left null means "all". */
    public static final class Filter {
        public String tableName;     // null for all
        public AuditActionType actionType;   // null for all
        public Integer userId;       // null for all users
        public LocalDate dateFrom;   // inclusive, null for no lower bound
        public LocalDate dateTo;     // inclusive, null for no upper bound
        public String searchText;    // matches the description/old/new value, null for no filter
        public int maxRows = 500;
    }

    /**
     * The filtered, searchable view of the log, newest first, with the acting user's name
     * joined in. Every filter value is a bound parameter — this is exactly the screen where
     * getting SQL injection wrong would be unforgivable, since every value comes from what an
     * administrator typed or picked.
     */
    public List<AuditLog> search(Filter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT TOP (" + clamp(filter.maxRows) + ") "
                + "a.log_id, a.user_id, u.username, a.action_type, a.table_name, a.record_id, "
                + "a.old_value, a.new_value, a.description, a.created_at "
                + "FROM dbo.audit_log a "
                + "LEFT JOIN dbo.users u ON u.user_id = a.user_id "
                + "WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();

        if (filter.tableName != null && !filter.tableName.isBlank()) {
            sql.append("AND a.table_name = ? ");
            params.add(filter.tableName);
        }
        if (filter.actionType != null) {
            sql.append("AND a.action_type = ? ");
            params.add(filter.actionType.toDb());
        }
        if (filter.userId != null) {
            sql.append("AND a.user_id = ? ");
            params.add(filter.userId);
        }
        if (filter.dateFrom != null) {
            sql.append("AND a.created_at >= ? ");
            params.add(Timestamp.valueOf(filter.dateFrom.atStartOfDay()));
        }
        if (filter.dateTo != null) {
            // "to" is INCLUSIVE: everything before midnight at the start of the NEXT day.
            // "<= dateTo" would silently drop every entry made later on that last day.
            sql.append("AND a.created_at < ? ");
            params.add(Timestamp.valueOf(filter.dateTo.plusDays(1).atStartOfDay()));
        }
        if (filter.searchText != null && !filter.searchText.isBlank()) {
            sql.append("AND (a.description LIKE ? OR a.old_value LIKE ? OR a.new_value LIKE ?) ");
            String like = "%" + filter.searchText.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        sql.append("ORDER BY a.log_id DESC");

        return queryList(sql.toString(), AuditLogDAO::mapSearchRow, params.toArray());
    }

    /** The distinct table names actually present in the log, for the filter drop-down. */
    public List<String> distinctTableNames() {
        return distinctColumn("SELECT DISTINCT table_name FROM dbo.audit_log ORDER BY table_name",
                "table_name");
    }

    /** The distinct action types actually present in the log, for the filter drop-down. */
    public List<String> distinctActionTypes() {
        return distinctColumn("SELECT DISTINCT action_type FROM dbo.audit_log ORDER BY action_type",
                "action_type");
    }

    private List<String> distinctColumn(String sql, String column) {
        return queryList(sql, resultSet -> resultSet.getNString(column));
    }

    private static AuditLog mapSearchRow(ResultSet rs) throws SQLException {
        AuditLog log = mapRow(rs);
        String username = rs.getNString("username");
        // A null user means the change was made outside the application — directly in SSMS.
        // That is not a bug; it is the strongest evidence that a TRIGGER wrote this row.
        log.setUsername(username == null ? "System / SSMS" : username);
        return log;
    }

    /** {@code TOP (?)} is not accepted in every context, so the count is clamped and inlined. */
    private String clamp(int n) {
        int bounded = n;
        if (bounded < 1) {
            bounded = 1;
        }
        if (bounded > 5000) {
            bounded = 5000;
        }
        return Integer.toString(bounded);
    }
}
