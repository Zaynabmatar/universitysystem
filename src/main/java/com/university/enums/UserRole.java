package com.university.enums;

/**
 * The three login roles of the system.
 *
 * <p>Mirrors {@code CK_users_role} on {@code dbo.users.role}.</p>
 */
public enum UserRole {

    ADMIN("Admin"),
    INSTRUCTOR("Instructor"),
    STUDENT("Student");

    private final String label;

    UserRole(String label) {
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
    public static UserRole fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
