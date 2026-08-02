package com.university.enums;

/**
 * How much of an invoice has been settled.
 *
 * <p>Mirrors {@code CK_student_invoices_status} on
 * {@code dbo.student_invoices.status}.</p>
 */
public enum InvoiceStatus {

    UNPAID("Unpaid"),
    PARTIALLY_PAID("Partially Paid"),
    PAID("Paid"),
    OVERDUE("Overdue");

    private final String label;

    InvoiceStatus(String label) {
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

    /** True while money is still owed on the invoice. */
    public boolean hasBalance() {
        return this != PAID;
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static InvoiceStatus fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
