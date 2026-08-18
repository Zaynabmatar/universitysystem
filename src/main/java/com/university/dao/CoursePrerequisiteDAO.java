package com.university.dao;

import com.university.model.Course;
import com.university.model.CoursePrerequisite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.course_prerequisites}.
 *
 * <p>Both identifiers in this table point at {@code dbo.courses}, so the
 * finders name which side they mean.</p>
 */
public class CoursePrerequisiteDAO extends AbstractDAO implements GenericDAO<CoursePrerequisite> {

    private static final String SELECT =
            "SELECT prereq_id, course_id, prerequisite_course_id, min_grade_points "
            + "FROM dbo.course_prerequisites";

    private static final String INSERT =
            "INSERT INTO dbo.course_prerequisites (course_id, prerequisite_course_id, "
            + "min_grade_points) VALUES (?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.course_prerequisites SET course_id = ?, prerequisite_course_id = ?, "
            + "min_grade_points = ? WHERE prereq_id = ?";

    private static final String DELETE = "DELETE FROM dbo.course_prerequisites WHERE prereq_id = ?";

    private static final RowMapper<CoursePrerequisite> MAPPER = CoursePrerequisiteDAO::mapRow;

    static CoursePrerequisite mapRow(ResultSet rs) throws SQLException {
        CoursePrerequisite prerequisite = new CoursePrerequisite();
        prerequisite.setPrerequisiteId(rs.getInt("prereq_id"));
        prerequisite.setCourseId(rs.getInt("course_id"));
        prerequisite.setPrerequisiteCourseId(rs.getInt("prerequisite_course_id"));
        prerequisite.setMinGradePoints(rs.getBigDecimal("min_grade_points"));
        return prerequisite;
    }

    @Override
    public Optional<CoursePrerequisite> findById(int id) {
        return queryOne(SELECT + " WHERE prereq_id = ?", MAPPER, id);
    }

    @Override
    public List<CoursePrerequisite> findAll() {
        return queryList(SELECT + " ORDER BY course_id", MAPPER);
    }

    /** What a student must already have passed before taking this course. */
    public List<CoursePrerequisite> findByCourse(int courseId) {
        return queryList(SELECT + " WHERE course_id = ?", MAPPER, courseId);
    }

    /** Which courses this one unlocks. */
    public List<CoursePrerequisite> findByPrerequisiteCourse(int prerequisiteCourseId) {
        return queryList(SELECT + " WHERE prerequisite_course_id = ?", MAPPER, prerequisiteCourseId);
    }

    /**
     * The prerequisites of a course as full course records, so a message can
     * name them rather than show numbers.
     */
    public List<Course> findPrerequisiteCourses(int courseId) {
        return queryList("SELECT c.course_id, c.course_code, c.course_title, c.description, "
                        + "c.credits, c.dept_id, c.level_year, c.is_active, c.has_lab, "
                        + "c.coursework_weight, c.midterm_weight, c.final_weight, "
                        + "c.coursework_max_mark, c.midterm_max_mark, c.final_max_mark "
                        + "FROM dbo.courses c "
                        + "INNER JOIN dbo.course_prerequisites p "
                        + "ON p.prerequisite_course_id = c.course_id "
                        + "WHERE p.course_id = ? ORDER BY c.course_code",
                CourseDAO::mapRow, courseId);
    }

    /**
     * The prerequisites of a course the student has not yet satisfied.
     *
     * <p>A prerequisite counts as met only when the student passed that
     * course with at least the grade points the link demands, and the grade
     * has been published. Doing this in one statement keeps the rule in step
     * with the data: reading the pieces separately would let a grade change
     * between the read and the decision.</p>
     *
     * @return the courses still outstanding, empty when the student may enrol
     */
    public List<Course> findUnmetPrerequisiteCourses(int studentId, int courseId) {
        return queryList("SELECT c.course_id, c.course_code, c.course_title, c.description, "
                        + "c.credits, c.dept_id, c.level_year, c.is_active, c.has_lab, "
                        + "c.coursework_weight, c.midterm_weight, c.final_weight, "
                        + "c.coursework_max_mark, c.midterm_max_mark, c.final_max_mark "
                        + "FROM dbo.course_prerequisites p "
                        + "INNER JOIN dbo.courses c ON c.course_id = p.prerequisite_course_id "
                        + "WHERE p.course_id = ? AND NOT EXISTS ("
                        + "    SELECT 1 FROM dbo.enrollments e "
                        + "    INNER JOIN dbo.sections s ON s.section_id = e.section_id "
                        + "    INNER JOIN dbo.grades g ON g.enrollment_id = e.enrollment_id "
                        + "    WHERE e.student_id = ? "
                        + "      AND s.course_id = p.prerequisite_course_id "
                        + "      AND g.is_submitted = 1 "
                        + "      AND g.result_status = 'PASSED' "
                        + "      AND g.grade_points >= p.min_grade_points) "
                        + "ORDER BY c.course_code",
                CourseDAO::mapRow, courseId, studentId);
    }

    /** True when the course has no prerequisites at all. */
    public boolean hasNoPrerequisites(int courseId) {
        return queryInt("SELECT COUNT(*) FROM dbo.course_prerequisites WHERE course_id = ?",
                courseId) == 0;
    }

    @Override
    public int insert(CoursePrerequisite entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, CoursePrerequisite entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(CoursePrerequisite entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, CoursePrerequisite entity) {
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

    private Object[] insertParams(CoursePrerequisite entity) {
        return new Object[]{
                entity.getCourseId(),
                entity.getPrerequisiteCourseId(),
                entity.getMinGradePoints()
        };
    }

    private Object[] updateParams(CoursePrerequisite entity) {
        return new Object[]{
                entity.getCourseId(),
                entity.getPrerequisiteCourseId(),
                entity.getMinGradePoints(),
                entity.getPrerequisiteId()
        };
    }
}
