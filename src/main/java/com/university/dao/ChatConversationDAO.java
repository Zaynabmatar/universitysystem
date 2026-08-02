package com.university.dao;

import com.university.enums.ChatCategory;
import com.university.enums.ChatStatus;
import com.university.model.ChatConversation;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code dbo.chat_conversations}.
 */
public class ChatConversationDAO extends AbstractDAO implements GenericDAO<ChatConversation> {

    private static final String SELECT =
            "SELECT conversation_id, student_id, assigned_admin_user_id, subject, category, "
            + "status, created_at, updated_at FROM dbo.chat_conversations";

    private static final String INSERT =
            "INSERT INTO dbo.chat_conversations (student_id, assigned_admin_user_id, subject, "
            + "category, status) VALUES (?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE dbo.chat_conversations SET assigned_admin_user_id = ?, subject = ?, "
            + "category = ?, status = ?, updated_at = ? WHERE conversation_id = ?";

    private static final String DELETE =
            "DELETE FROM dbo.chat_conversations WHERE conversation_id = ?";

    private static final RowMapper<ChatConversation> MAPPER = ChatConversationDAO::mapRow;

    static ChatConversation mapRow(ResultSet rs) throws SQLException {
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId(rs.getInt("conversation_id"));
        conversation.setStudentId(rs.getInt("student_id"));
        // Null until an administrator picks the ticket up.
        conversation.setAssignedAdminUserId(DaoUtils.getInteger(rs, "assigned_admin_user_id"));
        conversation.setSubject(rs.getString("subject"));
        conversation.setCategory(ChatCategory.fromDb(rs.getString("category")));
        conversation.setStatus(ChatStatus.fromDb(rs.getString("status")));
        conversation.setCreatedAt(DaoUtils.getLocalDateTime(rs, "created_at"));
        conversation.setUpdatedAt(DaoUtils.getLocalDateTime(rs, "updated_at"));
        return conversation;
    }

    @Override
    public Optional<ChatConversation> findById(int id) {
        return queryOne(SELECT + " WHERE conversation_id = ?", MAPPER, id);
    }

    @Override
    public List<ChatConversation> findAll() {
        return queryList(SELECT + " ORDER BY created_at DESC", MAPPER);
    }

    /** Every ticket one student has raised. */
    public List<ChatConversation> findByStudent(int studentId) {
        return queryList(SELECT + " WHERE student_id = ? ORDER BY created_at DESC",
                MAPPER, studentId);
    }

    /** The tickets waiting for somebody to pick them up. */
    public List<ChatConversation> findUnassigned() {
        return queryList(SELECT + " WHERE assigned_admin_user_id IS NULL AND status = 'OPEN' "
                + "ORDER BY created_at", MAPPER);
    }

    /** The tickets one administrator is handling. */
    public List<ChatConversation> findByAdmin(int adminUserId) {
        return queryList(SELECT + " WHERE assigned_admin_user_id = ? ORDER BY updated_at DESC",
                MAPPER, adminUserId);
    }

    /** Every ticket still needing attention, oldest first. */
    public List<ChatConversation> findOpen() {
        return queryList(SELECT + " WHERE status IN ('OPEN', 'IN_PROGRESS') ORDER BY created_at",
                MAPPER);
    }

    /** Tickets of one subject area. */
    public List<ChatConversation> findByCategory(ChatCategory category) {
        return queryList(SELECT + " WHERE category = ? ORDER BY created_at DESC", MAPPER, category);
    }

    /** Takes a ticket and moves it to in progress in one statement. */
    public boolean assignToAdmin(int conversationId, int adminUserId, LocalDateTime moment) {
        return executeUpdate("UPDATE dbo.chat_conversations SET assigned_admin_user_id = ?, "
                + "status = 'IN_PROGRESS', updated_at = ? WHERE conversation_id = ?",
                adminUserId, moment, conversationId) > 0;
    }

    /** Moves a ticket to another state. */
    public boolean setStatus(int conversationId, ChatStatus status, LocalDateTime moment) {
        return executeUpdate("UPDATE dbo.chat_conversations SET status = ?, updated_at = ? "
                + "WHERE conversation_id = ?", status, moment, conversationId) > 0;
    }

    @Override
    public int insert(ChatConversation entity) {
        return insertAndReturnKey(INSERT, insertParams(entity));
    }

    @Override
    public int insert(Connection connection, ChatConversation entity) {
        return insertAndReturnKey(connection, INSERT, insertParams(entity));
    }

    @Override
    public boolean update(ChatConversation entity) {
        return executeUpdate(UPDATE, updateParams(entity)) > 0;
    }

    @Override
    public boolean update(Connection connection, ChatConversation entity) {
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

    private Object[] insertParams(ChatConversation entity) {
        return new Object[]{
                entity.getStudentId(),
                entity.getAssignedAdminUserId(),
                entity.getSubject(),
                entity.getCategory(),
                entity.getStatus()
        };
    }

    private Object[] updateParams(ChatConversation entity) {
        return new Object[]{
                entity.getAssignedAdminUserId(),
                entity.getSubject(),
                entity.getCategory(),
                entity.getStatus(),
                entity.getUpdatedAt(),
                entity.getConversationId()
        };
    }
}
