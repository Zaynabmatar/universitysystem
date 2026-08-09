package com.university.dao;

import com.university.enums.AttendanceStatus;
import com.university.model.AttendanceRecord;
import com.university.model.AttendanceRow;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.attendance_records}.
 *
 * <p>{@link #upsert} is the write path the screen actually uses: it updates the existing row for
 * (enrollment, date) when one exists, otherwise inserts one. {@code UQ_attendance_records_enrollment_date}
 * is the backstop that makes a duplicate impossible even if two saves raced.</p>
 */
public class AttendanceDAO extends AbstractDAO implements GenericDAO<AttendanceRecord> {

    private static final String SELECT =
            "SELECT attendance_id, enrollment_id, section_id, class_date, status, recorded_by, "
            + "recorded_at FROM dbo.attendance_records";

    private static final String INSERT =
            "INSERT INTO dbo.attendance_records (enrollment_id, section_id, class_date, status, "
            + "recorded_by) VALUES (?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.attendance_records SET enrollment_id = ?, section_id = ?, class_date = ?, "
            + "status = ?, recorded_by = ?, recorded_at = SYSDATETIME() WHERE attendance_id = ?";

    private static final String DELETE = "DELETE FROM dbo.attendance_records WHERE attendance_id = ?";

    private static final RowMapper<AttendanceRecord> MAPPER = AttendanceDAO::mapRow;

    static AttendanceRecord mapRow(ResultSet rs) throws SQLException {
        AttendanceRecord record = new AttendanceRecord();
        record.setAttendanceId(rs.getInt("attendance_id"));
        record.setEnrollmentId(rs.getInt("enrollment_id"));
        record.setSectionId(rs.getInt("section_id"));
        record.setClassDate(DaoUtils.getLocalDate(rs, "class_date"));
        record.setStatus(AttendanceStatus.fromDb(rs.getString("status")));
        record.setRecordedBy(rs.getInt("recorded_by"));
        record.setRecordedAt(DaoUtils.getLocalDateTime(rs, "recorded_at"));
        return record;
    }

    @Override
    public Optional<AttendanceRecord> findById(int id) {
        return queryOne(SELECT + " WHERE attendance_id = ?", MAPPER, id);
    }

    @Override
    public List<AttendanceRecord> findAll() {
        return queryList(SELECT + " ORDER BY class_date DESC", MAPPER);
    }

    /** The record for one student's one meeting, if one has been saved yet. */
    public Optional<AttendanceRecord> findByEnrollmentAndDate(int enrollmentId, LocalDate classDate) {
        return queryOne(SELECT + " WHERE enrollment_id = ? AND class_date = ?",
                MAPPER, enrollmentId, classDate);
    }

    /**
     * The instructor's attendance sheet for one section on one date: every ENROLLED or COMPLETED
     * student in the section, joined with whatever attendance row already exists for them on that
     * date. A student with no row yet defaults to PRESENT — that is what the LEFT JOIN plus the
     * ISNULL are for.
     */
    public List<AttendanceRow> findRosterForDate(int sectionId, LocalDate classDate) {
        String sql = "SELECT e.enrollment_id, e.student_id, st.user_id, st.first_name, st.last_name, "
                + "c.course_code, s.section_number, a.attendance_id, a.status "
                + "FROM dbo.enrollments e "
                + "INNER JOIN dbo.students st ON st.student_id = e.student_id "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.courses c ON c.course_id = s.course_id "
                + "LEFT JOIN dbo.attendance_records a "
                + "  ON a.enrollment_id = e.enrollment_id AND a.class_date = ? "
                + "WHERE e.section_id = ? AND e.status IN ('ENROLLED', 'COMPLETED') "
                + "ORDER BY st.user_id";
        return queryList(sql, rs -> {
            AttendanceRow row = new AttendanceRow();
            row.setEnrollmentId(rs.getInt("enrollment_id"));
            row.setStudentId(rs.getInt("student_id"));
            row.setStudentUserId(rs.getInt("user_id"));
            row.setStudentName(rs.getString("first_name") + " " + rs.getString("last_name"));
            row.setSectionLabel(rs.getString("course_code") + "-" + rs.getString("section_number"));
            row.setAttendanceId(DaoUtils.getInteger(rs, "attendance_id"));
            String status = rs.getString("status");
            row.setStatus(status == null ? AttendanceStatus.PRESENT : AttendanceStatus.fromDb(status));
            return row;
        }, classDate, sectionId);
    }

    /**
     * Writes one student's mark for one date: updates the existing row for this
     * (enrollment, date) if there is one, otherwise inserts a new one. Never creates a second row
     * for the same student on the same date.
     */
    public void upsert(Connection connection, AttendanceRecord record) {
        int updated = executeUpdate(connection,
                "UPDATE dbo.attendance_records SET status = ?, recorded_by = ?, "
                + "recorded_at = SYSDATETIME() WHERE enrollment_id = ? AND class_date = ?",
                record.getStatus(), record.getRecordedBy(), record.getEnrollmentId(), record.getClassDate());
        if (updated == 0) {
            insertAndReturnKey(connection, INSERT, record.getEnrollmentId(), record.getSectionId(),
                    record.getClassDate(), record.getStatus(), record.getRecordedBy());
        }
    }

    @Override
    public int insert(AttendanceRecord entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, AttendanceRecord entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(AttendanceRecord entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, AttendanceRecord entity) {
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

    private Object[] insertParams(AttendanceRecord entity) {
        return new Object[]{
                entity.getEnrollmentId(),
                entity.getSectionId(),
                entity.getClassDate(),
                entity.getStatus(),
                entity.getRecordedBy()
        };
    }

    private Object[] updateParams(AttendanceRecord entity) {
        return new Object[]{
                entity.getEnrollmentId(),
                entity.getSectionId(),
                entity.getClassDate(),
                entity.getStatus(),
                entity.getRecordedBy(),
                entity.getAttendanceId()
        };
    }
}
