package com.university.dao;

import com.university.enums.DegreeType;
import com.university.model.Program;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.programs}.
 */
public class ProgramDAO extends AbstractDAO implements GenericDAO<Program> {

    private static final String SELECT =
            "SELECT program_id, dept_id, program_code, program_name, degree_type, "
            + "total_credits_required, is_active FROM dbo.programs";

    private static final String INSERT =
            "INSERT INTO dbo.programs (dept_id, program_code, program_name, degree_type, "
            + "total_credits_required, is_active) VALUES (?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.programs SET dept_id = ?, program_code = ?, program_name = ?, "
            + "degree_type = ?, total_credits_required = ?, is_active = ? WHERE program_id = ?";

    private static final String DELETE = "DELETE FROM dbo.programs WHERE program_id = ?";

    private static final RowMapper<Program> MAPPER = ProgramDAO::mapRow;

    static Program mapRow(ResultSet rs) throws SQLException {
        Program program = new Program();
        program.setProgramId(rs.getInt("program_id"));
        program.setDepartmentId(rs.getInt("dept_id"));
        program.setProgramCode(rs.getString("program_code"));
        program.setProgramName(rs.getString("program_name"));
        program.setDegreeType(DegreeType.fromDb(rs.getString("degree_type")));
        program.setTotalCreditsRequired(rs.getInt("total_credits_required"));
        program.setActive(rs.getBoolean("is_active"));
        return program;
    }

    @Override
    public Optional<Program> findById(int id) {
        return queryOne(SELECT + " WHERE program_id = ?", MAPPER, id);
    }

    @Override
    public List<Program> findAll() {
        return queryList(SELECT + " ORDER BY program_code", MAPPER);
    }

    /** The programs still open to new students. */
    public List<Program> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY program_code", MAPPER);
    }

    /** Every program run by one department. */
    public List<Program> findByDepartment(int departmentId) {
        return queryList(SELECT + " WHERE dept_id = ? ORDER BY program_code", MAPPER, departmentId);
    }

    /** Finds a program by its unique code, for example BSCS. */
    public Optional<Program> findByCode(String programCode) {
        return queryOne(SELECT + " WHERE program_code = ?", MAPPER, programCode);
    }

    @Override
    public int insert(Program entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Program entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Program entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Program entity) {
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

    private Object[] insertParams(Program entity) {
        return new Object[]{
                entity.getDepartmentId(),
                entity.getProgramCode(),
                entity.getProgramName(),
                entity.getDegreeType(),
                entity.getTotalCreditsRequired(),
                entity.isActive()
        };
    }

    private Object[] updateParams(Program entity) {
        return new Object[]{
                entity.getDepartmentId(),
                entity.getProgramCode(),
                entity.getProgramName(),
                entity.getDegreeType(),
                entity.getTotalCreditsRequired(),
                entity.isActive(),
                entity.getProgramId()
        };
    }
}
