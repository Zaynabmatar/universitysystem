package com.university.dao;

import com.university.enums.DayOfWeekCode;
import com.university.model.SectionSchedule;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.section_schedules}.
 */
public class SectionScheduleDAO extends AbstractDAO implements GenericDAO<SectionSchedule> {

    private static final String SELECT =
            "SELECT schedule_id, section_id, day_of_week, start_time, end_time "
            + "FROM dbo.section_schedules";

    private static final String INSERT =
            "INSERT INTO dbo.section_schedules (section_id, day_of_week, start_time, end_time) "
            + "VALUES (?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.section_schedules SET section_id = ?, day_of_week = ?, start_time = ?, "
            + "end_time = ? WHERE schedule_id = ?";

    private static final String DELETE = "DELETE FROM dbo.section_schedules WHERE schedule_id = ?";

    private static final RowMapper<SectionSchedule> MAPPER = SectionScheduleDAO::mapRow;

    static SectionSchedule mapRow(ResultSet rs) throws SQLException {
        SectionSchedule schedule = new SectionSchedule();
        schedule.setScheduleId(rs.getInt("schedule_id"));
        schedule.setSectionId(rs.getInt("section_id"));
        schedule.setDayOfWeek(DayOfWeekCode.fromDb(rs.getString("day_of_week")));
        schedule.setStartTime(DaoUtils.getLocalTime(rs, "start_time"));
        schedule.setEndTime(DaoUtils.getLocalTime(rs, "end_time"));
        return schedule;
    }

    @Override
    public Optional<SectionSchedule> findById(int id) {
        return queryOne(SELECT + " WHERE schedule_id = ?", MAPPER, id);
    }

    @Override
    public List<SectionSchedule> findAll() {
        return queryList(SELECT + " ORDER BY section_id, start_time", MAPPER);
    }

    /** The weekly meetings of one section. */
    public List<SectionSchedule> findBySection(int sectionId) {
        return queryList(SELECT + " WHERE section_id = ? ORDER BY day_of_week, start_time",
                MAPPER, sectionId);
    }

    /**
     * The full weekly timetable of one student in one semester.
     *
     * <p>Joined through the enrolments, so it returns only the meetings the
     * student actually has to attend.</p>
     */
    public List<SectionSchedule> findByStudentAndSemester(int studentId, int semesterId) {
        return queryList("SELECT sch.schedule_id, sch.section_id, sch.day_of_week, "
                        + "sch.start_time, sch.end_time "
                        + "FROM dbo.section_schedules sch "
                        + "INNER JOIN dbo.sections s ON s.section_id = sch.section_id "
                        + "INNER JOIN dbo.enrollments e ON e.section_id = s.section_id "
                        + "WHERE e.student_id = ? AND s.semester_id = ? AND e.status = 'ENROLLED' "
                        + "ORDER BY sch.day_of_week, sch.start_time",
                MAPPER, studentId, semesterId);
    }

    /** The weekly timetable of one instructor in one semester. */
    public List<SectionSchedule> findByInstructorAndSemester(int instructorId, int semesterId) {
        return queryList("SELECT sch.schedule_id, sch.section_id, sch.day_of_week, "
                        + "sch.start_time, sch.end_time "
                        + "FROM dbo.section_schedules sch "
                        + "INNER JOIN dbo.sections s ON s.section_id = sch.section_id "
                        + "WHERE s.instructor_id = ? AND s.semester_id = ? "
                        + "ORDER BY sch.day_of_week, sch.start_time",
                MAPPER, instructorId, semesterId);
    }

    /**
     * Counts the meetings a student already has that would overlap this
     * section.
     *
     * <p>Two periods overlap when each starts before the other ends. The
     * check runs in the database so the answer cannot go stale between
     * reading and enrolling.</p>
     *
     * @return the number of clashing meetings, zero when the section fits
     */
    public int countClashes(int studentId, int semesterId, int sectionId) {
        return queryInt("SELECT COUNT(*) "
                + "FROM dbo.section_schedules candidate "
                + "INNER JOIN dbo.section_schedules existing "
                + "  ON existing.day_of_week = candidate.day_of_week "
                + " AND existing.start_time < candidate.end_time "
                + " AND candidate.start_time < existing.end_time "
                + "INNER JOIN dbo.sections s ON s.section_id = existing.section_id "
                + "INNER JOIN dbo.enrollments e ON e.section_id = s.section_id "
                + "WHERE candidate.section_id = ? AND e.student_id = ? "
                + "AND s.semester_id = ? AND e.status = 'ENROLLED' "
                + "AND existing.section_id <> candidate.section_id",
                sectionId, studentId, semesterId);
    }

    /** Removes every meeting of a section before its timetable is rewritten. */
    public int deleteBySection(Connection connection, int sectionId) {
        return executeUpdate(connection,
                "DELETE FROM dbo.section_schedules WHERE section_id = ?", sectionId);
    }

    @Override
    public int insert(SectionSchedule entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, SectionSchedule entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(SectionSchedule entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, SectionSchedule entity) {
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

    private Object[] insertParams(SectionSchedule entity) {
        return new Object[]{
                entity.getSectionId(),
                entity.getDayOfWeek(),
                entity.getStartTime(),
                entity.getEndTime()
        };
    }

    private Object[] updateParams(SectionSchedule entity) {
        return new Object[]{
                entity.getSectionId(),
                entity.getDayOfWeek(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getScheduleId()
        };
    }
}
