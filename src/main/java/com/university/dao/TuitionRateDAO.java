package com.university.dao;

import com.university.model.TuitionRate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Reads {@code dbo.tuition_rates}. Written only by migration 0003; the
 * application never inserts or updates a rate.
 */
public class TuitionRateDAO extends AbstractDAO {

    private static final String SELECT =
            "SELECT semester_id, usd_rate_per_credit, lbp_rate_per_credit, installment_count, "
            + "late_penalty_usd, late_penalty_lbp FROM dbo.tuition_rates";

    private static final RowMapper<TuitionRate> MAPPER = TuitionRateDAO::mapRow;

    static TuitionRate mapRow(ResultSet rs) throws SQLException {
        TuitionRate rate = new TuitionRate();
        rate.setSemesterId(rs.getInt("semester_id"));
        rate.setUsdRatePerCredit(rs.getBigDecimal("usd_rate_per_credit"));
        rate.setLbpRatePerCredit(rs.getBigDecimal("lbp_rate_per_credit"));
        rate.setInstallmentCount(rs.getInt("installment_count"));
        rate.setLatePenaltyUsd(rs.getBigDecimal("late_penalty_usd"));
        rate.setLatePenaltyLbp(rs.getBigDecimal("late_penalty_lbp"));
        return rate;
    }

    /** The configured rate for one semester, if tuition has been configured for it. */
    public Optional<TuitionRate> findBySemester(int semesterId) {
        return queryOne(SELECT + " WHERE semester_id = ?", MAPPER, semesterId);
    }
}
