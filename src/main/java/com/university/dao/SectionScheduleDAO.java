package com.university.dao;

import com.university.enums.DayOfWeekCode;
import com.university.model.SectionSchedule;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
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

    /** One meeting of a section the student is already enrolled in, colliding with a candidate. */
    public static class StudentClash {
        private String courseCode;
        private DayOfWeekCode dayOfWeek;
        private LocalTime newStartTime;

        public String getCourseCode()        { return courseCode; }
        public DayOfWeekCode getDayOfWeek()  { return dayOfWeek; }
        public LocalTime getNewStartTime()   { return newStartTime; }
    }

    /**
     * RULE R6 — the detail behind {@link #countClashes}: which already-enrolled course collides,
     * on which day, and at what time the *candidate* section's colliding meeting starts. Used to
     * build the exact sentence project_details.md Section 6.1 requires:
     * {@code Time conflict with CS201 on MON at 10:00.}
     */
    public Optional<StudentClash> findStudentClash(int studentId, int semesterId, int candidateSectionId) {
        return queryOne("SELECT TOP 1 c.course_code AS course_code, ns.day_of_week AS clash_day, "
                        + "ns.start_time AS new_start "
                        + "FROM dbo.section_schedules ns "
                        + "INNER JOIN dbo.section_schedules es ON es.day_of_week = ns.day_of_week "
                        + "  AND ns.start_time < es.end_time AND ns.end_time > es.start_time "
                        + "INNER JOIN dbo.sections s    ON s.section_id = es.section_id "
                        + "INNER JOIN dbo.courses  c    ON c.course_id  = s.course_id "
                        + "INNER JOIN dbo.enrollments e ON e.section_id = es.section_id "
                        + "WHERE ns.section_id = ? AND e.student_id = ? "
                        + "  AND e.status = 'ENROLLED' AND s.semester_id = ? "
                        + "  AND es.section_id <> ns.section_id "
                        + "ORDER BY ns.day_of_week, ns.start_time",
                rs -> {
                    StudentClash c = new StudentClash();
                    c.courseCode = rs.getNString("course_code");
                    c.dayOfWeek = DayOfWeekCode.fromDb(rs.getString("clash_day"));
                    c.newStartTime = DaoUtils.getLocalTime(rs, "new_start");
                    return c;
                }, candidateSectionId, studentId, semesterId);
    }

    /** One meeting that clashes with a proposed one — enough to name the section it belongs to. */
    public static class Clash {
        private int sectionId;
        private DayOfWeekCode dayOfWeek;
        private LocalTime startTime;
        private LocalTime endTime;

        public int getSectionId()      { return sectionId; }
        public DayOfWeekCode getDayOfWeek() { return dayOfWeek; }
        public LocalTime getStartTime() { return startTime; }
        public LocalTime getEndTime()   { return endTime; }
    }

    /**
     * The overlap formula (project_details.md Section 6.3), written once and shared by the
     * instructor clash (rule S1) and the room clash (rule S2):
     *
     * <pre>overlap  &lt;=&gt;  (newStart &lt; existingEnd)  AND  (newEnd &gt; existingStart)</pre>
     *
     * Both comparisons are strict — a class ending at 11:00 and one starting at 11:00 do not
     * clash. Three filters always ride along: only within the same semester, a cancelled section
     * occupies nothing, and the section currently being edited is excluded (otherwise editing a
     * section would make it clash with itself).
     */
    private static final String CLASH_SQL =
            "SELECT TOP 1 sch.section_id, sch.day_of_week, sch.start_time, sch.end_time "
            + "FROM dbo.section_schedules sch "
            + "INNER JOIN dbo.sections s ON s.section_id = sch.section_id "
            + "WHERE s.semester_id = ? AND s.status <> 'CANCELLED' AND s.section_id <> ? "
            + "AND sch.day_of_week = ? AND ? < sch.end_time AND ? > sch.start_time ";

    /** RULE S1 — an instructor cannot teach two sections that overlap in time. */
    public Optional<Clash> findInstructorClash(int instructorId, int semesterId, int excludeSectionId,
                                                DayOfWeekCode day, LocalTime start, LocalTime end) {
        return findClash(CLASH_SQL + " AND s.instructor_id = ? ",
                semesterId, excludeSectionId, day, start, end, instructorId);
    }

    /** RULE S2 — a room cannot host two sections that overlap in time. */
    public Optional<Clash> findRoomClash(String room, int semesterId, int excludeSectionId,
                                          DayOfWeekCode day, LocalTime start, LocalTime end) {
        return findClash(CLASH_SQL + " AND s.room = ? ",
                semesterId, excludeSectionId, day, start, end, room);
    }

    private Optional<Clash> findClash(String sql, int semesterId, int excludeSectionId,
                                       DayOfWeekCode day, LocalTime start, LocalTime end, Object lastParam) {
        return queryOne(sql, rs -> {
            Clash c = new Clash();
            c.sectionId = rs.getInt("section_id");
            c.dayOfWeek = DayOfWeekCode.fromDb(rs.getString("day_of_week"));
            c.startTime = DaoUtils.getLocalTime(rs, "start_time");
            c.endTime = DaoUtils.getLocalTime(rs, "end_time");
            return c;
        }, semesterId, excludeSectionId, day, start, end, lastParam);
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
