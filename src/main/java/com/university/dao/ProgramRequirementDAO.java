package com.university.dao;

import com.university.model.ProgramRequirement;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.program_requirements}.
 */
public class ProgramRequirementDAO extends AbstractDAO implements GenericDAO<ProgramRequirement> {

    private static final String SELECT =
            "SELECT requirement_id, program_id, course_id, is_mandatory, recommended_semester "
            + "FROM dbo.program_requirements";

    private static final String INSERT =
            "INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, "
            + "recommended_semester) VALUES (?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.program_requirements SET program_id = ?, course_id = ?, is_mandatory = ?, "
            + "recommended_semester = ? WHERE requirement_id = ?";

    private static final String DELETE =
            "DELETE FROM dbo.program_requirements WHERE requirement_id = ?";

    private static final RowMapper<ProgramRequirement> MAPPER = ProgramRequirementDAO::mapRow;

    static ProgramRequirement mapRow(ResultSet rs) throws SQLException {
        ProgramRequirement requirement = new ProgramRequirement();
        requirement.setRequirementId(rs.getInt("requirement_id"));
        requirement.setProgramId(rs.getInt("program_id"));
        requirement.setCourseId(rs.getInt("course_id"));
        requirement.setMandatory(rs.getBoolean("is_mandatory"));
        requirement.setRecommendedSemester(rs.getInt("recommended_semester"));
        return requirement;
    }

    @Override
    public Optional<ProgramRequirement> findById(int id) {
        return queryOne(SELECT + " WHERE requirement_id = ?", MAPPER, id);
    }

    @Override
    public List<ProgramRequirement> findAll() {
        return queryList(SELECT + " ORDER BY program_id, recommended_semester", MAPPER);
    }

    /** The whole degree plan of one program, in study order. */
    public List<ProgramRequirement> findByProgram(int programId) {
        return queryList(SELECT + " WHERE program_id = ? ORDER BY recommended_semester, course_id",
                MAPPER, programId);
    }

    /** The courses a program insists on. */
    public List<ProgramRequirement> findMandatoryByProgram(int programId) {
        return queryList(SELECT + " WHERE program_id = ? AND is_mandatory = 1 "
                + "ORDER BY recommended_semester", MAPPER, programId);
    }

    /** One study term of the plan. */
    public List<ProgramRequirement> findByProgramAndSemester(int programId, int recommendedSemester) {
        return queryList(SELECT + " WHERE program_id = ? AND recommended_semester = ? "
                + "ORDER BY course_id", MAPPER, programId, recommendedSemester);
    }

    /** Which programs require a given course. */
    public List<ProgramRequirement> findByCourse(int courseId) {
        return queryList(SELECT + " WHERE course_id = ? ORDER BY program_id", MAPPER, courseId);
    }

    /** The credits a program requires on paper, summed from the plan. */
    public int sumRequiredCredits(int programId) {
        return queryInt("SELECT ISNULL(SUM(c.credits), 0) FROM dbo.program_requirements r "
                + "INNER JOIN dbo.courses c ON c.course_id = r.course_id "
                + "WHERE r.program_id = ?", programId);
    }

    @Override
    public int insert(ProgramRequirement entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, ProgramRequirement entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(ProgramRequirement entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, ProgramRequirement entity) {
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

    private Object[] insertParams(ProgramRequirement entity) {
        return new Object[]{
                entity.getProgramId(),
                entity.getCourseId(),
                entity.isMandatory(),
                entity.getRecommendedSemester()
        };
    }

    private Object[] updateParams(ProgramRequirement entity) {
        return new Object[]{
                entity.getProgramId(),
                entity.getCourseId(),
                entity.isMandatory(),
                entity.getRecommendedSemester(),
                entity.getRequirementId()
        };
    }
}
