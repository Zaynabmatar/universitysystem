package com.university.dao;

import com.university.model.Course;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.courses}.
 */
public class CourseDAO extends AbstractDAO implements GenericDAO<Course> {

    private static final String SELECT =
            "SELECT course_id, course_code, course_title, description, credits, dept_id, "
            + "level_year, is_active, has_lab FROM dbo.courses";

    private static final String INSERT =
            "INSERT INTO dbo.courses (course_code, course_title, description, credits, dept_id, "
            + "level_year, is_active, has_lab) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.courses SET course_code = ?, course_title = ?, description = ?, "
            + "credits = ?, dept_id = ?, level_year = ?, is_active = ?, has_lab = ? WHERE course_id = ?";

    private static final String DELETE = "DELETE FROM dbo.courses WHERE course_id = ?";

    private static final RowMapper<Course> MAPPER = CourseDAO::mapRow;

    static Course mapRow(ResultSet rs) throws SQLException {
        Course course = new Course();
        course.setCourseId(rs.getInt("course_id"));
        course.setCourseCode(rs.getString("course_code"));
        course.setCourseTitle(rs.getString("course_title"));
        course.setDescription(rs.getString("description"));
        course.setCredits(rs.getInt("credits"));
        course.setDepartmentId(rs.getInt("dept_id"));
        course.setLevelYear(rs.getInt("level_year"));
        course.setActive(rs.getBoolean("is_active"));
        course.setHasLab(rs.getBoolean("has_lab"));
        return course;
    }

    @Override
    public Optional<Course> findById(int id) {
        return queryOne(SELECT + " WHERE course_id = ?", MAPPER, id);
    }

    @Override
    public List<Course> findAll() {
        return queryList(SELECT + " ORDER BY course_code", MAPPER);
    }

    /** The courses still offered, for the catalogue screen. */
    public List<Course> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY course_code", MAPPER);
    }

    /** Finds a course by its unique code, for example CS101. */
    public Optional<Course> findByCode(String courseCode) {
        return queryOne(SELECT + " WHERE course_code = ?", MAPPER, courseCode);
    }

    /** Every course owned by one department. */
    public List<Course> findByDepartment(int departmentId) {
        return queryList(SELECT + " WHERE dept_id = ? ORDER BY course_code", MAPPER, departmentId);
    }

    /** Every course of one study year. */
    public List<Course> findByLevelYear(int levelYear) {
        return queryList(SELECT + " WHERE level_year = ? ORDER BY course_code", MAPPER, levelYear);
    }

    /** Searches on code or title. */
    public List<Course> search(String term) {
        String pattern = "%" + term + "%";
        return queryList(SELECT + " WHERE course_code LIKE ? OR course_title LIKE ? "
                + "ORDER BY course_code", MAPPER, pattern, pattern);
    }

    /**
     * The courses one program requires, joined through
     * {@code dbo.program_requirements}.
     */
    public List<Course> findByProgram(int programId) {
        return queryList("SELECT c.course_id, c.course_code, c.course_title, c.description, "
                        + "c.credits, c.dept_id, c.level_year, c.is_active "
                        + "FROM dbo.courses c "
                        + "INNER JOIN dbo.program_requirements r ON r.course_id = c.course_id "
                        + "WHERE r.program_id = ? ORDER BY r.recommended_semester, c.course_code",
                MAPPER, programId);
    }

    @Override
    public int insert(Course entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Course entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Course entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Course entity) {
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

    private Object[] insertParams(Course entity) {
        return new Object[]{
                entity.getCourseCode(),
                entity.getCourseTitle(),
                entity.getDescription(),
                entity.getCredits(),
                entity.getDepartmentId(),
                entity.getLevelYear(),
                entity.isActive(),
                entity.isHasLab()
        };
    }

    private Object[] updateParams(Course entity) {
        return new Object[]{
                entity.getCourseCode(),
                entity.getCourseTitle(),
                entity.getDescription(),
                entity.getCredits(),
                entity.getDepartmentId(),
                entity.getLevelYear(),
                entity.isActive(),
                entity.isHasLab(),
                entity.getCourseId()
        };
    }
}
