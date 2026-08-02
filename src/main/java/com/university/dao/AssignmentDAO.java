package com.university.dao;

import com.university.enums.AssignmentStatus;
import com.university.model.Assignment;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.assignments}.
 */
public class AssignmentDAO extends AbstractDAO implements GenericDAO<Assignment> {

    private static final String SELECT =
            "SELECT assignment_id, section_id, title, description, assigned_date, due_date, "
            + "maximum_mark, created_by, status, created_at FROM dbo.assignments";

    private static final String INSERT =
            "INSERT INTO dbo.assignments (section_id, title, description, assigned_date, due_date, "
            + "maximum_mark, created_by, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.assignments SET title = ?, description = ?, assigned_date = ?, "
            + "due_date = ?, maximum_mark = ?, status = ? WHERE assignment_id = ?";

    private static final String DELETE = "DELETE FROM dbo.assignments WHERE assignment_id = ?";

    private static final RowMapper<Assignment> MAPPER = AssignmentDAO::mapRow;

    static Assignment mapRow(ResultSet rs) throws SQLException {
        Assignment assignment = new Assignment();
        assignment.setAssignmentId(rs.getInt("assignment_id"));
        assignment.setSectionId(rs.getInt("section_id"));
        assignment.setTitle(rs.getString("title"));
        assignment.setDescription(rs.getString("description"));
        assignment.setAssignedDate(DaoUtils.getLocalDateTime(rs, "assigned_date"));
        assignment.setDueDate(DaoUtils.getLocalDateTime(rs, "due_date"));
        assignment.setMaximumMark(rs.getBigDecimal("maximum_mark"));
        assignment.setCreatedBy(rs.getInt("created_by"));
        assignment.setStatus(AssignmentStatus.fromDb(rs.getString("status")));
        assignment.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        return assignment;
    }

    @Override
    public Optional<Assignment> findById(int id) {
        return queryOne(SELECT + " WHERE assignment_id = ?", MAPPER, id);
    }

    @Override
    public List<Assignment> findAll() {
        return queryList(SELECT + " ORDER BY due_date DESC", MAPPER);
    }

    /** The work set for one section. */
    public List<Assignment> findBySection(int sectionId) {
        return queryList(SELECT + " WHERE section_id = ? ORDER BY due_date", MAPPER, sectionId);
    }

    /** The work still open in one section. */
    public List<Assignment> findActiveBySection(int sectionId) {
        return queryList(SELECT + " WHERE section_id = ? AND status = 'ACTIVE' ORDER BY due_date",
                MAPPER, sectionId);
    }

    /**
     * Everything one student has been set this semester, across all their
     * sections.
     */
    public List<Assignment> findByStudentAndSemester(int studentId, int semesterId) {
        return queryList("SELECT a.assignment_id, a.section_id, a.title, a.description, "
                        + "a.assigned_date, a.due_date, a.maximum_mark, a.created_by, a.status, "
                        + "a.created_at FROM dbo.assignments a "
                        + "INNER JOIN dbo.sections s ON s.section_id = a.section_id "
                        + "INNER JOIN dbo.enrollments e ON e.section_id = s.section_id "
                        + "WHERE e.student_id = ? AND s.semester_id = ? AND e.status = 'ENROLLED' "
                        + "AND a.status = 'ACTIVE' ORDER BY a.due_date",
                MAPPER, studentId, semesterId);
    }

    /** Work due in the next few days, for the reminder panel. */
    public List<Assignment> findDueWithinDays(int studentId, int days) {
        return queryList("SELECT a.assignment_id, a.section_id, a.title, a.description, "
                        + "a.assigned_date, a.due_date, a.maximum_mark, a.created_by, a.status, "
                        + "a.created_at FROM dbo.assignments a "
                        + "INNER JOIN dbo.enrollments e ON e.section_id = a.section_id "
                        + "WHERE e.student_id = ? AND e.status = 'ENROLLED' "
                        + "AND a.status = 'ACTIVE' "
                        + "AND a.due_date BETWEEN GETDATE() AND DATEADD(DAY, ?, GETDATE()) "
                        + "ORDER BY a.due_date",
                MAPPER, studentId, days);
    }

    /** Everything one instructor has set. */
    public List<Assignment> findByCreator(int createdByUserId) {
        return queryList(SELECT + " WHERE created_by = ? ORDER BY due_date DESC",
                MAPPER, createdByUserId);
    }

    /** Closes or cancels an assignment. */
    public boolean setStatus(int assignmentId, AssignmentStatus status) {
        return executeUpdate("UPDATE dbo.assignments SET status = ? WHERE assignment_id = ?",
                status, assignmentId) > 0;
    }

    @Override
    public int insert(Assignment entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Assignment entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Assignment entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Assignment entity) {
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

    private Object[] insertParams(Assignment entity) {
        return new Object[]{
                entity.getSectionId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getAssignedDate(),
                entity.getDueDate(),
                entity.getMaximumMark(),
                entity.getCreatedBy(),
                entity.getStatus()
        };
    }

    private Object[] updateParams(Assignment entity) {
        return new Object[]{
                entity.getTitle(),
                entity.getDescription(),
                entity.getAssignedDate(),
                entity.getDueDate(),
                entity.getMaximumMark(),
                entity.getStatus(),
                entity.getAssignmentId()
        };
    }
}
