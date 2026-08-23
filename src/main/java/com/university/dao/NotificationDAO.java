package com.university.dao;

import com.university.enums.NotificationType;
import com.university.model.Notification;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.notifications}.
 */
public class NotificationDAO extends AbstractDAO implements GenericDAO<Notification> {

    private static final String SELECT =
            "SELECT notification_id, user_id, title, message, type, is_read, "
            + "related_entity_type, related_entity_id, created_at FROM dbo.notifications";

    private static final String INSERT =
            "INSERT INTO dbo.notifications (user_id, title, message, type, is_read, "
            + "related_entity_type, related_entity_id) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.notifications SET title = ?, message = ?, type = ?, is_read = ? "
            + "WHERE notification_id = ?";

    private static final String DELETE =
            "DELETE FROM dbo.notifications WHERE notification_id = ?";

    private static final RowMapper<Notification> MAPPER = NotificationDAO::mapRow;

    static Notification mapRow(ResultSet rs) throws SQLException {
        Notification notification = new Notification();
        notification.setNotificationId(rs.getInt("notification_id"));
        notification.setUserId(rs.getInt("user_id"));
        notification.setTitle(rs.getString("title"));
        notification.setMessage(rs.getString("message"));
        notification.setType(NotificationType.fromDb(rs.getString("type")));
        notification.setRead(rs.getBoolean("is_read"));
        notification.setRelatedEntityType(rs.getString("related_entity_type"));
        // Null when the notification points at nothing in particular.
        notification.setRelatedEntityId(DaoUtils.getInteger(rs, "related_entity_id"));
        notification.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        return notification;
    }

    @Override
    public Optional<Notification> findById(int id) {
        return queryOne(SELECT + " WHERE notification_id = ?", MAPPER, id);
    }

    @Override
    public List<Notification> findAll() {
        return queryList(SELECT + " ORDER BY created_at DESC", MAPPER);
    }

    /** One person's inbox, newest first. */
    public List<Notification> findByUser(int userId) {
        return queryList(SELECT + " WHERE user_id = ? ORDER BY created_at DESC", MAPPER, userId);
    }

    /** The unseen items, which is what the bell icon opens. */
    public List<Notification> findUnreadByUser(int userId) {
        return queryList(SELECT + " WHERE user_id = ? AND is_read = 0 ORDER BY created_at DESC",
                MAPPER, userId);
    }

    /** The newest few items, for the dropdown under the bell. */
    public List<Notification> findLatestByUser(int userId, int howMany) {
        return queryList("SELECT TOP (?) notification_id, user_id, title, message, type, is_read, "
                + "related_entity_type, related_entity_id, created_at FROM dbo.notifications "
                + "WHERE user_id = ? ORDER BY created_at DESC", MAPPER, howMany, userId);
    }

    /** The number on the bell badge. */
    public int countUnread(int userId) {
        return queryInt("SELECT COUNT(*) FROM dbo.notifications "
                + "WHERE user_id = ? AND is_read = 0", userId);
    }

    /** One person's items of a single kind. */
    public List<Notification> findByUserAndType(int userId, NotificationType type) {
        return queryList(SELECT + " WHERE user_id = ? AND type = ? ORDER BY created_at DESC",
                MAPPER, userId, type);
    }

    /** True when this exact event was already notified for the related record. */
    public boolean existsForUserEntityAndTitle(int userId, String entityType,
                                                int entityId, String title) {
        return queryInt(
                "SELECT COUNT(*) FROM dbo.notifications "
                + "WHERE user_id = ? "
                + "AND related_entity_type = ? "
                + "AND related_entity_id = ? "
                + "AND title = ?",
                userId, entityType, entityId, title) > 0;
    }

    /** Removes one notification event for a related record. */
    public int deleteForEntityAndTitle(String entityType, int entityId, String title) {
        return executeUpdate(
                "DELETE FROM dbo.notifications "
                        + "WHERE related_entity_type = ? "
                        + "AND related_entity_id = ? "
                        + "AND title = ?",
                entityType, entityId, title);
    }
    /** Marks one item as seen. */
    public boolean markRead(int notificationId) {
        return executeUpdate("UPDATE dbo.notifications SET is_read = 1 WHERE notification_id = ?",
                notificationId) > 0;
    }

    /** Marks a whole inbox as seen. */
    public int markAllRead(int userId) {
        return executeUpdate("UPDATE dbo.notifications SET is_read = 1 "
                + "WHERE user_id = ? AND is_read = 0", userId);
    }

    /** Empties one person's inbox. */
    public int deleteByUser(int userId) {
        return executeUpdate("DELETE FROM dbo.notifications WHERE user_id = ?", userId);
    }

    @Override
    public int insert(Notification entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Notification entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Notification entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Notification entity) {
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

    private Object[] insertParams(Notification entity) {
        return new Object[]{
                entity.getUserId(),
                entity.getTitle(),
                entity.getMessage(),
                entity.getType(),
                entity.isRead(),
                entity.getRelatedEntityType(),
                entity.getRelatedEntityId()
        };
    }

    private Object[] updateParams(Notification entity) {
        return new Object[]{
                entity.getTitle(),
                entity.getMessage(),
                entity.getType(),
                entity.isRead(),
                entity.getNotificationId()
        };
    }
}


