package com.university.dao;

import com.university.enums.SectionStatus;
import com.university.model.Section;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.sections}.
 *
 * <p>{@code enrolled_count} is a cached counter. It is never written by the
 * ordinary update, only by {@link #changeEnrolledCount}, which is meant to be
 * called on the same connection as the enrolment that caused the change.</p>
 */
public class SectionDAO extends AbstractDAO implements GenericDAO<Section> {

    private static final String SELECT =
            "SELECT section_id, course_id, semester_id, instructor_id, campus_id, section_number, "
            + "capacity, enrolled_count, room, status FROM dbo.sections";

    private static final String INSERT =
            "INSERT INTO dbo.sections (course_id, semester_id, instructor_id, campus_id, "
            + "section_number, capacity, enrolled_count, room, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.sections SET course_id = ?, semester_id = ?, instructor_id = ?, "
            + "campus_id = ?, section_number = ?, capacity = ?, room = ?, status = ? "
            + "WHERE section_id = ?";

    private static final String DELETE = "DELETE FROM dbo.sections WHERE section_id = ?";

    private static final RowMapper<Section> MAPPER = SectionDAO::mapRow;

    static Section mapRow(ResultSet rs) throws SQLException {
        Section section = new Section();
        section.setSectionId(rs.getInt("section_id"));
        section.setCourseId(rs.getInt("course_id"));
        section.setSemesterId(rs.getInt("semester_id"));
        // Null for a section published as TBA, so it must not become 0.
        section.setInstructorId(DaoUtils.getInteger(rs, "instructor_id"));
        section.setCampusId(rs.getInt("campus_id"));
        section.setSectionNumber(rs.getString("section_number"));
        section.setCapacity(rs.getInt("capacity"));
        section.setEnrolledCount(rs.getInt("enrolled_count"));
        section.setRoom(rs.getString("room"));
        section.setStatus(SectionStatus.fromDb(rs.getString("status")));
        return section;
    }

    @Override
    public Optional<Section> findById(int id) {
        return queryOne(SELECT + " WHERE section_id = ?", MAPPER, id);
    }

    @Override
    public List<Section> findAll() {
        return queryList(SELECT + " ORDER BY semester_id DESC, course_id, section_number", MAPPER);
    }

    /** Every section running in one semester. */
    public List<Section> findBySemester(int semesterId) {
        return queryList(SELECT + " WHERE semester_id = ? ORDER BY course_id, section_number",
                MAPPER, semesterId);
    }

    /** The offerings of one course in one semester, for the registration screen. */
    public List<Section> findByCourseAndSemester(int courseId, int semesterId) {
        return queryList(SELECT + " WHERE course_id = ? AND semester_id = ? ORDER BY section_number",
                MAPPER, courseId, semesterId);
    }

    /** What one instructor teaches in one semester. */
    public List<Section> findByInstructorAndSemester(int instructorId, int semesterId) {
        return queryList(SELECT + " WHERE instructor_id = ? AND semester_id = ? "
                + "ORDER BY course_id, section_number", MAPPER, instructorId, semesterId);
    }

    /** What one student is enrolled in for one semester, in a single round trip. */
    public List<Section> findByStudentAndSemester(int studentId, int semesterId) {
        return queryList("SELECT s.section_id, s.course_id, s.semester_id, s.instructor_id, "
                + "s.campus_id, s.section_number, s.capacity, s.enrolled_count, s.room, s.status "
                + "FROM dbo.sections s "
                + "INNER JOIN dbo.enrollments e ON e.section_id = s.section_id "
                + "WHERE e.student_id = ? AND s.semester_id = ? AND e.status = 'ENROLLED' "
                + "ORDER BY s.course_id, s.section_number", MAPPER, studentId, semesterId);
    }

    /** Everything one instructor has ever taught. */
    public List<Section> findByInstructor(int instructorId) {
        return queryList(SELECT + " WHERE instructor_id = ? ORDER BY semester_id DESC",
                MAPPER, instructorId);
    }

    /** Sections open and not yet full, which a student may still join. */
    public List<Section> findOpenWithSeats(int semesterId) {
        return queryList(SELECT + " WHERE semester_id = ? AND status = 'OPEN' "
                + "AND enrolled_count < capacity ORDER BY course_id, section_number",
                MAPPER, semesterId);
    }

    /** Sections still waiting for somebody to teach them. */
    public List<Section> findWithoutInstructor(int semesterId) {
        return queryList(SELECT + " WHERE semester_id = ? AND instructor_id IS NULL "
                + "ORDER BY course_id, section_number", MAPPER, semesterId);
    }

    /** Open sections a course has in the current semester — used to warn before deactivating it. */
    public int countOpenInCurrentSemester(int courseId) {
        return queryInt("SELECT COUNT(*) FROM dbo.sections s "
                + "INNER JOIN dbo.semesters sem ON sem.semester_id = s.semester_id "
                + "WHERE s.course_id = ? AND sem.is_current = 1 AND s.status = 'OPEN'", courseId);
    }

    /**
     * Moves the cached seat counter and refuses to take it below zero or
     * above capacity.
     *
     * <p>Meant to run on the same connection as the enrolment that caused the
     * change, so the seat count and the enrolment rows cannot drift apart.</p>
     *
     * @param delta {@code +1} when somebody joins, {@code -1} when somebody leaves
     * @return true when the counter moved
     */
    public boolean changeEnrolledCount(Connection connection, int sectionId, int delta) {
        return executeUpdate(connection,
                "UPDATE dbo.sections SET enrolled_count = enrolled_count + ? "
                + "WHERE section_id = ? AND enrolled_count + ? BETWEEN 0 AND capacity",
                delta, sectionId, delta) > 0;
    }

    /** Recounts the seats from the enrolment rows and repairs the cache. */
    public boolean recalculateEnrolledCount(int sectionId) {
        return executeUpdate("UPDATE dbo.sections SET enrolled_count = ("
                + "SELECT COUNT(*) FROM dbo.enrollments "
                + "WHERE section_id = ? AND status IN ('ENROLLED', 'COMPLETED')) "
                + "WHERE section_id = ?", sectionId, sectionId) > 0;
    }

    /** Opens, closes or cancels a section. */
    public boolean setStatus(int sectionId, SectionStatus status) {
        return executeUpdate("UPDATE dbo.sections SET status = ? WHERE section_id = ?",
                status, sectionId) > 0;
    }

    /** Puts an instructor in front of a section, or clears it back to TBA. */
    public boolean assignInstructor(int sectionId, Integer instructorId) {
        return executeUpdate("UPDATE dbo.sections SET instructor_id = ? WHERE section_id = ?",
                instructorId, sectionId) > 0;
    }

    @Override
    public int insert(Section entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Section entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Section entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Section entity) {
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

    private Object[] insertParams(Section entity) {
        return new Object[]{
                entity.getCourseId(),
                entity.getSemesterId(),
                entity.getInstructorId(),
                entity.getCampusId(),
                entity.getSectionNumber(),
                entity.getCapacity(),
                entity.getEnrolledCount(),
                entity.getRoom(),
                entity.getStatus()
        };
    }

    private Object[] updateParams(Section entity) {
        return new Object[]{
                entity.getCourseId(),
                entity.getSemesterId(),
                entity.getInstructorId(),
                entity.getCampusId(),
                entity.getSectionNumber(),
                entity.getCapacity(),
                entity.getRoom(),
                entity.getStatus(),
                entity.getSectionId()
        };
    }
}
