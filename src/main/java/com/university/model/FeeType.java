package com.university.model;

/**
 * One row of {@code dbo.fee_types}, for example Tuition or Laboratory.
 */
public class FeeType {

    private int feeTypeId;
    private String feeName;
    private String description;
    private boolean active = true;

    public FeeType() {
    }

    public FeeType(int feeTypeId, String feeName, String description, boolean active) {
        this.feeTypeId = feeTypeId;
        this.feeName = feeName;
        this.description = description;
        this.active = active;
    }

    public int getFeeTypeId() {
        return feeTypeId;
    }

    public void setFeeTypeId(int feeTypeId) {
        this.feeTypeId = feeTypeId;
    }

    public String getFeeName() {
        return feeName;
    }

    public void setFeeName(String feeName) {
        this.feeName = feeName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return feeName;
    }
}
