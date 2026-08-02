package com.university.dao;

import com.university.enums.AuditActionType;
import com.university.model.AuditLog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code dbo.audit_log}.
 *
 * <p>Read-only on purpose. Those rows are written by database triggers, so an
 * insert from Java would either duplicate what a trigger already recorded or
 * record something that never happened. This class therefore offers no
 * insert, update or delete, and does not implement {@link GenericDAO}.</p>
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
}
