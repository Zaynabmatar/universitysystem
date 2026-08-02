package com.university.enums;

/**
 * The state of one entry in the queue for a full section.
 *
 * <p>Mirrors {@code CK_waitlist_status} on {@code dbo.waitlist.status}.</p>
 */
public enum WaitlistStatus {

    WAITING("Waiting"),
    PROMOTED("Promoted"),
    CANCELLED("Cancelled");

    private final String label;

    WaitlistStatus(String label) {
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
    public static WaitlistStatus fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
