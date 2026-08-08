package com.university.model;

import com.university.enums.Currency;
import com.university.enums.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of {@code dbo.student_tuition_installments}: one due payment, in
 * one currency, for one student's bill for one semester.
 *
 * <p>Generated once by {@code TuitionService} and only ever read back after
 * that, so the bank reference, amount and due date a student sees never
 * change between page loads. {@link InvoiceStatus} is reused here rather than
 * a new enum — the four values (Unpaid / Partially Paid / Paid / Overdue)
 * already say exactly what an installment's status means.</p>
 */
public class TuitionInstallment {

    private int installmentId;
    private int studentId;
    private int semesterId;
    private Currency currency;
    private int installmentNo;
    private String bankReference;
    private BigDecimal amount = BigDecimal.ZERO;
    private LocalDate dueDate;
    private LocalDate paymentDate;
    private BigDecimal penalty = BigDecimal.ZERO;
    private InvoiceStatus status = InvoiceStatus.UNPAID;

    public int getInstallmentId() {
        return installmentId;
    }

    public void setInstallmentId(int installmentId) {
        this.installmentId = installmentId;
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

    public Currency getCurrency() {
        return currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = currency;
    }

    public int getInstallmentNo() {
        return installmentNo;
    }

    public void setInstallmentNo(int installmentNo) {
        this.installmentNo = installmentNo;
    }

    public String getBankReference() {
        return bankReference;
    }

    public void setBankReference(String bankReference) {
        this.bankReference = bankReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    /** Null until the installment is actually paid. */
    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDate paymentDate) {
        this.paymentDate = paymentDate;
    }

    public BigDecimal getPenalty() {
        return penalty;
    }

    public void setPenalty(BigDecimal penalty) {
        this.penalty = penalty;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }
}
