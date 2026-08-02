package com.university.model;

import com.university.enums.PaymentMethod;
import com.university.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of {@code dbo.payments}, money actually received.
 *
 * <p>{@code recordedBy} is null when the payment arrived through an online
 * channel with no member of staff behind it.</p>
 */
public class Payment {

    private int paymentId;
    private int invoiceId;
    private int studentId;
    private LocalDateTime paymentDate;
    private BigDecimal amount;
    private PaymentMethod paymentMethod;
    private String receiptNumber;
    private String referenceNumber;
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;
    private String notes;
    private Integer recordedBy;
    private LocalDateTime createdAt;

    public Payment() {
    }

    public Payment(int paymentId, int invoiceId, int studentId, LocalDateTime paymentDate,
                   BigDecimal amount, PaymentMethod paymentMethod, String receiptNumber,
                   String referenceNumber, PaymentStatus paymentStatus, String notes,
                   Integer recordedBy, LocalDateTime createdAt) {
        this.paymentId = paymentId;
        this.invoiceId = invoiceId;
        this.studentId = studentId;
        this.paymentDate = paymentDate;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.receiptNumber = receiptNumber;
        this.referenceNumber = referenceNumber;
        this.paymentStatus = paymentStatus;
        this.notes = notes;
        this.recordedBy = recordedBy;
        this.createdAt = createdAt;
    }

    public int getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(int paymentId) {
        this.paymentId = paymentId;
    }

    public int getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(int invoiceId) {
        this.invoiceId = invoiceId;
    }

    public int getStudentId() {
        return studentId;
    }

    public void setStudentId(int studentId) {
        this.studentId = studentId;
    }

    public LocalDateTime getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDateTime paymentDate) {
        this.paymentDate = paymentDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public void setReceiptNumber(String receiptNumber) {
        this.receiptNumber = receiptNumber;
    }

    /** The bank or gateway reference, null for cash. */
    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(PaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    /** The {@code user_id} of the cashier, null for an online payment. */
    public Integer getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(Integer recordedBy) {
        this.recordedBy = recordedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return receiptNumber + " - " + amount + " (" + paymentStatus + ")";
    }
}
