package com.university.model;

import java.math.BigDecimal;

/**
 * One row of {@code dbo.tuition_rates}: the USD and LBP price per credit for
 * one semester, plus how many installments a bill splits into.
 *
 * <p>The single source of truth a tuition amount is computed from — see
 * {@code TuitionService} — so no controller hard-codes a rate.</p>
 */
public class TuitionRate {

    private int semesterId;
    private BigDecimal usdRatePerCredit = BigDecimal.ZERO;
    private BigDecimal lbpRatePerCredit = BigDecimal.ZERO;
    private int installmentCount = 3;
    private BigDecimal latePenaltyUsd = BigDecimal.ZERO;
    private BigDecimal latePenaltyLbp = BigDecimal.ZERO;

    public int getSemesterId() {
        return semesterId;
    }

    public void setSemesterId(int semesterId) {
        this.semesterId = semesterId;
    }

    public BigDecimal getUsdRatePerCredit() {
        return usdRatePerCredit;
    }

    public void setUsdRatePerCredit(BigDecimal usdRatePerCredit) {
        this.usdRatePerCredit = usdRatePerCredit;
    }

    public BigDecimal getLbpRatePerCredit() {
        return lbpRatePerCredit;
    }

    public void setLbpRatePerCredit(BigDecimal lbpRatePerCredit) {
        this.lbpRatePerCredit = lbpRatePerCredit;
    }

    public int getInstallmentCount() {
        return installmentCount;
    }

    public void setInstallmentCount(int installmentCount) {
        this.installmentCount = installmentCount;
    }

    public BigDecimal getLatePenaltyUsd() {
        return latePenaltyUsd;
    }

    public void setLatePenaltyUsd(BigDecimal latePenaltyUsd) {
        this.latePenaltyUsd = latePenaltyUsd;
    }

    public BigDecimal getLatePenaltyLbp() {
        return latePenaltyLbp;
    }

    public void setLatePenaltyLbp(BigDecimal latePenaltyLbp) {
        this.latePenaltyLbp = latePenaltyLbp;
    }
}
