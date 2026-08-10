package com.university.dao;

import com.university.enums.InvoiceStatus;
import com.university.model.StudentInvoice;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.student_invoices}.
 *
 * <p>{@code paid_amount} and {@code remaining_amount} are cached totals. The
 * ordinary update leaves them alone; {@link #applyPayment} moves them, and is
 * meant to run on the same connection as the payment that caused the
 * change.</p>
 */
public class StudentInvoiceDAO extends AbstractDAO implements GenericDAO<StudentInvoice> {

    private static final String SELECT =
            "SELECT invoice_id, student_id, semester_id, invoice_number, issue_date, due_date, "
            + "original_amount, discount_amount, scholarship_amount, late_fee_amount, "
            + "total_amount, paid_amount, remaining_amount, status, created_at, updated_at "
            + "FROM dbo.student_invoices";

    private static final String INSERT =
            "INSERT INTO dbo.student_invoices (student_id, semester_id, invoice_number, "
            + "issue_date, due_date, original_amount, discount_amount, scholarship_amount, "
            + "late_fee_amount, total_amount, paid_amount, remaining_amount, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.student_invoices SET invoice_number = ?, issue_date = ?, due_date = ?, "
            + "original_amount = ?, discount_amount = ?, scholarship_amount = ?, "
            + "late_fee_amount = ?, total_amount = ?, status = ?, updated_at = ? "
            + "WHERE invoice_id = ?";

    private static final String DELETE = "DELETE FROM dbo.student_invoices WHERE invoice_id = ?";

    private static final RowMapper<StudentInvoice> MAPPER = StudentInvoiceDAO::mapRow;

    static StudentInvoice mapRow(ResultSet rs) throws SQLException {
        StudentInvoice invoice = new StudentInvoice();
        invoice.setInvoiceId(rs.getInt("invoice_id"));
        invoice.setStudentId(rs.getInt("student_id"));
        invoice.setSemesterId(rs.getInt("semester_id"));
        invoice.setInvoiceNumber(rs.getString("invoice_number"));
        invoice.setIssueDate(DaoUtils.getLocalDate(rs, "issue_date"));
        invoice.setDueDate(DaoUtils.getLocalDate(rs, "due_date"));
        invoice.setOriginalAmount(rs.getBigDecimal("original_amount"));
        invoice.setDiscountAmount(rs.getBigDecimal("discount_amount"));
        invoice.setScholarshipAmount(rs.getBigDecimal("scholarship_amount"));
        invoice.setLateFeeAmount(rs.getBigDecimal("late_fee_amount"));
        invoice.setTotalAmount(rs.getBigDecimal("total_amount"));
        invoice.setPaidAmount(rs.getBigDecimal("paid_amount"));
        invoice.setRemainingAmount(rs.getBigDecimal("remaining_amount"));
        invoice.setStatus(InvoiceStatus.fromDb(rs.getString("status")));
        invoice.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        invoice.setUpdatedAt(DaoUtils.getLocalDateTime(rs, "updated_at"));
        return invoice;
    }

    @Override
    public Optional<StudentInvoice> findById(int id) {
        return queryOne(SELECT + " WHERE invoice_id = ?", MAPPER, id);
    }

    @Override
    public List<StudentInvoice> findAll() {
        return queryList(SELECT + " ORDER BY issue_date DESC", MAPPER);
    }

    /** Every bill one student has received. */
    public List<StudentInvoice> findByStudent(int studentId) {
        return queryList(SELECT + " WHERE student_id = ? ORDER BY issue_date DESC",
                MAPPER, studentId);
    }

    /** The one bill a student has for a semester, if it has been raised. */
    public Optional<StudentInvoice> findByStudentAndSemester(int studentId, int semesterId) {
        return queryOne(SELECT + " WHERE student_id = ? AND semester_id = ?",
                MAPPER, studentId, semesterId);
    }

    /**
     * Every distinct due date billed for one semester, earliest first — the Admin calendar's view
     * of "Payment" when no single student is in context. Invoices for the same semester are
     * usually raised on the same due date, but this does not assume that: a semester with two
     * billing rounds shows both dates.
     */
    public List<LocalDate> findDueDatesBySemester(int semesterId) {
        return queryList("SELECT DISTINCT due_date FROM dbo.student_invoices "
                + "WHERE semester_id = ? ORDER BY due_date",
                rs -> rs.getObject("due_date", LocalDate.class), semesterId);
    }

    /** Finds a bill by its printed number. */
    public Optional<StudentInvoice> findByInvoiceNumber(String invoiceNumber) {
        return queryOne(SELECT + " WHERE invoice_number = ?", MAPPER, invoiceNumber);
    }

    /** The bills of one student that still owe money. */
    public List<StudentInvoice> findUnpaidByStudent(int studentId) {
        return queryList(SELECT + " WHERE student_id = ? AND status <> 'PAID' ORDER BY due_date",
                MAPPER, studentId);
    }

    /** Everything past its due date and not settled, for the finance office. */
    public List<StudentInvoice> findOverdue() {
        return queryList(SELECT + " WHERE status <> 'PAID' AND due_date < CAST(GETDATE() AS DATE) "
                + "ORDER BY due_date", MAPPER);
    }

    /** What one student still owes in total. */
    public BigDecimal totalOutstanding(int studentId) {
        return queryOne("SELECT ISNULL(SUM(remaining_amount), 0) FROM dbo.student_invoices "
                + "WHERE student_id = ? AND status <> 'PAID'",
                rs -> rs.getBigDecimal(1), studentId).orElse(BigDecimal.ZERO);
    }

    /**
     * Moves the cached totals after money arrives and resets the status to
     * match.
     *
     * <p>The status is derived in the same statement so it can never disagree
     * with the amounts. Run this on the connection that inserted the
     * payment.</p>
     */
    public boolean applyPayment(Connection connection, int invoiceId, BigDecimal amount,
                                LocalDateTime moment) {
        return executeUpdate(connection,
                "UPDATE dbo.student_invoices "
                + "SET paid_amount = paid_amount + ?, "
                + "    remaining_amount = total_amount - (paid_amount + ?), "
                + "    status = CASE WHEN total_amount - (paid_amount + ?) <= 0 THEN 'PAID' "
                + "                  WHEN paid_amount + ? > 0 THEN 'PARTIALLY_PAID' "
                + "                  ELSE 'UNPAID' END, "
                + "    updated_at = ? "
                + "WHERE invoice_id = ?",
                amount, amount, amount, amount, moment, invoiceId) > 0;
    }

    /** Adds a late fee and raises the total and the balance with it. */
    public boolean addLateFee(Connection connection, int invoiceId, BigDecimal fee,
                              LocalDateTime moment) {
        return executeUpdate(connection,
                "UPDATE dbo.student_invoices "
                + "SET late_fee_amount = late_fee_amount + ?, "
                + "    total_amount = total_amount + ?, "
                + "    remaining_amount = remaining_amount + ?, "
                + "    updated_at = ? "
                + "WHERE invoice_id = ?", fee, fee, fee, moment, invoiceId) > 0;
    }

    /** Marks the bills that have run past their due date. */
    public int markOverdue(LocalDateTime moment) {
        return executeUpdate("UPDATE dbo.student_invoices SET status = 'OVERDUE', updated_at = ? "
                + "WHERE status IN ('UNPAID', 'PARTIALLY_PAID') "
                + "AND due_date < CAST(GETDATE() AS DATE)", moment);
    }

    @Override
    public int insert(StudentInvoice entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, StudentInvoice entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(StudentInvoice entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, StudentInvoice entity) {
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

    private Object[] insertParams(StudentInvoice entity) {
        return new Object[]{
                entity.getStudentId(),
                entity.getSemesterId(),
                entity.getInvoiceNumber(),
                entity.getIssueDate(),
                entity.getDueDate(),
                entity.getOriginalAmount(),
                entity.getDiscountAmount(),
                entity.getScholarshipAmount(),
                entity.getLateFeeAmount(),
                entity.getTotalAmount(),
                entity.getPaidAmount(),
                entity.getRemainingAmount(),
                entity.getStatus()
        };
    }

    private Object[] updateParams(StudentInvoice entity) {
        return new Object[]{
                entity.getInvoiceNumber(),
                entity.getIssueDate(),
                entity.getDueDate(),
                entity.getOriginalAmount(),
                entity.getDiscountAmount(),
                entity.getScholarshipAmount(),
                entity.getLateFeeAmount(),
                entity.getTotalAmount(),
                entity.getStatus(),
                entity.getUpdatedAt(),
                entity.getInvoiceId()
        };
    }
}
