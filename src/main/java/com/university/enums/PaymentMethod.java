package com.university.enums;

/**
 * How the money reached the university.
 *
 * <p>Mirrors {@code CK_payments_method} on
 * {@code dbo.payments.payment_method}.</p>
 */
public enum PaymentMethod {

    CASH("Cash"),
    CARD("Card"),
    BANK_TRANSFER("Bank Transfer"),
    ONLINE("Online");

    private final String label;

    PaymentMethod(String label) {
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
    public static PaymentMethod fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
