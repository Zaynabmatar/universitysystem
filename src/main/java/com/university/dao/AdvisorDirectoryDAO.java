package com.university.dao;

import com.university.model.AdvisorDirectory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.advisors_directory}.
 */
public class AdvisorDirectoryDAO extends AbstractDAO implements GenericDAO<AdvisorDirectory> {

    private static final String SELECT =
            "SELECT advisor_id, full_name, department, university_email, office, phone, "
            + "is_active, created_at, updated_at FROM dbo.advisors_directory";

    private static final String INSERT =
            "INSERT INTO dbo.advisors_directory (full_name, department, university_email, office, "
            + "phone, is_active) VALUES (?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.advisors_directory SET full_name = ?, department = ?, "
            + "university_email = ?, office = ?, phone = ?, is_active = ?, updated_at = ? "
            + "WHERE advisor_id = ?";

    private static final String DELETE = "DELETE FROM dbo.advisors_directory WHERE advisor_id = ?";

    private static final RowMapper<AdvisorDirectory> MAPPER = AdvisorDirectoryDAO::mapRow;

    static AdvisorDirectory mapRow(ResultSet rs) throws SQLException {
        AdvisorDirectory advisor = new AdvisorDirectory();
        advisor.setAdvisorId(rs.getInt("advisor_id"));
        advisor.setFullName(rs.getString("full_name"));
        advisor.setDepartment(rs.getString("department"));
        advisor.setUniversityEmail(rs.getString("university_email"));
        advisor.setOffice(rs.getString("office"));
        advisor.setPhone(rs.getString("phone"));
        advisor.setActive(rs.getBoolean("is_active"));
        advisor.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        advisor.setUpdatedAt(DaoUtils.getLocalDateTime(rs, "updated_at"));
        return advisor;
    }

    @Override
    public Optional<AdvisorDirectory> findById(int id) {
        return queryOne(SELECT + " WHERE advisor_id = ?", MAPPER, id);
    }

    @Override
    public List<AdvisorDirectory> findAll() {
        return queryList(SELECT + " ORDER BY full_name", MAPPER);
    }

    /** The advisors a student may contact, which is what My Account shows. */
    public List<AdvisorDirectory> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY full_name", MAPPER);
    }

    /** The advisors listed against one department name. */
    public List<AdvisorDirectory> findByDepartment(String department) {
        return queryList(SELECT + " WHERE department = ? AND is_active = 1 ORDER BY full_name",
                MAPPER, department);
    }

    /** Finds an advisor by the unique university address. */
    public Optional<AdvisorDirectory> findByEmail(String universityEmail) {
        return queryOne(SELECT + " WHERE university_email = ?", MAPPER, universityEmail);
    }

    /** Searches on name or department. */
    public List<AdvisorDirectory> search(String term) {
        String pattern = "%" + term + "%";
        return queryList(SELECT + " WHERE (full_name LIKE ? OR department LIKE ?) "
                + "AND is_active = 1 ORDER BY full_name", MAPPER, pattern, pattern);
    }

    @Override
    public int insert(AdvisorDirectory entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, AdvisorDirectory entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(AdvisorDirectory entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, AdvisorDirectory entity) {
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

    private Object[] insertParams(AdvisorDirectory entity) {
        return new Object[]{
                entity.getFullName(),
                entity.getDepartment(),
                entity.getUniversityEmail(),
                entity.getOffice(),
                entity.getPhone(),
                entity.isActive()
        };
    }

    private Object[] updateParams(AdvisorDirectory entity) {
        return new Object[]{
                entity.getFullName(),
                entity.getDepartment(),
                entity.getUniversityEmail(),
                entity.getOffice(),
                entity.getPhone(),
                entity.isActive(),
                entity.getUpdatedAt(),
                entity.getAdvisorId()
        };
    }
}
