package com.university.model;

import com.university.enums.NotificationType;

import java.time.LocalDateTime;

/**
 * One row of {@code dbo.notifications}, a personal message for one user.
 *
 * <p>{@code relatedEntityType} and {@code relatedEntityId} form a soft
 * reference with no foreign key, because the target table varies. Together
 * they let the bell icon open the exact assignment, invoice, grade,
 * conversation or news item.</p>
 */
public class Notification {

    private int notificationId;
    private int userId;
    private String title;
    private String message;
    private NotificationType type;
    private boolean read;
    private String relatedEntityType;
    private Integer relatedEntityId;
    private LocalDateTime createdAt;

    public Notification() {
    }

    public Notification(int notificationId, int userId, String title, String message,
                        NotificationType type, boolean read, String relatedEntityType,
                        Integer relatedEntityId, LocalDateTime createdAt) {
        this.notificationId = notificationId;
        this.userId = userId;
        this.title = title;
        this.message = message;
        this.type = type;
        this.read = read;
        this.relatedEntityType = relatedEntityType;
        this.relatedEntityId = relatedEntityId;
        this.createdAt = createdAt;
    }

    public int getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(int notificationId) {
        this.notificationId = notificationId;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public NotificationType getType() {
        return type;
    }

    public void setType(NotificationType type) {
        this.type = type;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    /** The table the notification points at, null when it points nowhere. */
    public String getRelatedEntityType() {
        return relatedEntityType;
    }

    public void setRelatedEntityType(String relatedEntityType) {
        this.relatedEntityType = relatedEntityType;
    }

    /** The primary key inside {@link #getRelatedEntityType()}. */
    public Integer getRelatedEntityId() {
        return relatedEntityId;
    }

    public void setRelatedEntityId(Integer relatedEntityId) {
        this.relatedEntityId = relatedEntityId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** True when the notification can open a specific record. */
    public boolean hasTarget() {
        return relatedEntityType != null && relatedEntityId != null;
    }

    @Override
    public String toString() {
        return title;
    }
}
