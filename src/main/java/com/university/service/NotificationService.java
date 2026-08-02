package com.university.service;

import com.university.dao.NotificationDAO;
import com.university.enums.NotificationType;
import com.university.model.Notification;

import java.sql.Connection;
import java.util.List;

/**
 * Creates and reads the personal in-app inbox.
 *
 * <p>Most notifications are a side effect of something else: a seat coming
 * free, a grade being published, a payment landing. Those calls pass the
 * connection of the change that caused them, so the message cannot survive a
 * rolled-back transaction and announce something that never happened.</p>
 */
public class NotificationService {

    private final NotificationDAO notificationDao = new NotificationDAO();

    /**
     * Sends a message on its own connection.
     *
     * @param entityType the table the message points at, may be null
     * @param entityId   the key inside that table, may be null
     * @return the new notification's key
     */
    public int notify(int userId, NotificationType type, String title, String message,
                      String entityType, Integer entityId) {
        return notificationDao.insert(build(userId, type, title, message, entityType, entityId));
    }

    /** Sends a plain message with nothing to open. */
    public int notify(int userId, NotificationType type, String title, String message) {
        return notify(userId, type, title, message, null, null);
    }

    /**
     * Sends a message as part of a change already in progress.
     *
     * <p>Rolls back with everything else if the change fails.</p>
     */
    public int notify(Connection connection, int userId, NotificationType type, String title,
                      String message, String entityType, Integer entityId) {
        return notificationDao.insert(connection,
                build(userId, type, title, message, entityType, entityId));
    }

    /** Sends the same message to several people inside one change. */
    public void notifyAll(Connection connection, List<Integer> userIds, NotificationType type,
                          String title, String message) {
        for (Integer userId : userIds) {
            notify(connection, userId, type, title, message, null, null);
        }
    }

    /** One person's inbox, newest first. */
    public List<Notification> inbox(int userId) {
        return notificationDao.findByUser(userId);
    }

    /** The items this person has not opened yet. */
    public List<Notification> unread(int userId) {
        return notificationDao.findUnreadByUser(userId);
    }

    /** The newest few, for the dropdown under the bell. */
    public List<Notification> latest(int userId, int howMany) {
        return notificationDao.findLatestByUser(userId, Math.max(1, howMany));
    }

    /** The number on the bell badge. */
    public int unreadCount(int userId) {
        return notificationDao.countUnread(userId);
    }

    /** Marks one item as seen. */
    public boolean markRead(int notificationId) {
        return notificationDao.markRead(notificationId);
    }

    /** Marks a whole inbox as seen. */
    public int markAllRead(int userId) {
        return notificationDao.markAllRead(userId);
    }

    private Notification build(int userId, NotificationType type, String title, String message,
                               String entityType, Integer entityId) {
        ValidationException.requireId(userId, "User");
        ValidationException.requireNonNull(type, "Notification type");
        ValidationException.requireText(title, "Title");
        ValidationException.requireText(message, "Message");

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setRead(false);
        notification.setRelatedEntityType(entityType);
        notification.setRelatedEntityId(entityId);
        return notification;
    }
}
