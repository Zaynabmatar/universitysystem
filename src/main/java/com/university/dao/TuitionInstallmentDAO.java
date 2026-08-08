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

    /** One student's installment schedule for one semester, USD and LBP together, in bill order. */
    public List<TuitionInstallment> findByStudentAndSemester(int studentId, int semesterId) {
        return queryList(SELECT + " WHERE student_id = ? AND semester_id = ? "
                + "ORDER BY currency, installment_no", MAPPER, studentId, semesterId);
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
