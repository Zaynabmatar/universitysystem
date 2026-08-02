package com.university.dao;

import com.university.enums.TransactionType;
import com.university.model.FinancialTransaction;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.financial_transactions}.
 *
 * <p>The amount column is signed, so a student's balance is simply the sum of
 * it. That is what {@link #balanceOf} relies on.</p>
 */
public class FinancialTransactionDAO extends AbstractDAO
        implements GenericDAO<FinancialTransaction> {

    private static final String SELECT =
            "SELECT transaction_id, student_id, invoice_id, payment_id, transaction_type, amount, "
            + "transaction_date, description, created_by, created_at "
            + "FROM dbo.financial_transactions";

    private static final String INSERT =
            "INSERT INTO dbo.financial_transactions (student_id, invoice_id, payment_id, "
            + "transaction_type, amount, transaction_date, description, created_by) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.financial_transactions SET transaction_type = ?, amount = ?, "
            + "description = ? WHERE transaction_id = ?";

    private static final String DELETE =
            "DELETE FROM dbo.financial_transactions WHERE transaction_id = ?";

    private static final RowMapper<FinancialTransaction> MAPPER = FinancialTransactionDAO::mapRow;

    static FinancialTransaction mapRow(ResultSet rs) throws SQLException {
        FinancialTransaction transaction = new FinancialTransaction();
        transaction.setTransactionId(rs.getInt("transaction_id"));
        transaction.setStudentId(rs.getInt("student_id"));
        // Both are optional: an adjustment may belong to neither.
        transaction.setInvoiceId(DaoUtils.getInteger(rs, "invoice_id"));
        transaction.setPaymentId(DaoUtils.getInteger(rs, "payment_id"));
        transaction.setTransactionType(TransactionType.fromDb(rs.getString("transaction_type")));
        transaction.setAmount(rs.getBigDecimal("amount"));
        transaction.setTransactionDate(DaoUtils.getLocalDateTime(rs, "transaction_date"));
        transaction.setDescription(rs.getString("description"));
        transaction.setCreatedBy(DaoUtils.getInteger(rs, "created_by"));
        transaction.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        return transaction;
    }

    @Override
    public Optional<FinancialTransaction> findById(int id) {
        return queryOne(SELECT + " WHERE transaction_id = ?", MAPPER, id);
    }

    @Override
    public List<FinancialTransaction> findAll() {
        return queryList(SELECT + " ORDER BY transaction_date DESC", MAPPER);
    }

    /** The full money history of one student, newest first. */
    public List<FinancialTransaction> findByStudent(int studentId) {
        return queryList(SELECT + " WHERE student_id = ? ORDER BY transaction_date DESC",
                MAPPER, studentId);
    }

    /** Everything recorded against one bill. */
    public List<FinancialTransaction> findByInvoice(int invoiceId) {
        return queryList(SELECT + " WHERE invoice_id = ? ORDER BY transaction_date",
                MAPPER, invoiceId);
    }

    /** The entries of one kind for a student, for example every scholarship. */
    public List<FinancialTransaction> findByStudentAndType(int studentId, TransactionType type) {
        return queryList(SELECT + " WHERE student_id = ? AND transaction_type = ? "
                + "ORDER BY transaction_date DESC", MAPPER, studentId, type);
    }

    /** The entries of a date range, for the finance report. */
    public List<FinancialTransaction> findBetween(LocalDate from, LocalDate to) {
        return queryList(SELECT + " WHERE CAST(transaction_date AS DATE) BETWEEN ? AND ? "
                + "ORDER BY transaction_date", MAPPER, from, to);
    }

    /**
     * What the student owes, as the sum of the signed amounts.
     *
     * @return a positive figure when money is owed, negative when in credit
     */
    public BigDecimal balanceOf(int studentId) {
        return queryOne("SELECT ISNULL(SUM(amount), 0) FROM dbo.financial_transactions "
                + "WHERE student_id = ?", rs -> rs.getBigDecimal(1), studentId)
                .orElse(BigDecimal.ZERO);
    }

    /** The total of one kind of entry for a student. */
    public BigDecimal sumByType(int studentId, TransactionType type) {
        return queryOne("SELECT ISNULL(SUM(amount), 0) FROM dbo.financial_transactions "
                + "WHERE student_id = ? AND transaction_type = ?",
                rs -> rs.getBigDecimal(1), studentId, type).orElse(BigDecimal.ZERO);
    }

    @Override
    public int insert(FinancialTransaction entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, FinancialTransaction entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(FinancialTransaction entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, FinancialTransaction entity) {
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

    private Object[] insertParams(FinancialTransaction entity) {
        return new Object[]{
                entity.getStudentId(),
                entity.getInvoiceId(),
                entity.getPaymentId(),
                entity.getTransactionType(),
                entity.getAmount(),
                entity.getTransactionDate(),
                entity.getDescription(),
                entity.getCreatedBy()
        };
    }

    private Object[] updateParams(FinancialTransaction entity) {
        return new Object[]{
                entity.getTransactionType(),
                entity.getAmount(),
                entity.getDescription(),
                entity.getTransactionId()
        };
    }
}
