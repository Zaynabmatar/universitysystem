package com.university.enums;

/**
 * The two independent tuition obligations a student owes.
 *
 * <p>USD and LBP are never a conversion of one another here — see
 * {@code TuitionService}. Mirrors {@code CK_sti_currency} on
 * {@code dbo.student_tuition_installments.currency}.</p>
 */
public enum Currency {

    USD("USD"),
    LBP("LBP");

    private final String label;

    Currency(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public String toDb() {
        return name();
    }

    public static Currency fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
