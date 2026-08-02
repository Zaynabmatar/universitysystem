package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.FinancialTransactionDAO;
import com.university.dao.InvoiceItemDAO;
import com.university.dao.PaymentDAO;
import com.university.dao.SemesterDAO;
import com.university.dao.StudentDAO;
import com.university.dao.StudentInvoiceDAO;
import com.university.enums.InvoiceStatus;
import com.university.enums.NotificationType;
import com.university.enums.PaymentMethod;
import com.university.enums.PaymentStatus;
import com.university.enums.TransactionType;
import com.university.model.FinancialTransaction;
import com.university.model.InvoiceItem;
import com.university.model.Payment;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.model.StudentInvoice;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bills, payments and the running balance.
 *
 * <p>Money is never one statement. Recording a payment touches three tables:
 * the payment itself, the cached totals on the invoice, and the signed
 * history line. All three go on one connection, so a failure halfway through
 * leaves the books as they were rather than showing money that arrived
 * against a bill that never moved.</p>
 */
public class FinanceService {

    private static final DateTimeFormatter RECEIPT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final StudentInvoiceDAO invoiceDao = new StudentInvoiceDAO();
    private final InvoiceItemDAO itemDao = new InvoiceItemDAO();
    private final PaymentDAO paymentDao = new PaymentDAO();
    private final FinancialTransactionDAO transactionDao = new FinancialTransactionDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final SemesterDAO semesterDao = new SemesterDAO();
    private final NotificationService notifications = new NotificationService();

    private final AbstractDAO transactions = new AbstractDAO() {
    };

    /**
     * Raises a bill for one student for one semester.
     *
     * @param lines    the amount charged against each fee type
     * @param discount taken off the bill, zero when there is none
     * @param scholarship taken off the bill, zero when there is none
     * @return the new invoice's key
     * @throws ServiceException when the student already has a bill for that semester
     */
    public int createInvoice(int studentId, int semesterId, LocalDate dueDate,
                             Map<Integer, BigDecimal> lines, BigDecimal discount,
                             BigDecimal scholarship, Integer createdByUserId) {
        ValidationException.requireId(studentId, "Student");
        ValidationException.requireId(semesterId, "Semester");
        ValidationException.requireNonNull(dueDate, "Due date");
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException("An invoice needs at least one line.");
        }

        Student student = requireStudent(studentId);
        Semester semester = requireSemester(semesterId);

        if (invoiceDao.findByStudentAndSemester(studentId, semesterId).isPresent()) {
            throw new ServiceException(student.getStudentNumber()
                    + " already has an invoice for " + semester.getSemesterName() + ".");
        }

