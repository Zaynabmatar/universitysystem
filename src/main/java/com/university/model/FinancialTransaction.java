package com.university.model;

import com.university.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of {@code dbo.financial_transactions}, the full money history of a
 * student.
 *
 * <p>{@code amount} is signed on purpose. A charge or a late fee is positive,
 * a payment, discount, scholarship or refund is negative, and an adjustment
 * may be either. The student balance is the sum of the column.</p>
 *
 * <p>{@code invoiceId} and {@code paymentId} are both nullable because a
 * transaction may relate to neither, for example a manual adjustment.</p>
 */
public class FinancialTransaction {

    private int transactionId;
    private int studentId;
    private Integer invoiceId;
    private Integer paymentId;
    private TransactionType transactionType;
    private BigDecimal amount;
    private LocalDateTime transactionDate;
    private String description;
    private Integer createdBy;
    private LocalDateTime createdAt;

    public FinancialTransaction() {
    }

    public FinancialTransaction(int transactionId, int studentId, Integer invoiceId, Integer paymentId,
                                TransactionType transactionType, BigDecimal amount,
                                LocalDateTime transactionDate, String description,
                                Integer createdBy, LocalDateTime createdAt) {
        this.transactionId = transactionId;
        this.studentId = studentId;
        this.invoiceId = invoiceId;
        this.paymentId = paymentId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.transactionDate = transactionDate;
        this.description = description;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public int getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(int transactionId) {
        this.transactionId = transactionId;
    }

    public int getStudentId() {
        return studentId;
    }

    public void setStudentId(int studentId) {
        this.studentId = studentId;
    }

    /** Null when the entry is not tied to a particular invoice. */
    public Integer getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(Integer invoiceId) {
        this.invoiceId = invoiceId;
    }

    /** Null when the entry is not tied to a particular payment. */
    public Integer getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Integer paymentId) {
        this.paymentId = paymentId;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    /** Signed: positive increases what the student owes, negative reduces it. */
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDateTime getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(LocalDateTime transactionDate) {
        this.transactionDate = transactionDate;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /** Null when a background job wrote the row. */
    public Integer getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return transactionType + " " + amount + " on " + transactionDate;
    }
}
