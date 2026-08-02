package com.university.dao;

import com.university.model.FeeType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.fee_types}.
 */
public class FeeTypeDAO extends AbstractDAO implements GenericDAO<FeeType> {

    private static final String SELECT =
            "SELECT fee_type_id, fee_name, description, is_active FROM dbo.fee_types";

    private static final String INSERT =
            "INSERT INTO dbo.fee_types (fee_name, description, is_active) VALUES (?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.fee_types SET fee_name = ?, description = ?, is_active = ? "
            + "WHERE fee_type_id = ?";

    private static final String DELETE = "DELETE FROM dbo.fee_types WHERE fee_type_id = ?";

    private static final RowMapper<FeeType> MAPPER = FeeTypeDAO::mapRow;

    static FeeType mapRow(ResultSet rs) throws SQLException {
        FeeType feeType = new FeeType();
        feeType.setFeeTypeId(rs.getInt("fee_type_id"));
        feeType.setFeeName(rs.getString("fee_name"));
        feeType.setDescription(rs.getString("description"));
        feeType.setActive(rs.getBoolean("is_active"));
        return feeType;
    }

    @Override
    public Optional<FeeType> findById(int id) {
        return queryOne(SELECT + " WHERE fee_type_id = ?", MAPPER, id);
    }

    @Override
    public List<FeeType> findAll() {
        return queryList(SELECT + " ORDER BY fee_name", MAPPER);
    }

    /** The fees still chargeable, for the invoice builder. */
    public List<FeeType> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY fee_name", MAPPER);
    }

    /** Finds a fee by its unique name, for example Tuition. */
    public Optional<FeeType> findByName(String feeName) {
        return queryOne(SELECT + " WHERE fee_name = ?", MAPPER, feeName);
    }

    @Override
    public int insert(FeeType entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, FeeType entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(FeeType entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, FeeType entity) {
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

    private Object[] insertParams(FeeType entity) {
        return new Object[]{
                entity.getFeeName(),
                entity.getDescription(),
                entity.isActive()
        };
    }

    private Object[] updateParams(FeeType entity) {
        return new Object[]{
                entity.getFeeName(),
                entity.getDescription(),
                entity.isActive(),
                entity.getFeeTypeId()
        };
    }
}
