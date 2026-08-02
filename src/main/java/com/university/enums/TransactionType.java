package com.university.enums;

/**
 * The kind of entry in the money history.
 *
 * <p>Mirrors {@code CK_financial_transactions_type} on
 * {@code dbo.financial_transactions.transaction_type}.</p>
 *
 * <p>The {@code amount} column is signed on purpose: {@link #CHARGE} and
 * {@link #LATE_FEE} are positive, {@link #PAYMENT}, {@link #DISCOUNT},
 * {@link #SCHOLARSHIP} and {@link #REFUND} are negative, and
 * {@link #ADJUSTMENT} may be either. The student balance is the sum of the
 * column.</p>
 */
public enum TransactionType {

    CHARGE("Charge"),
    PAYMENT("Payment"),
    DISCOUNT("Discount"),
    SCHOLARSHIP("Scholarship"),
    LATE_FEE("Late Fee"),
    REFUND("Refund"),
    ADJUSTMENT("Adjustment");

    private final String label;

    TransactionType(String label) {
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

    /** True when the type is expected to increase what the student owes. */
    public boolean increasesBalance() {
        return this == CHARGE || this == LATE_FEE;
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static TransactionType fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
