package com.university.dao;

import com.university.enums.Term;
import com.university.model.Semester;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.semesters}.
 *
 * <p>A filtered unique index allows only one row to carry
 * {@code is_current = 1}, which is why {@link #findCurrent} returns a single
 * value and {@link #makeCurrent} clears the flag everywhere else first.</p>
 */
public class SemesterDAO extends AbstractDAO implements GenericDAO<Semester> {

    private static final String SELECT =
            "SELECT semester_id, semester_name, academic_year, term, start_date, end_date, "
            + "registration_start, registration_end, drop_deadline, withdraw_deadline, "
            + "grade_entry_start, grade_entry_end, is_current FROM dbo.semesters";

    private static final String INSERT =
            "INSERT INTO dbo.semesters (semester_name, academic_year, term, start_date, end_date, "
            + "registration_start, registration_end, drop_deadline, withdraw_deadline, "
            + "grade_entry_start, grade_entry_end, is_current) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.semesters SET semester_name = ?, academic_year = ?, term = ?, "
            + "start_date = ?, end_date = ?, registration_start = ?, registration_end = ?, "
            + "drop_deadline = ?, withdraw_deadline = ?, grade_entry_start = ?, "
            + "grade_entry_end = ? WHERE semester_id = ?";

    private static final String DELETE = "DELETE FROM dbo.semesters WHERE semester_id = ?";

    private static final RowMapper<Semester> MAPPER = SemesterDAO::mapRow;

    static Semester mapRow(ResultSet rs) throws SQLException {
        Semester semester = new Semester();
        semester.setSemesterId(rs.getInt("semester_id"));
        semester.setSemesterName(rs.getString("semester_name"));
        semester.setAcademicYear(rs.getString("academic_year"));
        semester.setTerm(Term.fromDb(rs.getString("term")));
        semester.setStartDate(DaoUtils.getLocalDate(rs, "start_date"));
        semester.setEndDate(DaoUtils.getLocalDate(rs, "end_date"));
        semester.setRegistrationStart(DaoUtils.getLocalDateTime(rs, "registration_start"));
        semester.setRegistrationEnd(DaoUtils.getLocalDateTime(rs, "registration_end"));
        semester.setDropDeadline(DaoUtils.getLocalDate(rs, "drop_deadline"));
        semester.setWithdrawDeadline(DaoUtils.getLocalDate(rs, "withdraw_deadline"));
        semester.setGradeEntryStart(DaoUtils.getLocalDate(rs, "grade_entry_start"));
        semester.setGradeEntryEnd(DaoUtils.getLocalDate(rs, "grade_entry_end"));
        semester.setCurrent(rs.getBoolean("is_current"));
        return semester;
    }

    @Override
    public Optional<Semester> findById(int id) {
        return queryOne(SELECT + " WHERE semester_id = ?", MAPPER, id);
    }

    @Override
    public List<Semester> findAll() {
        return queryList(SELECT + " ORDER BY start_date DESC", MAPPER);
    }

    /**
     * The semester the university is currently in.
     *
     * <p>Almost every screen starts here, so it is the most used query in the
     * layer.</p>
     */
    public Optional<Semester> findCurrent() {
        return queryOne(SELECT + " WHERE is_current = 1", MAPPER);
    }

    /** Finds a semester by its unique name, for example Fall 2025. */
    public Optional<Semester> findByName(String semesterName) {
        return queryOne(SELECT + " WHERE semester_name = ?", MAPPER, semesterName);
    }

    /** Every semester of one academic year, for example 2025-2026. */
    public List<Semester> findByAcademicYear(String academicYear) {
        return queryList(SELECT + " WHERE academic_year = ? ORDER BY start_date",
                MAPPER, academicYear);
    }

    /** The semesters whose registration window is open right now. */
    public List<Semester> findOpenForRegistration() {
        return queryList(SELECT + " WHERE GETDATE() BETWEEN registration_start AND registration_end "
                + "ORDER BY start_date", MAPPER);
    }

    /**
     * Moves the current flag to one semester.
     *
     * <p>Both statements run on one connection inside a transaction. The
     * filtered unique index rejects a second current row, so clearing the old
     * flag and setting the new one cannot be allowed to half succeed.</p>
     *
     * @return true when the flag was moved
     */
    public boolean makeCurrent(int semesterId) {
        Connection connection = beginTransaction();
        try {
            executeUpdate(connection, "UPDATE dbo.semesters SET is_current = 0 WHERE is_current = 1");
            int changed = executeUpdate(connection,
                    "UPDATE dbo.semesters SET is_current = 1 WHERE semester_id = ?", semesterId);
            connection.commit();
            return changed > 0;
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw new DataAccessException("Could not make semester " + semesterId + " current", e);
        } catch (DataAccessException e) {
            rollbackQuietly(connection);
            throw e;
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public int insert(Semester entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Semester entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Semester entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Semester entity) {
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

    private Object[] insertParams(Semester entity) {
        return new Object[]{
                entity.getSemesterName(),
                entity.getAcademicYear(),
                entity.getTerm(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getRegistrationStart(),
                entity.getRegistrationEnd(),
                entity.getDropDeadline(),
                entity.getWithdrawDeadline(),
                entity.getGradeEntryStart(),
                entity.getGradeEntryEnd(),
                entity.isCurrent()
        };
    }

    private Object[] updateParams(Semester entity) {
        return new Object[]{
                entity.getSemesterName(),
                entity.getAcademicYear(),
                entity.getTerm(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getRegistrationStart(),
                entity.getRegistrationEnd(),
                entity.getDropDeadline(),
                entity.getWithdrawDeadline(),
                entity.getGradeEntryStart(),
                entity.getGradeEntryEnd(),
                entity.getSemesterId()
        };
    }
}
