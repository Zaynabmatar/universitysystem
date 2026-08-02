package com.university.model;

import com.university.enums.ChatCategory;
import com.university.enums.ChatStatus;

import java.time.LocalDateTime;

/**
 * One row of {@code dbo.chat_conversations}, the header of a support ticket
 * between a student and administration.
 *
 * <p>{@code assignedAdminUserId} is null until an administrator picks the
 * ticket up.</p>
 */
public class ChatConversation {

    private int conversationId;
    private int studentId;
    private Integer assignedAdminUserId;
    private String subject;
    private ChatCategory category;
    private ChatStatus status = ChatStatus.OPEN;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ChatConversation() {
    }

    public ChatConversation(int conversationId, int studentId, Integer assignedAdminUserId,
                            String subject, ChatCategory category, ChatStatus status,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.conversationId = conversationId;
        this.studentId = studentId;
        this.assignedAdminUserId = assignedAdminUserId;
        this.subject = subject;
        this.category = category;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public int getConversationId() {
        return conversationId;
    }

    public void setConversationId(int conversationId) {
        this.conversationId = conversationId;
    }

    public int getStudentId() {
        return studentId;
    }

    public void setStudentId(int studentId) {
        this.studentId = studentId;
    }

    /** Null while nobody has taken the ticket. */
    public Integer getAssignedAdminUserId() {
        return assignedAdminUserId;
    }

    public void setAssignedAdminUserId(Integer assignedAdminUserId) {
        this.assignedAdminUserId = assignedAdminUserId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public ChatCategory getCategory() {
        return category;
    }

    public void setCategory(ChatCategory category) {
        this.category = category;
    }

    public ChatStatus getStatus() {
        return status;
    }

    public void setStatus(ChatStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** True when no administrator has been assigned yet. */
    public boolean isUnassigned() {
        return assignedAdminUserId == null;
    }

    @Override
    public String toString() {
        return subject + " (" + status + ")";
    }
}
