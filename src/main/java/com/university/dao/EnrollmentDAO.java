package com.university.dao;

import com.university.enums.EnrollmentStatus;
import com.university.model.Enrollment;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.enrollments}.
 *
 * <p>Inserting a row here has to be paired with raising
 * {@code sections.enrolled_count}. Use {@link #insert(Connection, Enrollment)}
 * together with {@code SectionDAO.changeEnrolledCount} on the same connection
 * so the two cannot come apart.</p>
 */
public class EnrollmentDAO extends AbstractDAO implements GenericDAO<Enrollment> {

    private static final String SELECT =
            "SELECT enrollment_id, student_id, section_id, enrollment_date, status, is_repeat, "
            + "counts_in_gpa FROM dbo.enrollments";

    private static final String INSERT =
            "INSERT INTO dbo.enrollments (student_id, section_id, status, is_repeat, counts_in_gpa) "
            + "VALUES (?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.enrollments SET student_id = ?, section_id = ?, status = ?, is_repeat = ?, "
            + "counts_in_gpa = ? WHERE enrollment_id = ?";

    private static final String DELETE = "DELETE FROM dbo.enrollments WHERE enrollment_id = ?";

    private static final RowMapper<Enrollment> MAPPER = EnrollmentDAO::mapRow;

    static Enrollment mapRow(ResultSet rs) throws SQLException {
        Enrollment enrollment = new Enrollment();
        enrollment.setEnrollmentId(rs.getInt("enrollment_id"));
        enrollment.setStudentId(rs.getInt("student_id"));
        enrollment.setSectionId(rs.getInt("section_id"));
        enrollment.setEnrollmentDate(DaoUtils.getLocalDateTime(rs, "enrollment_date"));
        enrollment.setStatus(EnrollmentStatus.fromDb(rs.getString("status")));
        enrollment.setRepeat(rs.getBoolean("is_repeat"));
        enrollment.setCountsInGpa(rs.getBoolean("counts_in_gpa"));
        return enrollment;
    }

    @Override
    public Optional<Enrollment> findById(int id) {
        return queryOne(SELECT + " WHERE enrollment_id = ?", MAPPER, id);
    }

    @Override
    public List<Enrollment> findAll() {
        return queryList(SELECT + " ORDER BY enrollment_date DESC", MAPPER);
    }

    /** Everything one student has ever registered for. */
    public List<Enrollment> findByStudent(int studentId) {
        return queryList(SELECT + " WHERE student_id = ? ORDER BY enrollment_date DESC",
                MAPPER, studentId);
    }

    /** The class list of one section. */
    public List<Enrollment> findBySection(int sectionId) {
        return queryList(SELECT + " WHERE section_id = ? ORDER BY student_id", MAPPER, sectionId);
    }

    /** What one student is taking in one semester. */
    public List<Enrollment> findByStudentAndSemester(int studentId, int semesterId) {
        return queryList("SELECT e.enrollment_id, e.student_id, e.section_id, e.enrollment_date, "
                        + "e.status, e.is_repeat, e.counts_in_gpa "
                        + "FROM dbo.enrollments e "
                        + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                        + "WHERE e.student_id = ? AND s.semester_id = ? "
                        + "ORDER BY e.enrollment_date",
                MAPPER, studentId, semesterId);
    }

    /** The row linking one student to one section, if it exists. */
    public Optional<Enrollment> findByStudentAndSection(int studentId, int sectionId) {
        return queryOne(SELECT + " WHERE student_id = ? AND section_id = ?",
                MAPPER, studentId, sectionId);
    }

    /** The students actually attending a section. */
    public List<Enrollment> findActiveBySection(int sectionId) {
        return queryList(SELECT + " WHERE section_id = ? AND status = 'ENROLLED' "
                + "ORDER BY student_id", MAPPER, sectionId);
    }

    /**
     * True when the student has already passed the course behind this section.
     *
     * <p>Used before allowing a repeat, and by the prerequisite check.</p>
     */
    public boolean hasPassedCourse(int studentId, int courseId) {
        return queryInt("SELECT COUNT(*) FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.grades g ON g.enrollment_id = e.enrollment_id "
                + "WHERE e.student_id = ? AND s.course_id = ? AND g.result_status = 'PASSED'",
                studentId, courseId) > 0;
    }

    /**
     * True when the student already holds a live registration for the course,
     * in any section of it, during the given semester.
     */
    public boolean isAlreadyRegisteredForCourse(int studentId, int courseId, int semesterId) {
        return queryInt("SELECT COUNT(*) FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "WHERE e.student_id = ? AND s.course_id = ? AND s.semester_id = ? "
                + "AND e.status = 'ENROLLED'", studentId, courseId, semesterId) > 0;
    }

    /**
     * Section 5, repeat policy — true when the student has any earlier COMPLETED attempt at this
     * course, pass or fail. Used to flag a new registration's {@code is_repeat}.
     */
    public boolean hasCompletedCourse(int studentId, int courseId) {
        return queryInt("SELECT COUNT(*) FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "WHERE e.student_id = ? AND s.course_id = ? AND e.status = 'COMPLETED'",
                studentId, courseId) > 0;
    }

    /**
     * RULE R8 — another live section of the same course.
     *
     * @return the {@code section_number} of the other section, if the student holds one
     */
    public java.util.Optional<String> findOtherEnrolledSectionNumber(int studentId, int courseId,
                                                                      int excludeSectionId) {
        return queryOne("SELECT TOP 1 s.section_number FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "WHERE e.student_id = ? AND e.status = 'ENROLLED' "
                + "AND s.course_id = ? AND s.section_id <> ?",
                rs -> rs.getNString("section_number"), studentId, courseId, excludeSectionId);
    }

    /** The credits a student is carrying this semester, for the load limit. */
    public int sumCreditsInSemester(int studentId, int semesterId) {
        return queryInt("SELECT ISNULL(SUM(c.credits), 0) FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                + "INNER JOIN dbo.courses c ON c.course_id = s.course_id "
                + "WHERE e.student_id = ? AND s.semester_id = ? AND e.status = 'ENROLLED'",
                studentId, semesterId);
    }

    /** Moves one registration to another state, for example DROPPED. */
    public boolean setStatus(int enrollmentId, EnrollmentStatus status) {
        return executeUpdate("UPDATE dbo.enrollments SET status = ? WHERE enrollment_id = ?",
                status, enrollmentId) > 0;
    }

    /** Moves one registration to another state inside a running transaction. */
    public boolean setStatus(Connection connection, int enrollmentId, EnrollmentStatus status) {
        return executeUpdate(connection,
                "UPDATE dbo.enrollments SET status = ? WHERE enrollment_id = ?",
                status, enrollmentId) > 0;
    }

    /** Takes a repeated attempt out of the grade point average. */
    public boolean setCountsInGpa(Connection connection, int enrollmentId, boolean countsInGpa) {
        return executeUpdate(connection,
                "UPDATE dbo.enrollments SET counts_in_gpa = ? WHERE enrollment_id = ?",
                countsInGpa, enrollmentId) > 0;
    }

    @Override
    public int insert(Enrollment entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Enrollment entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Enrollment entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Enrollment entity) {
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

    private Object[] insertParams(Enrollment entity) {
        return new Object[]{
                entity.getStudentId(),
                entity.getSectionId(),
                entity.getStatus(),
                entity.isRepeat(),
                entity.isCountsInGpa()
        };
    }

    private Object[] updateParams(Enrollment entity) {
        return new Object[]{
                entity.getStudentId(),
                entity.getSectionId(),
                entity.getStatus(),
                entity.isRepeat(),
                entity.isCountsInGpa(),
                entity.getEnrollmentId()
        };
    }
}
