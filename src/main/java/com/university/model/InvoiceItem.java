package com.university.model;

import java.math.BigDecimal;

/**
 * One row of {@code dbo.invoice_items}, a single line on an invoice.
 */
public class InvoiceItem {

    private int invoiceItemId;
    private int invoiceId;
    private int feeTypeId;
    private String description;
    private BigDecimal amount = BigDecimal.ZERO;

    public InvoiceItem() {
    }

    public InvoiceItem(int invoiceItemId, int invoiceId, int feeTypeId, String description,
                       BigDecimal amount) {
        this.invoiceItemId = invoiceItemId;
        this.invoiceId = invoiceId;
        this.feeTypeId = feeTypeId;
        this.description = description;
        this.amount = amount;
    }

    public int getInvoiceItemId() {
        return invoiceItemId;
    }

    public void setInvoiceItemId(int invoiceItemId) {
        this.invoiceItemId = invoiceItemId;
    }

    public int getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(int invoiceId) {
        this.invoiceId = invoiceId;
    }

    public int getFeeTypeId() {
        return feeTypeId;
    }

    public void setFeeTypeId(int feeTypeId) {
        this.feeTypeId = feeTypeId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    @Override
    public String toString() {
        return description + " - " + amount;
    }
}
