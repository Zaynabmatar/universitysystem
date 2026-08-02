package com.university.model;

import com.university.enums.AuditActionType;

import java.time.LocalDateTime;

/**
 * One row of {@code dbo.audit_log}.
 *
 * <p>These rows are written by database triggers, never by application code,
 * so this class is used for reading and reporting only.</p>
 *
 * <p>{@code userId} is null when the change came from a background job or
 * from somebody working directly in the database, where there is no
 * application user.</p>
 */
public class AuditLog {

    private int logId;
    private Integer userId;
    private AuditActionType actionType;
    private String tableName;
    private Integer recordId;
    private String oldValue;
    private String newValue;
    private String description;
    private LocalDateTime createdAt;

    public AuditLog() {
    }

    public AuditLog(int logId, Integer userId, AuditActionType actionType, String tableName,
                    Integer recordId, String oldValue, String newValue, String description,
                    LocalDateTime createdAt) {
        this.logId = logId;
        this.userId = userId;
        this.actionType = actionType;
        this.tableName = tableName;
        this.recordId = recordId;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.description = description;
        this.createdAt = createdAt;
    }

    public int getLogId() {
        return logId;
    }

    public void setLogId(int logId) {
        this.logId = logId;
    }

    /** Null when no application user was behind the change. */
    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public AuditActionType getActionType() {
        return actionType;
    }

    public void setActionType(AuditActionType actionType) {
        this.actionType = actionType;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /** The primary key of the affected row, null for a bulk change. */
    public Integer getRecordId() {
        return recordId;
    }

    public void setRecordId(Integer recordId) {
        this.recordId = recordId;
    }

    /** The value before the change, null for an insert. */
    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    /** The value after the change, null for a delete. */
    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return actionType + " on " + tableName + " record " + recordId;
    }
}