        BigDecimal off = orZero(discount);
        BigDecimal aid = orZero(scholarship);
        BigDecimal original = BigDecimal.ZERO;
        for (BigDecimal amount : lines.values()) {
            if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
                throw new ValidationException("An invoice line cannot be negative.");
            }
            original = original.add(amount);
        }

        BigDecimal total = original.subtract(off).subtract(aid);
        if (total.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("The discount and scholarship exceed the charges.");
        }

        LocalDate issueDate = LocalDate.now();
        if (dueDate.isBefore(issueDate)) {
            throw new ValidationException("The due date cannot be before today.");
        }

        StudentInvoice invoice = new StudentInvoice();
        invoice.setStudentId(studentId);
        invoice.setSemesterId(semesterId);
        invoice.setInvoiceNumber("INV-" + semesterId + "-" + student.getStudentNumber());
        invoice.setIssueDate(issueDate);
        invoice.setDueDate(dueDate);
        invoice.setOriginalAmount(original);
        invoice.setDiscountAmount(off);
        invoice.setScholarshipAmount(aid);
        invoice.setLateFeeAmount(BigDecimal.ZERO);
        invoice.setTotalAmount(total);
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setRemainingAmount(total);
        invoice.setStatus(total.compareTo(BigDecimal.ZERO) == 0
                ? InvoiceStatus.PAID : InvoiceStatus.UNPAID);

        Connection connection = transactions.beginTransaction();
        try {
            int invoiceId = invoiceDao.insert(connection, invoice);

            for (Map.Entry<Integer, BigDecimal> line : new LinkedHashMap<>(lines).entrySet()) {
                InvoiceItem item = new InvoiceItem();
                item.setInvoiceId(invoiceId);
                item.setFeeTypeId(line.getKey());
                item.setAmount(line.getValue());
                itemDao.insert(connection, item);
            }

            writeTransaction(connection, studentId, invoiceId, null, TransactionType.CHARGE,
                    total, "Invoice " + invoice.getInvoiceNumber(), createdByUserId);

            if (off.compareTo(BigDecimal.ZERO) > 0) {
                writeTransaction(connection, studentId, invoiceId, null, TransactionType.DISCOUNT,
                        off.negate(), "Discount", createdByUserId);
            }
            if (aid.compareTo(BigDecimal.ZERO) > 0) {
                writeTransaction(connection, studentId, invoiceId, null,
                        TransactionType.SCHOLARSHIP, aid.negate(), "Scholarship", createdByUserId);
            }

            notifications.notify(connection, student.getUserId(), NotificationType.PAYMENT,
                    "Invoice issued for " + semester.getSemesterName(),
                    "Your invoice " + invoice.getInvoiceNumber() + " for " + total
                            + " is due on " + dueDate + ".",
                    "student_invoices", invoiceId);

            connection.commit();
            return invoiceId;
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The invoice could not be created.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    /**
     * Records money received against a bill.
     *
     * @return the new payment's key
     * @throws ServiceException when the amount is not positive or exceeds the balance
     */
    public int recordPayment(int invoiceId, BigDecimal amount, PaymentMethod method,
                             String referenceNumber, String notes, Integer recordedByUserId) {
        ValidationException.requireId(invoiceId, "Invoice");
        ValidationException.requireNonNull(amount, "Amount");
        ValidationException.requireNonNull(method, "Payment method");

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("A payment must be more than zero.");
        }

        StudentInvoice invoice = invoiceDao.findById(invoiceId)
                .orElseThrow(() -> new ServiceException("That invoice was not found."));
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new ServiceException("Invoice " + invoice.getInvoiceNumber()
                    + " is already settled.");
        }
        if (amount.compareTo(invoice.getRemainingAmount()) > 0) {
            throw new ValidationException("That is more than the " + invoice.getRemainingAmount()
                    + " still owed on this invoice.");
        }

        Student student = requireStudent(invoice.getStudentId());
        LocalDateTime now = LocalDateTime.now();

        Payment payment = new Payment();
        payment.setInvoiceId(invoiceId);
        payment.setStudentId(invoice.getStudentId());
        payment.setPaymentDate(now);
        payment.setAmount(amount);
        payment.setPaymentMethod(method);
        payment.setReceiptNumber(nextReceiptNumber(invoice.getStudentId(), now));
        payment.setReferenceNumber(referenceNumber);
        payment.setPaymentStatus(PaymentStatus.COMPLETED);
        payment.setNotes(notes);
        payment.setRecordedBy(recordedByUserId);

        Connection connection = transactions.beginTransaction();
        try {
            int paymentId = paymentDao.insert(connection, payment);
            invoiceDao.applyPayment(connection, invoiceId, amount, now);

            writeTransaction(connection, invoice.getStudentId(), invoiceId, paymentId,
                    TransactionType.PAYMENT, amount.negate(),
                    "Receipt " + payment.getReceiptNumber(), recordedByUserId);

            notifications.notify(connection, student.getUserId(), NotificationType.PAYMENT,
                    "Payment received",
                    "We received " + amount + " against invoice " + invoice.getInvoiceNumber()
                            + ". Receipt " + payment.getReceiptNumber() + ".",
                    "payments", paymentId);

            connection.commit();
            return paymentId;
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The payment could not be recorded.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    /**
     * Adds a late fee to a bill, raising both the total and the balance.
     */
    public void applyLateFee(int invoiceId, BigDecimal fee, Integer createdByUserId) {
        ValidationException.requireId(invoiceId, "Invoice");
        ValidationException.requireNonNull(fee, "Late fee");
        if (fee.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("A late fee must be more than zero.");
        }

        StudentInvoice invoice = invoiceDao.findById(invoiceId)
                .orElseThrow(() -> new ServiceException("That invoice was not found."));
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new ServiceException("This invoice is already settled.");
        }

        Student student = requireStudent(invoice.getStudentId());
        LocalDateTime now = LocalDateTime.now();

        Connection connection = transactions.beginTransaction();
        try {
            invoiceDao.addLateFee(connection, invoiceId, fee, now);
            writeTransaction(connection, invoice.getStudentId(), invoiceId, null,
                    TransactionType.LATE_FEE, fee, "Late fee", createdByUserId);

            notifications.notify(connection, student.getUserId(), NotificationType.PAYMENT,
                    "Late fee added",
                    "A late fee of " + fee + " was added to invoice "
                            + invoice.getInvoiceNumber() + ".",
                    "student_invoices", invoiceId);

            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The late fee could not be applied.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    /** Flags every bill that has run past its due date. */
    public int markOverdueInvoices() {
        return invoiceDao.markOverdue(LocalDateTime.now());
    }

    /** What the student owes overall, as the sum of the signed history. */
    public BigDecimal balanceOf(int studentId) {
        return transactionDao.balanceOf(studentId);
    }

    /** True when anything is still owed. */
    public boolean hasOutstandingBalance(int studentId) {
        return invoiceDao.totalOutstanding(studentId).compareTo(BigDecimal.ZERO) > 0;
    }

    /** Every bill one student has received. */
    public List<StudentInvoice> invoicesOf(int studentId) {
        return invoiceDao.findByStudent(studentId);
    }

    /** The lines of one bill. */
    public List<InvoiceItem> itemsOf(int invoiceId) {
        return itemDao.findByInvoice(invoiceId);
    }

    /** Everything one student has paid. */
    public List<Payment> paymentsOf(int studentId) {
        return paymentDao.findByStudent(studentId);
    }

    /** The full money history of one student. */
    public List<FinancialTransaction> historyOf(int studentId) {
        return transactionDao.findByStudent(studentId);
    }

    /**
     * Checks a bill against its own lines.
     *
     * @return true when the total matches the sum of the lines less the
     *         reductions
     */
    public boolean invoiceAddsUp(int invoiceId) {
        StudentInvoice invoice = invoiceDao.findById(invoiceId).orElse(null);
        if (invoice == null) {
            return false;
        }
        BigDecimal expected = itemDao.sumByInvoice(invoiceId)
                .subtract(orZero(invoice.getDiscountAmount()))
                .subtract(orZero(invoice.getScholarshipAmount()))
                .add(orZero(invoice.getLateFeeAmount()));
        return expected.compareTo(invoice.getTotalAmount()) == 0;
    }

    private void writeTransaction(Connection connection, int studentId, Integer invoiceId,
                                  Integer paymentId, TransactionType type, BigDecimal amount,
                                  String description, Integer createdByUserId) {
        FinancialTransaction entry = new FinancialTransaction();
        entry.setStudentId(studentId);
        entry.setInvoiceId(invoiceId);
        entry.setPaymentId(paymentId);
        entry.setTransactionType(type);
        entry.setAmount(amount);
        entry.setTransactionDate(LocalDateTime.now());
        entry.setDescription(description);
        entry.setCreatedBy(createdByUserId);
        transactionDao.insert(connection, entry);
    }

    /**
     * Builds a receipt number that is not already taken.
     *
     * <p>The column is unique, so a clash would fail the whole payment.</p>
     */
    private String nextReceiptNumber(int studentId, LocalDateTime moment) {
        String base = "RCP-" + moment.format(RECEIPT_STAMP) + "-" + studentId;
        String candidate = base;
        int suffix = 1;
        while (paymentDao.receiptNumberExists(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Student requireStudent(int studentId) {
        return studentDao.findById(studentId)
                .orElseThrow(() -> new ServiceException("That student record was not found."));
    }

    private Semester requireSemester(int semesterId) {
        return semesterDao.findById(semesterId)
                .orElseThrow(() -> new ServiceException("That semester was not found."));
    }
}
