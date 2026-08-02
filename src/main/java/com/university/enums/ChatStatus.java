package com.university.enums;

/**
 * The life cycle of a chat ticket between a student and administration.
 *
 * <p>Mirrors {@code CK_chat_conversations_status} on
 * {@code dbo.chat_conversations.status}.</p>
 */
public enum ChatStatus {

    OPEN("Open"),
    IN_PROGRESS("In Progress"),
    RESOLVED("Resolved"),
    CLOSED("Closed");

    private final String label;

    ChatStatus(String label) {
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

    /** True while the ticket still needs attention. */
    public boolean isActive() {
        return this == OPEN || this == IN_PROGRESS;
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static ChatStatus fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
