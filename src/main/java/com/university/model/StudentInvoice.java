package com.university.model;

import com.university.enums.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of {@code dbo.student_invoices}: one bill for one student in one
 * semester.
 *
 * <p>{@code paidAmount} and {@code remainingAmount} are cached totals kept in
 * step with {@code dbo.payments} by the application.</p>
 */
public class StudentInvoice {

    private int invoiceId;
    private int studentId;
    private int semesterId;
    private String invoiceNumber;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private BigDecimal originalAmount = BigDecimal.ZERO;
    private BigDecimal discountAmount = BigDecimal.ZERO;
    private BigDecimal scholarshipAmount = BigDecimal.ZERO;
    private BigDecimal lateFeeAmount = BigDecimal.ZERO;
    private BigDecimal totalAmount = BigDecimal.ZERO;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    private BigDecimal remainingAmount = BigDecimal.ZERO;
    private InvoiceStatus status = InvoiceStatus.UNPAID;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public StudentInvoice() {
    }

    public StudentInvoice(int invoiceId, int studentId, int semesterId, String invoiceNumber,
                          LocalDate issueDate, LocalDate dueDate, BigDecimal originalAmount,
                          BigDecimal discountAmount, BigDecimal scholarshipAmount,
                          BigDecimal lateFeeAmount, BigDecimal totalAmount, BigDecimal paidAmount,
                          BigDecimal remainingAmount, InvoiceStatus status,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.invoiceId = invoiceId;
        this.studentId = studentId;
        this.semesterId = semesterId;
        this.invoiceNumber = invoiceNumber;
        this.issueDate = issueDate;
        this.dueDate = dueDate;
        this.originalAmount = originalAmount;
        this.discountAmount = discountAmount;
        this.scholarshipAmount = scholarshipAmount;
        this.lateFeeAmount = lateFeeAmount;
        this.totalAmount = totalAmount;
        this.paidAmount = paidAmount;
        this.remainingAmount = remainingAmount;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    public int getSemesterId() {
        return semesterId;
    }

    public void setSemesterId(int semesterId) {
        this.semesterId = semesterId;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDate issueDate) {
        this.issueDate = issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public void setOriginalAmount(BigDecimal originalAmount) {
        this.originalAmount = originalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getScholarshipAmount() {
        return scholarshipAmount;
    }

    public void setScholarshipAmount(BigDecimal scholarshipAmount) {
        this.scholarshipAmount = scholarshipAmount;
    }

    public BigDecimal getLateFeeAmount() {
        return lateFeeAmount;
    }

    public void setLateFeeAmount(BigDecimal lateFeeAmount) {
        this.lateFeeAmount = lateFeeAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public BigDecimal getRemainingAmount() {
        return remainingAmount;
    }

    public void setRemainingAmount(BigDecimal remainingAmount) {
        this.remainingAmount = remainingAmount;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** True when money is still owed and the due date has passed. */
    public boolean isPastDue(LocalDate today) {
        return status.hasBalance() && dueDate != null && today != null && today.isAfter(dueDate);
    }

    @Override
    public String toString() {
        return invoiceNumber + " - " + totalAmount + " (" + status + ")";
    }
}
