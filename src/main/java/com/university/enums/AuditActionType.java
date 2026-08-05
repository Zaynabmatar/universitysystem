package com.university.enums;

/**
 * The statement that produced an audit row.
 *
 * <p>Mirrors {@code CK_audit_log_action} on
 * {@code dbo.audit_log.action_type}. Almost every audit row is written by a
 * trigger, so this enum is mostly used for reading and filtering; the one
 * exception is account administration, which only the application can know
 * about and records through {@code AuditLogDAO.record}.</p>
 */
public enum AuditActionType {

    INSERT("Insert"),
    UPDATE("Update"),
    DELETE("Delete");

    private final String label;

    AuditActionType(String label) {
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
    public static AuditActionType fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
