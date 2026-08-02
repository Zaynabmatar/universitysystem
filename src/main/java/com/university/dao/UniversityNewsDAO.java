package com.university.dao;

import com.university.model.UniversityNews;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.university_news}.
 */
public class UniversityNewsDAO extends AbstractDAO implements GenericDAO<UniversityNews> {

    private static final String SELECT =
            "SELECT news_id, title, content, image_path, category, publication_date, expiry_date, "
            + "is_published, created_by, created_at, updated_at FROM dbo.university_news";

    private static final String INSERT =
            "INSERT INTO dbo.university_news (title, content, image_path, category, "
            + "publication_date, expiry_date, is_published, created_by) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.university_news SET title = ?, content = ?, image_path = ?, category = ?, "
            + "publication_date = ?, expiry_date = ?, is_published = ?, updated_at = ? "
            + "WHERE news_id = ?";

    private static final String DELETE = "DELETE FROM dbo.university_news WHERE news_id = ?";

    private static final RowMapper<UniversityNews> MAPPER = UniversityNewsDAO::mapRow;

    static UniversityNews mapRow(ResultSet rs) throws SQLException {
        UniversityNews news = new UniversityNews();
        news.setNewsId(rs.getInt("news_id"));
        news.setTitle(rs.getString("title"));
        news.setContent(rs.getString("content"));
        news.setImagePath(rs.getString("image_path"));
        news.setCategory(rs.getString("category"));
        news.setPublicationDate(DaoUtils.getLocalDateTime(rs, "publication_date"));
        news.setExpiryDate(DaoUtils.getLocalDateTime(rs, "expiry_date"));
        news.setPublished(rs.getBoolean("is_published"));
        news.setCreatedBy(rs.getInt("created_by"));
        news.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        news.setUpdatedAt(DaoUtils.getLocalDateTime(rs, "updated_at"));
        return news;
    }

    @Override
    public Optional<UniversityNews> findById(int id) {
        return queryOne(SELECT + " WHERE news_id = ?", MAPPER, id);
    }

    @Override
    public List<UniversityNews> findAll() {
        return queryList(SELECT + " ORDER BY publication_date DESC", MAPPER);
    }

    /**
     * The items a reader should see right now: published, already out, and
     * not yet expired.
     */
    public List<UniversityNews> findVisible() {
        return queryList(SELECT + " WHERE is_published = 1 AND publication_date <= GETDATE() "
                + "AND (expiry_date IS NULL OR expiry_date > GETDATE()) "
                + "ORDER BY publication_date DESC", MAPPER);
    }

    /** The newest few visible items, for a dashboard panel. */
    public List<UniversityNews> findLatest(int howMany) {
        return queryList("SELECT TOP (?) news_id, title, content, image_path, category, "
                + "publication_date, expiry_date, is_published, created_by, created_at, updated_at "
                + "FROM dbo.university_news WHERE is_published = 1 "
                + "AND publication_date <= GETDATE() "
                + "AND (expiry_date IS NULL OR expiry_date > GETDATE()) "
                + "ORDER BY publication_date DESC", MAPPER, howMany);
    }

    /** Visible items of one category. */
    public List<UniversityNews> findByCategory(String category) {
        return queryList(SELECT + " WHERE category = ? AND is_published = 1 "
                + "ORDER BY publication_date DESC", MAPPER, category);
    }

    /** Everything one administrator has written, published or not. */
    public List<UniversityNews> findByCreator(int createdByUserId) {
        return queryList(SELECT + " WHERE created_by = ? ORDER BY created_at DESC",
                MAPPER, createdByUserId);
    }

    /** Takes an item off the board or puts it back. */
    public boolean setPublished(int newsId, boolean published, LocalDateTime moment) {
        return executeUpdate("UPDATE dbo.university_news SET is_published = ?, updated_at = ? "
                + "WHERE news_id = ?", published, moment, newsId) > 0;
    }

    @Override
    public int insert(UniversityNews entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, UniversityNews entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(UniversityNews entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, UniversityNews entity) {
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

    private Object[] insertParams(UniversityNews entity) {
        return new Object[]{
                entity.getTitle(),
                entity.getContent(),
                entity.getImagePath(),
                entity.getCategory(),
                entity.getPublicationDate(),
                entity.getExpiryDate(),
                entity.isPublished(),
                entity.getCreatedBy()
        };
    }

    private Object[] updateParams(UniversityNews entity) {
        return new Object[]{
                entity.getTitle(),
                entity.getContent(),
                entity.getImagePath(),
                entity.getCategory(),
                entity.getPublicationDate(),
                entity.getExpiryDate(),
                entity.isPublished(),
                entity.getUpdatedAt(),
                entity.getNewsId()
        };
    }
}
