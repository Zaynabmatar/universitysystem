package com.university.model;

import java.time.LocalDateTime;

/**
 * One row of {@code dbo.chat_messages}, a single line inside a conversation.
 *
 * <p>{@code senderUserId} may be the student or the assigned administrator,
 * which is why it points at {@code dbo.users} rather than at either side.</p>
 */
public class ChatMessage {

    private int messageId;
    private int conversationId;
    private int senderUserId;
    private String messageText;
    private LocalDateTime sentAt;
    private boolean read;

    public ChatMessage() {
    }

    public ChatMessage(int messageId, int conversationId, int senderUserId, String messageText,
                       LocalDateTime sentAt, boolean read) {
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.senderUserId = senderUserId;
        this.messageText = messageText;
        this.sentAt = sentAt;
        this.read = read;
    }

    public int getMessageId() {
        return messageId;
    }

    public void setMessageId(int messageId) {
        this.messageId = messageId;
    }

    public int getConversationId() {
        return conversationId;
    }

    public void setConversationId(int conversationId) {
        this.conversationId = conversationId;
    }

    public int getSenderUserId() {
        return senderUserId;
    }

    public void setSenderUserId(int senderUserId) {
        this.senderUserId = senderUserId;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    @Override
    public String toString() {
        return "user " + senderUserId + " at " + sentAt;
    }
}
