package com.university.dao;

import com.university.model.ChatMessage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.chat_messages}.
 */
public class ChatMessageDAO extends AbstractDAO implements GenericDAO<ChatMessage> {

    private static final String SELECT =
            "SELECT message_id, conversation_id, sender_user_id, message_text, sent_at, is_read "
            + "FROM dbo.chat_messages";

    private static final String INSERT =
            "INSERT INTO dbo.chat_messages (conversation_id, sender_user_id, message_text, is_read) "
            + "VALUES (?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.chat_messages SET message_text = ?, is_read = ? WHERE message_id = ?";

    private static final String DELETE = "DELETE FROM dbo.chat_messages WHERE message_id = ?";

    private static final RowMapper<ChatMessage> MAPPER = ChatMessageDAO::mapRow;

    static ChatMessage mapRow(ResultSet rs) throws SQLException {
        ChatMessage message = new ChatMessage();
        message.setMessageId(rs.getInt("message_id"));
        message.setConversationId(rs.getInt("conversation_id"));
        message.setSenderUserId(rs.getInt("sender_user_id"));
        message.setMessageText(rs.getString("message_text"));
        message.setSentAt(DaoUtils.getLocalDateTime(rs, "sent_at"));
        message.setRead(rs.getBoolean("is_read"));
        return message;
    }

    @Override
    public Optional<ChatMessage> findById(int id) {
        return queryOne(SELECT + " WHERE message_id = ?", MAPPER, id);
    }

    @Override
    public List<ChatMessage> findAll() {
        return queryList(SELECT + " ORDER BY sent_at", MAPPER);
    }

    /** The whole thread of one ticket, oldest first. */
    public List<ChatMessage> findByConversation(int conversationId) {
        return queryList(SELECT + " WHERE conversation_id = ? ORDER BY sent_at",
                MAPPER, conversationId);
    }

    /** The most recent line of a ticket, for the list preview. */
    public Optional<ChatMessage> findLatestInConversation(int conversationId) {
        return queryOne("SELECT TOP 1 message_id, conversation_id, sender_user_id, message_text, "
                + "sent_at, is_read FROM dbo.chat_messages WHERE conversation_id = ? "
                + "ORDER BY sent_at DESC", MAPPER, conversationId);
    }

    /**
     * How many lines in a ticket the given reader has not seen.
     *
     * <p>Messages the reader sent themselves are excluded, since nobody needs
     * telling about their own words.</p>
     */
    public int countUnread(int conversationId, int readerUserId) {
        return queryInt("SELECT COUNT(*) FROM dbo.chat_messages "
                + "WHERE conversation_id = ? AND sender_user_id <> ? AND is_read = 0",
                conversationId, readerUserId);
    }

    /** Every unread line addressed to one reader, across all their tickets. */
    public int countAllUnreadForUser(int readerUserId) {
        return queryInt("SELECT COUNT(*) FROM dbo.chat_messages m "
                + "INNER JOIN dbo.chat_conversations c "
                + "ON c.conversation_id = m.conversation_id "
                + "WHERE m.sender_user_id <> ? AND m.is_read = 0 "
                + "AND (c.assigned_admin_user_id = ? "
                + "     OR c.student_id IN (SELECT student_id FROM dbo.students WHERE user_id = ?))",
                readerUserId, readerUserId, readerUserId);
    }

    /** Marks everything the reader has now seen in one ticket. */
    public int markConversationRead(int conversationId, int readerUserId) {
        return executeUpdate("UPDATE dbo.chat_messages SET is_read = 1 "
                + "WHERE conversation_id = ? AND sender_user_id <> ? AND is_read = 0",
                conversationId, readerUserId);
    }

    @Override
    public int insert(ChatMessage entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, ChatMessage entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(ChatMessage entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, ChatMessage entity) {
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

    private Object[] insertParams(ChatMessage entity) {
        return new Object[]{
                entity.getConversationId(),
                entity.getSenderUserId(),
                entity.getMessageText(),
                entity.isRead()
        };
    }

    private Object[] updateParams(ChatMessage entity) {
        return new Object[]{
                entity.getMessageText(),
                entity.isRead(),
                entity.getMessageId()
        };
    }
}
