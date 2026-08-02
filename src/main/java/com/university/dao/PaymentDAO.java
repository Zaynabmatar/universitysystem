package com.university.dao;

import com.university.enums.PaymentMethod;
import com.university.enums.PaymentStatus;
import com.university.model.Payment;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.payments}.
 *
 * <p>Recording money is never a single statement on its own. Insert the
 * payment, move the invoice totals and write the transaction line, all on one
 * connection, so a failure halfway leaves nothing behind.</p>
 */
public class PaymentDAO extends AbstractDAO implements GenericDAO<Payment> {

    private static final String SELECT =
            "SELECT payment_id, invoice_id, student_id, payment_date, amount, payment_method, "
            + "receipt_number, reference_number, payment_status, notes, recorded_by, created_at "
            + "FROM dbo.payments";

    private static final String INSERT =
            "INSERT INTO dbo.payments (invoice_id, student_id, payment_date, amount, "
            + "payment_method, receipt_number, reference_number, payment_status, notes, "
            + "recorded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.payments SET amount = ?, payment_method = ?, reference_number = ?, "
            + "payment_status = ?, notes = ? WHERE payment_id = ?";

    private static final String DELETE = "DELETE FROM dbo.payments WHERE payment_id = ?";

    private static final RowMapper<Payment> MAPPER = PaymentDAO::mapRow;

    static Payment mapRow(ResultSet rs) throws SQLException {
        Payment payment = new Payment();
        payment.setPaymentId(rs.getInt("payment_id"));
        payment.setInvoiceId(rs.getInt("invoice_id"));
        payment.setStudentId(rs.getInt("student_id"));
        payment.setPaymentDate(DaoUtils.getLocalDateTime(rs, "payment_date"));
        payment.setAmount(rs.getBigDecimal("amount"));
        payment.setPaymentMethod(PaymentMethod.fromDb(rs.getString("payment_method")));
        payment.setReceiptNumber(rs.getString("receipt_number"));
        payment.setReferenceNumber(rs.getString("reference_number"));
        payment.setPaymentStatus(PaymentStatus.fromDb(rs.getString("payment_status")));
        payment.setNotes(rs.getString("notes"));
        // Null for an online payment with no cashier behind it.
        payment.setRecordedBy(DaoUtils.getInteger(rs, "recorded_by"));
        payment.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        return payment;
    }

    @Override
    public Optional<Payment> findById(int id) {
        return queryOne(SELECT + " WHERE payment_id = ?", MAPPER, id);
    }

    @Override
    public List<Payment> findAll() {
        return queryList(SELECT + " ORDER BY payment_date DESC", MAPPER);
    }

    /** Everything received against one bill. */
    public List<Payment> findByInvoice(int invoiceId) {
        return queryList(SELECT + " WHERE invoice_id = ? ORDER BY payment_date", MAPPER, invoiceId);
    }

    /** Everything one student has ever paid. */
    public List<Payment> findByStudent(int studentId) {
        return queryList(SELECT + " WHERE student_id = ? ORDER BY payment_date DESC",
                MAPPER, studentId);
    }

    /** Finds a payment by its unique receipt number. */
    public Optional<Payment> findByReceiptNumber(String receiptNumber) {
        return queryOne(SELECT + " WHERE receipt_number = ?", MAPPER, receiptNumber);
    }

    /** The takings of a date range, for the daily cash report. */
    public List<Payment> findBetween(LocalDate from, LocalDate to) {
        return queryList(SELECT + " WHERE CAST(payment_date AS DATE) BETWEEN ? AND ? "
                + "ORDER BY payment_date", MAPPER, from, to);
    }

    /** Payments still waiting to clear. */
    public List<Payment> findPending() {
        return queryList(SELECT + " WHERE payment_status = 'PENDING' ORDER BY payment_date",
                MAPPER);
    }

    /**
     * What has actually cleared against one bill.
     *
     * <p>Only completed payments count, which is why this can differ from the
     * cached {@code paid_amount} if something failed after being recorded.</p>
     */
    public BigDecimal sumCompletedByInvoice(int invoiceId) {
        return queryOne("SELECT ISNULL(SUM(amount), 0) FROM dbo.payments "
                + "WHERE invoice_id = ? AND payment_status = 'COMPLETED'",
                rs -> rs.getBigDecimal(1), invoiceId).orElse(BigDecimal.ZERO);
    }

    /** True when the receipt number has already been used. */
    public boolean receiptNumberExists(String receiptNumber) {
        return queryInt("SELECT COUNT(*) FROM dbo.payments WHERE receipt_number = ?",
                receiptNumber) > 0;
    }

    /** Moves a payment to another state, for example from pending to completed. */
    public boolean setStatus(Connection connection, int paymentId, PaymentStatus status) {
        return executeUpdate(connection,
                "UPDATE dbo.payments SET payment_status = ? WHERE payment_id = ?",
                status, paymentId) > 0;
    }

    @Override
    public int insert(Payment entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, Payment entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(Payment entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, Payment entity) {
        return executeUpdate(connection, UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean deleteById(int id) {
        return executeUpdate(DELETE, id) > 0;
    }

    @Override
    public boolean deleteById(Connection connection, int id) {
        return executeUpdate(connection, DELETE, id) > 0;
    }

    private Object[] insertParams(Payment entity) {
        return new Object[]{
                entity.getInvoiceId(),
                entity.getStudentId(),
                entity.getPaymentDate(),
                entity.getAmount(),
                entity.getPaymentMethod(),
                entity.getReceiptNumber(),
                entity.getReferenceNumber(),
                entity.getPaymentStatus(),
                entity.getNotes(),
                entity.getRecordedBy()
        };
    }

    private Object[] updateParams(Payment entity) {
        return new Object[]{
                entity.getAmount(),
                entity.getPaymentMethod(),
                entity.getReferenceNumber(),
                entity.getPaymentStatus(),
                entity.getNotes(),
                entity.getPaymentId()
        };
    }
}
