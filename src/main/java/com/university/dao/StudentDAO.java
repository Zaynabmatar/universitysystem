package com.university.dao;

import com.university.enums.AcademicStanding;
import com.university.enums.Gender;
import com.university.enums.StudentStatus;
import com.university.model.Student;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.students}.
 *
 * <p>{@code cumulative_gpa} and {@code completed_credits} are cached columns
 * refreshed by {@code sp_RecalculateStudentGPA}, so the ordinary update leaves
 * them alone. {@link #updateAcademicCache} changes them deliberately.</p>
 */
public class StudentDAO extends AbstractDAO implements GenericDAO<Student> {

    private static final String SELECT =
            "SELECT student_id, user_id, student_number, first_name, last_name, email, phone, "
            + "date_of_birth, gender, address, program_id, admission_date, status, "
            + "academic_standing, cumulative_gpa, completed_credits, probation_count "
            + "FROM dbo.students";

    private static final String INSERT =
            "INSERT INTO dbo.students (user_id, student_number, first_name, last_name, email, "
            + "phone, date_of_birth, gender, address, program_id, admission_date, status, "
            + "academic_standing, cumulative_gpa, completed_credits, probation_count) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.students SET student_number = ?, first_name = ?, last_name = ?, email = ?, "
            + "phone = ?, date_of_birth = ?, gender = ?, address = ?, program_id = ?, "
            + "admission_date = ?, status = ?, academic_standing = ?, probation_count = ? "
            + "WHERE student_id = ?";

    private static final String DELETE = "DELETE FROM dbo.students WHERE student_id = ?";

    private static final RowMapper<Student> MAPPER = StudentDAO::mapRow;

    static Student mapRow(ResultSet rs) throws SQLException {
        Student student = new Student();
        student.setStudentId(rs.getInt("student_id"));
        student.setUserId(rs.getInt("user_id"));
        student.setStudentNumber(rs.getString("student_number"));
        student.setFirstName(rs.getString("first_name"));
        student.setLastName(rs.getString("last_name"));
        student.setEmail(rs.getString("email"));
        student.setPhone(rs.getString("phone"));
        student.setDateOfBirth(DaoUtils.getLocalDate(rs, "date_of_birth"));
        student.setGender(Gender.fromDb(rs.getString("gender")));
        student.setAddress(rs.getString("address"));
        student.setProgramId(rs.getInt("program_id"));
        student.setAdmissionDate(DaoUtils.getLocalDate(rs, "admission_date"));
        student.setStatus(StudentStatus.fromDb(rs.getString("status")));
        student.setAcademicStanding(AcademicStanding.fromDb(rs.getString("academic_standing")));
        student.setCumulativeGpa(rs.getBigDecimal("cumulative_gpa"));
        student.setCompletedCredits(rs.getInt("completed_credits"));
        student.setProbationCount(rs.getInt("probation_count"));
        return student;
    }

    @Override
    public Optional<Student> findById(int id) {
        return queryOne(SELECT + " WHERE student_id = ?", MAPPER, id);
    }

    @Override
    public List<Student> findAll() {
        return queryList(SELECT + " ORDER BY student_number", MAPPER);
    }

    /**
     * Finds the student record attached to a login account.
     *
     * <p>Called straight after signing in, to turn a user into the student
     * whose timetable and grades the screens will show.</p>
     */
    public Optional<Student> findByUserId(int userId) {
        return queryOne(SELECT + " WHERE user_id = ?", MAPPER, userId);
    }

    /** Finds a student by the number printed on the card. */
    public Optional<Student> findByStudentNumber(String studentNumber) {
        return queryOne(SELECT + " WHERE student_number = ?", MAPPER, studentNumber);
    }

    /** Finds a student by their email address, which is also unique. */
    public Optional<Student> findByEmail(String email) {
        return queryOne(SELECT + " WHERE email = ?", MAPPER, email);
    }

    /** Every student enrolled on one degree plan. */
    public List<Student> findByProgram(int programId) {
        return queryList(SELECT + " WHERE program_id = ? ORDER BY student_number", MAPPER, programId);
    }

    /** Every student in one state, for example all suspended students. */
    public List<Student> findByStatus(StudentStatus status) {
        return queryList(SELECT + " WHERE status = ? ORDER BY student_number", MAPPER, status);
    }

    /** Every student on one academic standing, for the dean's list report. */
    public List<Student> findByAcademicStanding(AcademicStanding standing) {
        return queryList(SELECT + " WHERE academic_standing = ? ORDER BY cumulative_gpa DESC",
                MAPPER, standing);
    }

    /**
     * Searches on number, first name, last name or email.
     *
     * @param term matched anywhere in the field, case handled by the database
     *             collation
     */
    public List<Student> search(String term) {
        String pattern = "%" + term + "%";
        return queryList(SELECT + " WHERE student_number LIKE ? OR first_name LIKE ? "
                        + "OR last_name LIKE ? OR email LIKE ? ORDER BY student_number",
                MAPPER, pattern, pattern, pattern, pattern);
    }

    /**
     * Writes the cached academic figures.
     *
     * <p>Separate from {@link #update} because these columns are derived from
     * the grades table, not typed in by anybody.</p>
     */
    public boolean updateAcademicCache(int studentId, BigDecimal cumulativeGpa,
                                       int completedCredits, AcademicStanding standing) {
        return executeUpdate("UPDATE dbo.students SET cumulative_gpa = ?, completed_credits = ?, "
                        + "academic_standing = ? WHERE student_id = ?",
                cumulativeGpa, completedCredits, standing, studentId) > 0;
    }

    /** Writes the cached academic figures inside a transaction already running. */
    public boolean updateAcademicCache(Connection connection, int studentId, BigDecimal cumulativeGpa,
                                       int completedCredits, AcademicStanding standing) {
        return executeUpdate(connection, "UPDATE dbo.students SET cumulative_gpa = ?, "
                        + "completed_credits = ?, academic_standing = ? WHERE student_id = ?",
                cumulativeGpa, completedCredits, standing, studentId) > 0;
    }

    @Override
    public int insert(Student entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Student entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Student entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Student entity) {
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

    private Object[] insertParams(Student entity) {
        return new Object[]{
                entity.getUserId(),
                entity.getStudentNumber(),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getEmail(),
                entity.getPhone(),
                entity.getDateOfBirth(),
                entity.getGender(),
                entity.getAddress(),
                entity.getProgramId(),
                entity.getAdmissionDate(),
                entity.getStatus(),
                entity.getAcademicStanding(),
                entity.getCumulativeGpa(),
                entity.getCompletedCredits(),
                entity.getProbationCount()
        };
    }

    private Object[] updateParams(Student entity) {
        return new Object[]{
                entity.getStudentNumber(),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getEmail(),
                entity.getPhone(),
                entity.getDateOfBirth(),
                entity.getGender(),
                entity.getAddress(),
                entity.getProgramId(),
                entity.getAdmissionDate(),
                entity.getStatus(),
                entity.getAcademicStanding(),
                entity.getProbationCount(),
                entity.getStudentId()
        };
    }
}
