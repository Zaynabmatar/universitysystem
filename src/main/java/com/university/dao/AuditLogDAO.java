package com.university.dao;

import com.university.enums.AuditActionType;
import com.university.model.AuditLog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code dbo.audit_log}. Nothing here writes to it.
 *
 * <p>Every row is written by a database trigger — see the {@code trg_*_Audit}
 * triggers on {@code dbo.grades}, {@code dbo.users}, {@code dbo.students},
 * {@code dbo.instructors}, {@code dbo.courses}, {@code dbo.semesters},
 * {@code dbo.enrollments} and {@code dbo.payments} (migrations
 * {@code phase11_grades_scale.sql} and {@code 0014_audit_log_triggers.sql}).
 * An insert from Java would either duplicate what a trigger already recorded
 * or record something that never happened, so this class implements no
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

        return resolveDisplayIds(queryList(sql.toString(), AuditLogDAO::mapSearchRow, params.toArray()));
    }

    // ────────────────────────────────────────────────────────────────────────────────
    //  Display-only ID resolution.
    //
    //  trg_Enrollment_Audit and trg_Payment_Audit (0014_audit_log_triggers.sql) and
    //  trg_Section_Audit (0021_section_audit_trigger.sql) write the internal
    //  dbo.students.student_id / dbo.instructors.instructor_id values into
    //  old_value/new_value/description - not users.user_id, the "Student ID" /
    //  "Instructor ID" every other screen shows (see trg_Student_Audit's and
    //  trg_Instructor_Audit's own comments in that same migration). Rewriting that in
    //  the trigger would mean touching the audit triggers themselves, so instead this
    //  swaps the id actually shown, after the row is read, leaving audit_log, record_id
    //  and every trigger untouched.
    // ────────────────────────────────────────────────────────────────────────────────

    private static final Pattern STUDENT_ID_FIELD = Pattern.compile("student_id[ =](\\d+)");
    private static final Pattern STUDENT_WORD = Pattern.compile("(?i)\\bstudent (\\d+)\\b");
    private static final Pattern INSTRUCTOR_ID_FIELD = Pattern.compile("instructor_id=(\\d+)");

    private List<AuditLog> resolveDisplayIds(List<AuditLog> rows) {
        boolean hasStudentRows = rows.stream()
                .anyMatch(r -> "enrollments".equals(r.getTableName()) || "payments".equals(r.getTableName()));
        boolean hasSectionRows = rows.stream().anyMatch(r -> "sections".equals(r.getTableName()));
        if (!hasStudentRows && !hasSectionRows) {
            return rows;
        }

        Map<Integer, Integer> studentUserIds =
                hasStudentRows ? idMap("SELECT student_id, user_id FROM dbo.students") : Map.of();
        Map<Integer, Integer> instructorUserIds =
                hasSectionRows ? idMap("SELECT instructor_id, user_id FROM dbo.instructors") : Map.of();

        for (AuditLog row : rows) {
            if ("enrollments".equals(row.getTableName()) || "payments".equals(row.getTableName())) {
                row.setDescription(rewriteStudentIds(row.getDescription(), studentUserIds));
                row.setOldValue(rewriteStudentIds(row.getOldValue(), studentUserIds));
                row.setNewValue(rewriteStudentIds(row.getNewValue(), studentUserIds));
            } else if ("sections".equals(row.getTableName())) {
                row.setDescription(replaceIds(row.getDescription(), INSTRUCTOR_ID_FIELD, instructorUserIds));
                row.setOldValue(replaceIds(row.getOldValue(), INSTRUCTOR_ID_FIELD, instructorUserIds));
                row.setNewValue(replaceIds(row.getNewValue(), INSTRUCTOR_ID_FIELD, instructorUserIds));
            }
        }
        return rows;
    }

    private static String rewriteStudentIds(String text, Map<Integer, Integer> studentUserIds) {
        text = replaceIds(text, STUDENT_ID_FIELD, studentUserIds);
        text = replaceIds(text, STUDENT_WORD, studentUserIds);
        return text;
    }

    /** Replaces every id captured by {@code pattern}'s group 1, unresolved ids left as-is. */
    private static String replaceIds(String text, Pattern pattern, Map<Integer, Integer> idMap) {
        if (text == null || text.isEmpty() || idMap.isEmpty()) {
            return text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Integer userId = idMap.get(Integer.parseInt(matcher.group(1)));
            String replacement = userId == null
                    ? matcher.group(0)
                    : matcher.group(0).replace(matcher.group(1), String.valueOf(userId));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Map<Integer, Integer> idMap(String sql) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int[] pair : queryList(sql, rs -> new int[] { rs.getInt(1), rs.getInt(2) })) {
            map.put(pair[0], pair[1]);
        }
        return map;
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
