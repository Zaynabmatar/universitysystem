package com.university.enums;

/**
 * Whether a payment actually cleared.
 *
 * <p>Mirrors {@code CK_payments_status} on
 * {@code dbo.payments.payment_status}.</p>
 */
public enum PaymentStatus {

    PENDING("Pending"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    REFUNDED("Refunded");

    private final String label;

    PaymentStatus(String label) {
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

    /** True only when the money may be counted against an invoice. */
    public boolean countsTowardsInvoice() {
        return this == COMPLETED;
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static PaymentStatus fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
