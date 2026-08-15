package com.university.dao;

import com.university.enums.Currency;
import com.university.enums.InvoiceStatus;
import com.university.model.TuitionInstallment;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Reads and writes {@code dbo.student_tuition_installments}.
 *
 * <p>{@link #insert} is the only writer today: {@code TuitionService}
 * generates a student's installments once and this class persists them, it
 * never edits an amount or a reference afterwards.</p>
 */
public class TuitionInstallmentDAO extends AbstractDAO {

    private static final String SELECT =
            "SELECT installment_id, student_id, semester_id, currency, installment_no, "
            + "bank_reference, amount, due_date, payment_date, penalty, status "
            + "FROM dbo.student_tuition_installments";

    private static final String INSERT =
            "INSERT INTO dbo.student_tuition_installments (student_id, semester_id, currency, "
            + "installment_no, bank_reference, amount, due_date, payment_date, penalty, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final RowMapper<TuitionInstallment> MAPPER = TuitionInstallmentDAO::mapRow;

    static TuitionInstallment mapRow(ResultSet rs) throws SQLException {
        TuitionInstallment installment = new TuitionInstallment();
        installment.setInstallmentId(rs.getInt("installment_id"));
        installment.setStudentId(rs.getInt("student_id"));
        installment.setSemesterId(rs.getInt("semester_id"));
        installment.setCurrency(Currency.fromDb(rs.getString("currency")));
        installment.setInstallmentNo(rs.getInt("installment_no"));
        installment.setBankReference(rs.getString("bank_reference"));
        installment.setAmount(rs.getBigDecimal("amount"));
        installment.setDueDate(DaoUtils.getLocalDate(rs, "due_date"));
        installment.setPaymentDate(DaoUtils.getLocalDate(rs, "payment_date"));
        installment.setPenalty(rs.getBigDecimal("penalty"));
        installment.setStatus(InvoiceStatus.fromDb(rs.getString("status")));
        return installment;
    }

    /** All stored tuition installments, used by the global notification sync. */
    public List<TuitionInstallment> findAll() {
        return queryList(SELECT + " ORDER BY student_id, semester_id, currency, installment_no", MAPPER);
    }
    /** One student's installment schedule for one semester, USD and LBP together, in bill order. */
    public List<TuitionInstallment> findByStudentAndSemester(int studentId, int semesterId) {
        return queryList(SELECT + " WHERE student_id = ? AND semester_id = ? "
                + "ORDER BY currency, installment_no", MAPPER, studentId, semesterId);
    }

    public int rescheduleUnpaidForSemester(int semesterId,
                                           java.time.LocalDate firstDue,
                                           java.time.LocalDate lastDue) {
        return executeUpdate(
                ";WITH schedule AS ("
                + " SELECT installment_id, installment_no, "
                + " MAX(installment_no) OVER "
                + " (PARTITION BY student_id, semester_id, currency) AS installment_count "
                + " FROM dbo.student_tuition_installments "
                + " WHERE semester_id = ?"
                + ") "
                + "UPDATE sti SET "
                + " due_date = CASE "
                + "   WHEN s.installment_count <= 1 THEN CAST(? AS DATE) "
                + "   ELSE DATEADD(DAY, "
                + "     ((s.installment_no - 1) * DATEDIFF(DAY, CAST(? AS DATE), CAST(? AS DATE))) "
                + "       / (s.installment_count - 1), "
                + "     CAST(? AS DATE)) "
                + " END, "
                + " status = CASE WHEN sti.status = 'OVERDUE' THEN 'UNPAID' ELSE sti.status END, "
                + " penalty = 0 "
                + "FROM dbo.student_tuition_installments sti "
                + "JOIN schedule s ON s.installment_id = sti.installment_id "
                + "WHERE sti.semester_id = ? "
                + " AND sti.status IN ('UNPAID', 'PARTIALLY_PAID', 'OVERDUE') "
                + " AND sti.payment_date IS NULL",
                semesterId,
                firstDue,
                firstDue,
                lastDue,
                firstDue,
                semesterId);
    }

    public int refreshDelinquency(int semesterId) {
        return executeUpdate(
                "UPDATE dbo.student_tuition_installments "
                + "SET status = 'OVERDUE', "
                + "    penalty = CASE "
                + "        WHEN currency = 'USD' AND ISNULL(penalty, 0) = 0 THEN 10 "
                + "        WHEN currency = 'LBP' AND ISNULL(penalty, 0) = 0 THEN 900000 "
                + "        ELSE penalty "
                + "    END "
                + "WHERE semester_id = ? "
                + "  AND status IN ('UNPAID', 'PARTIALLY_PAID', 'OVERDUE') "
                + "  AND payment_date IS NULL "
                + "  AND due_date < CAST(GETDATE() AS DATE)",
                semesterId);
    }
    /**
     * Wipes one student's installment schedule for one semester, so {@link
     * com.university.service.TuitionService#billFor} can regenerate it from the student's current
     * (post-drop) charges. Only ever called after the caller has confirmed none of these rows carry
     * a real payment ({@code payment_date IS NOT NULL}) -- an unpaid, not-yet-billed schedule is a
     * computed projection, not a historical record, so replacing it is safe; money actually
     * received never is.
     */
    public int deleteByStudentAndSemester(int studentId, int semesterId) {
        return executeUpdate("DELETE FROM dbo.student_tuition_installments "
                + "WHERE student_id = ? AND semester_id = ?", studentId, semesterId);
    }

    public int insert(Connection connection, TuitionInstallment installment) {
        return insertAndReturnKey(connection, INSERT,
                installment.getStudentId(),
                installment.getSemesterId(),
                installment.getCurrency(),
                installment.getInstallmentNo(),
                installment.getBankReference(),
                installment.getAmount(),
                installment.getDueDate(),
                installment.getPaymentDate(),
                installment.getPenalty(),
                installment.getStatus());
    }
}






