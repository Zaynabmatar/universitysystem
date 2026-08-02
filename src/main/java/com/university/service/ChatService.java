package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.ChatConversationDAO;
import com.university.dao.ChatMessageDAO;
import com.university.dao.StudentDAO;
import com.university.enums.ChatCategory;
import com.university.enums.ChatStatus;
import com.university.enums.NotificationType;
import com.university.model.ChatConversation;
import com.university.model.ChatMessage;
import com.university.model.Student;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The message thread between a student and the administration.
 *
 * <p>A ticket and its first message are created together: a ticket with no
 * message in it would show in the queue as something to answer with nothing
 * to read.</p>
 */
public class ChatService {

    private final ChatConversationDAO conversationDao = new ChatConversationDAO();
    private final ChatMessageDAO messageDao = new ChatMessageDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final NotificationService notifications = new NotificationService();

    private final AbstractDAO transactions = new AbstractDAO() {
    };

    /**
     * Opens a ticket and posts the opening message.
     *
     * @return the new ticket's key
     */
    public int openTicket(int studentId, String subject, ChatCategory category,
                          String firstMessage) {
        ValidationException.requireId(studentId, "Student");
        ValidationException.requireText(subject, "Subject");
        ValidationException.requireNonNull(category, "Category");
        ValidationException.requireText(firstMessage, "Message");

        Student student = studentDao.findById(studentId)
                .orElseThrow(() -> new ServiceException("That student record was not found."));

        ChatConversation conversation = new ChatConversation();
        conversation.setStudentId(studentId);
        conversation.setAssignedAdminUserId(null);
        conversation.setSubject(subject.trim());
        conversation.setCategory(category);
        conversation.setStatus(ChatStatus.OPEN);

        Connection connection = transactions.beginTransaction();
        try {
            int conversationId = conversationDao.insert(connection, conversation);

            ChatMessage message = new ChatMessage();
            message.setConversationId(conversationId);
            message.setSenderUserId(student.getUserId());
            message.setMessageText(firstMessage.trim());
            message.setRead(false);
            messageDao.insert(connection, message);

            connection.commit();
            return conversationId;
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The message could not be sent.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    /**
     * Adds a line to a ticket and tells the other side.
     *
     * @return the new message's key
     * @throws ServiceException when the ticket is closed
     */
    public int reply(int conversationId, int senderUserId, String text) {
        ValidationException.requireId(conversationId, "Conversation");
        ValidationException.requireText(text, "Message");

        ChatConversation conversation = requireConversation(conversationId);
        if (conversation.getStatus() == ChatStatus.CLOSED) {
            throw new ServiceException("This conversation is closed. Please open a new one.");
        }

        Student student = studentDao.findById(conversation.getStudentId())
                .orElseThrow(() -> new ServiceException("That student record was not found."));

        boolean fromStudent = senderUserId == student.getUserId();
        LocalDateTime now = LocalDateTime.now();

        Connection connection = transactions.beginTransaction();
        try {
            ChatMessage message = new ChatMessage();
            message.setConversationId(conversationId);
            message.setSenderUserId(senderUserId);
            message.setMessageText(text.trim());
            message.setRead(false);
            int messageId = messageDao.insert(connection, message);

            conversation.setUpdatedAt(now);
            conversationDao.update(connection, conversation);

            // Only the student gets an inbox message. Staff work the queue.
            if (!fromStudent) {
                notifications.notify(connection, student.getUserId(),
                        NotificationType.CHAT_REPLY,
                        "Reply about " + conversation.getSubject(),
                        "The administration has replied to your message.",
                        "chat_conversations", conversationId);
            }

            connection.commit();
            return messageId;
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The reply could not be sent.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    /**
     * Takes a ticket out of the unassigned queue and moves it to in progress.
     *
     * @throws ServiceException when somebody else already holds it
     */
    public void assignToMe(int conversationId, int adminUserId) {
        ChatConversation conversation = requireConversation(conversationId);
        if (!conversation.isUnassigned()
                && conversation.getAssignedAdminUserId() != adminUserId) {
            throw new ServiceException("Another member of staff is already handling this.");
        }
        conversationDao.assignToAdmin(conversationId, adminUserId, LocalDateTime.now());
    }

    /** Marks a ticket resolved, leaving it open to reply to. */
    public void resolve(int conversationId) {
        requireConversation(conversationId);
        conversationDao.setStatus(conversationId, ChatStatus.RESOLVED, LocalDateTime.now());
    }

    /** Closes a ticket for good. */
    public void close(int conversationId) {
        requireConversation(conversationId);
        conversationDao.setStatus(conversationId, ChatStatus.CLOSED, LocalDateTime.now());
    }

    /** The whole thread, oldest first. */
    public List<ChatMessage> thread(int conversationId) {
        return messageDao.findByConversation(conversationId);
    }

    /** Opens a thread and marks everything in it as seen by this reader. */
    public List<ChatMessage> openThread(int conversationId, int readerUserId) {
        messageDao.markConversationRead(conversationId, readerUserId);
        return messageDao.findByConversation(conversationId);
    }

    /** One student's tickets. */
    public List<ChatConversation> ticketsOf(int studentId) {
        return conversationDao.findByStudent(studentId);
    }

    /** The tickets nobody has picked up. */
    public List<ChatConversation> unassignedQueue() {
        return conversationDao.findUnassigned();
    }

    /** The tickets one member of staff is handling. */
    public List<ChatConversation> assignedTo(int adminUserId) {
        return conversationDao.findByAdmin(adminUserId);
    }

    /** Every ticket still needing attention. */
    public List<ChatConversation> openTickets() {
        return conversationDao.findOpen();
    }

    /** How many unread lines are waiting for this reader. */
    public int unreadCount(int readerUserId) {
        return messageDao.countAllUnreadForUser(readerUserId);
    }

    private ChatConversation requireConversation(int conversationId) {
        return conversationDao.findById(conversationId)
                .orElseThrow(() -> new ServiceException("That conversation was not found."));
    }
}
