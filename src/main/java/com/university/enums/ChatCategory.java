package com.university.enums;

/**
 * The subject area a chat ticket belongs to.
 *
 * <p>Mirrors {@code CK_chat_conversations_category} on
 * {@code dbo.chat_conversations.category}.</p>
 */
public enum ChatCategory {

    ACADEMIC("Academic"),
    REGISTRATION("Registration"),
    FINANCE("Finance"),
    TECHNICAL("Technical"),
    OTHER("Other");

    private final String label;

    ChatCategory(String label) {
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
    public static ChatCategory fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
