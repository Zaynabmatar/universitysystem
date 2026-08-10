package com.university.dao;

import com.university.enums.CalendarEventType;
import com.university.model.CalendarEvent;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.calendar_events} — Admin-authored Academic
 * Calendar entries only. Every other date the calendar shows (semester dates,
 * registration, drop/withdraw, exams, payment due dates) is read through its
 * own DAO, never through this one.
 */
public class CalendarEventDAO extends AbstractDAO implements GenericDAO<CalendarEvent> {

    private static final String SELECT =
            "SELECT event_id, semester_id, title, event_type, start_date, end_date, "
            + "description, is_active, created_by, created_at, updated_at FROM dbo.calendar_events";

    private static final String INSERT =
            "INSERT INTO dbo.calendar_events (semester_id, title, event_type, start_date, end_date, "
            + "description, is_active, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.calendar_events SET title = ?, event_type = ?, start_date = ?, end_date = ?, "
            + "description = ?, updated_at = ? WHERE event_id = ?";

    private static final String DELETE = "DELETE FROM dbo.calendar_events WHERE event_id = ?";

    private static final RowMapper<CalendarEvent> MAPPER = CalendarEventDAO::mapRow;

    static CalendarEvent mapRow(ResultSet rs) throws SQLException {
        CalendarEvent event = new CalendarEvent();
        event.setEventId(rs.getInt("event_id"));
        event.setSemesterId(rs.getInt("semester_id"));
        event.setTitle(rs.getString("title"));
        event.setType(CalendarEventType.fromDb(rs.getString("event_type")));
        event.setStartDate(DaoUtils.getLocalDate(rs, "start_date"));
        event.setEndDate(DaoUtils.getLocalDate(rs, "end_date"));
        event.setDescription(rs.getString("description"));
        event.setActive(rs.getBoolean("is_active"));
        event.setCreatedBy(DaoUtils.getInteger(rs, "created_by"));
        event.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        event.setUpdatedAt(DaoUtils.getLocalDateTime(rs, "updated_at"));
        return event;
    }

    @Override
    public Optional<CalendarEvent> findById(int id) {
        return queryOne(SELECT + " WHERE event_id = ?", MAPPER, id);
    }

    @Override
    public List<CalendarEvent> findAll() {
        return queryList(SELECT + " ORDER BY start_date", MAPPER);
    }

    /** Every custom event for one semester, active or not — what Admin's management table shows. */
    public List<CalendarEvent> findBySemester(int semesterId) {
        return queryList(SELECT + " WHERE semester_id = ? ORDER BY start_date", MAPPER, semesterId);
    }

    /** Only the active custom events for one semester — what the read-only Student calendar shows. */
    public List<CalendarEvent> findActiveBySemester(int semesterId) {
        return queryList(SELECT + " WHERE semester_id = ? AND is_active = 1 ORDER BY start_date",
                MAPPER, semesterId);
    }

    /** Flips an event's visibility without deleting its history. */
    public boolean setActive(int eventId, boolean active, LocalDateTime moment) {
        return executeUpdate("UPDATE dbo.calendar_events SET is_active = ?, updated_at = ? WHERE event_id = ?",
                active, moment, eventId) > 0;
    }

    @Override
    public int insert(CalendarEvent entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, CalendarEvent entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(CalendarEvent entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, CalendarEvent entity) {
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

    private Object[] insertParams(CalendarEvent entity) {
        return new Object[]{
                entity.getSemesterId(),
                entity.getTitle(),
                entity.getType(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getDescription(),
                entity.isActive(),
                entity.getCreatedBy()
        };
    }

    private Object[] updateParams(CalendarEvent entity) {
        return new Object[]{
                entity.getTitle(),
                entity.getType(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getDescription(),
                entity.getUpdatedAt(),
                entity.getEventId()
        };
    }
}
