package com.university.dao;

import com.university.model.Campus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.campuses}.
 */
public class CampusDAO extends AbstractDAO implements GenericDAO<Campus> {

    private static final String SELECT =
            "SELECT campus_id, campus_name, location, is_active FROM dbo.campuses";

    private static final String INSERT =
            "INSERT INTO dbo.campuses (campus_name, location, is_active) VALUES (?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.campuses SET campus_name = ?, location = ?, is_active = ? WHERE campus_id = ?";

    private static final String DELETE = "DELETE FROM dbo.campuses WHERE campus_id = ?";

    private static final RowMapper<Campus> MAPPER = CampusDAO::mapRow;

    static Campus mapRow(ResultSet rs) throws SQLException {
        Campus campus = new Campus();
        campus.setCampusId(rs.getInt("campus_id"));
        campus.setCampusName(rs.getString("campus_name"));
        campus.setLocation(rs.getString("location"));
        campus.setActive(rs.getBoolean("is_active"));
        return campus;
    }

    @Override
    public Optional<Campus> findById(int id) {
        return queryOne(SELECT + " WHERE campus_id = ?", MAPPER, id);
    }

    @Override
    public List<Campus> findAll() {
        return queryList(SELECT + " ORDER BY campus_name", MAPPER);
    }

    /** The campuses still in use, for a combo box. */
    public List<Campus> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY campus_name", MAPPER);
    }

    @Override
    public int insert(Campus entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Campus entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Campus entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Campus entity) {
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

    private Object[] insertParams(Campus entity) {
        return new Object[]{
                entity.getCampusName(),
                entity.getLocation(),
                entity.isActive()
        };
    }

    private Object[] updateParams(Campus entity) {
        return new Object[]{
                entity.getCampusName(),
                entity.getLocation(),
                entity.isActive(),
                entity.getCampusId()
        };
    }
}
