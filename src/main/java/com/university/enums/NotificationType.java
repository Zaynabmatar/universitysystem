package com.university.enums;

/**
 * The category of a personal in-app notification.
 *
 * <p>Mirrors {@code CK_notifications_type} on
 * {@code dbo.notifications.type}.</p>
 */
public enum NotificationType {

    INFO("Information"),
    SUCCESS("Success"),
    WARNING("Warning"),
    GRADE("Grade"),
    PAYMENT("Payment"),
    REGISTRATION("Registration"),
    NEWS("News"),
    GENERAL("General");

    private final String label;

    NotificationType(String label) {
        this.label = label;
    }

    /** The text shown in the user interface. */
    public String getLabel() {
        return label;
    }

    /** The exact string stored in the database. */
    public String toDb() {
        return name();
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static NotificationType fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
