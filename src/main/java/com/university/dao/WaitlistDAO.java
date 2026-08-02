package com.university.dao;

import com.university.enums.WaitlistStatus;
import com.university.model.Waitlist;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.waitlist}.
 *
 * <p>A row here is only legitimate while the section is full. When a seat
 * opens the person at the front is promoted, which is what
 * {@link #findNextInQueue} is for.</p>
 */
public class WaitlistDAO extends AbstractDAO implements GenericDAO<Waitlist> {

    private static final String SELECT =
            "SELECT waitlist_id, section_id, student_id, position, status, created_at "
            + "FROM dbo.waitlist";

    private static final String INSERT =
            "INSERT INTO dbo.waitlist (section_id, student_id, position, status) "
            + "VALUES (?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.waitlist SET section_id = ?, student_id = ?, position = ?, status = ? "
            + "WHERE waitlist_id = ?";

    private static final String DELETE = "DELETE FROM dbo.waitlist WHERE waitlist_id = ?";

    private static final RowMapper<Waitlist> MAPPER = WaitlistDAO::mapRow;

    static Waitlist mapRow(ResultSet rs) throws SQLException {
        Waitlist waitlist = new Waitlist();
        waitlist.setWaitlistId(rs.getInt("waitlist_id"));
        waitlist.setSectionId(rs.getInt("section_id"));
        waitlist.setStudentId(rs.getInt("student_id"));
        waitlist.setPosition(rs.getInt("position"));
        waitlist.setStatus(WaitlistStatus.fromDb(rs.getString("status")));
        waitlist.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        return waitlist;
    }

    @Override
    public Optional<Waitlist> findById(int id) {
        return queryOne(SELECT + " WHERE waitlist_id = ?", MAPPER, id);
    }

    @Override
    public List<Waitlist> findAll() {
        return queryList(SELECT + " ORDER BY section_id, position", MAPPER);
    }

    /** The whole queue for one section, front first. */
    public List<Waitlist> findBySection(int sectionId) {
        return queryList(SELECT + " WHERE section_id = ? ORDER BY position", MAPPER, sectionId);
    }

    /** The queues one student is standing in. */
    public List<Waitlist> findByStudent(int studentId) {
        return queryList(SELECT + " WHERE student_id = ? AND status = 'WAITING' "
                + "ORDER BY created_at", MAPPER, studentId);
    }

    /** The entry linking one student to one queue, if any. */
    public Optional<Waitlist> findByStudentAndSection(int studentId, int sectionId) {
        return queryOne(SELECT + " WHERE student_id = ? AND section_id = ?",
                MAPPER, studentId, sectionId);
    }

    /** The person who should get the next free seat. */
    public Optional<Waitlist> findNextInQueue(int sectionId) {
        return queryOne("SELECT TOP 1 waitlist_id, section_id, student_id, position, status, "
                + "created_at FROM dbo.waitlist WHERE section_id = ? AND status = 'WAITING' "
                + "ORDER BY position", MAPPER, sectionId);
    }

    /** The position a new arrival should take. */
    public int nextPosition(int sectionId) {
        return queryInt("SELECT ISNULL(MAX(position), 0) + 1 FROM dbo.waitlist WHERE section_id = ?",
                sectionId);
    }

    /** The position a new arrival should take, inside a running transaction. */
    public int nextPosition(Connection connection, int sectionId) {
        return queryInt(connection,
                "SELECT ISNULL(MAX(position), 0) + 1 FROM dbo.waitlist WHERE section_id = ?",
                sectionId);
    }

    /** How many people are still waiting for a section. */
    public int countWaiting(int sectionId) {
        return queryInt("SELECT COUNT(*) FROM dbo.waitlist "
                + "WHERE section_id = ? AND status = 'WAITING'", sectionId);
    }

    /** Marks an entry as promoted or cancelled. */
    public boolean setStatus(Connection connection, int waitlistId, WaitlistStatus status) {
        return executeUpdate(connection,
                "UPDATE dbo.waitlist SET status = ? WHERE waitlist_id = ?", status, waitlistId) > 0;
    }

    /**
     * Closes the gap after somebody leaves the queue.
     *
     * <p>Everybody standing behind the vacated position moves up one.</p>
     */
    public int closeGap(Connection connection, int sectionId, int vacatedPosition) {
        return executeUpdate(connection,
                "UPDATE dbo.waitlist SET position = position - 1 "
                + "WHERE section_id = ? AND status = 'WAITING' AND position > ?",
                sectionId, vacatedPosition);
    }

    @Override
    public int insert(Waitlist entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Waitlist entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Waitlist entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Waitlist entity) {
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

    private Object[] insertParams(Waitlist entity) {
        return new Object[]{
                entity.getSectionId(),
                entity.getStudentId(),
                entity.getPosition(),
                entity.getStatus()
        };
    }

    private Object[] updateParams(Waitlist entity) {
        return new Object[]{
                entity.getSectionId(),
                entity.getStudentId(),
                entity.getPosition(),
                entity.getStatus(),
                entity.getWaitlistId()
        };
    }
}
