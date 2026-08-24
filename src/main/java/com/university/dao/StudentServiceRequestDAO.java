package com.university.dao;

import com.university.model.StudentServiceRequest;

import java.util.List;

/**
 * Reads and writes {@code dbo.student_service_requests} — every request a
 * student has submitted from Online Service / Student Services, and the
 * count that screen's "My Requests" section shows.
 */
public class StudentServiceRequestDAO extends AbstractDAO {

    private static final String INSERT =
            "INSERT INTO dbo.student_service_requests (student_id, service_name, status, details) "
            + "VALUES (?, ?, ?, ?)";

    private static final String SELECT_BY_STUDENT =
            "SELECT request_id, student_id, service_name, status, details, submitted_at "
            + "FROM dbo.student_service_requests WHERE student_id = ? ORDER BY submitted_at DESC";

    private static final String COUNT_BY_STUDENT =
            "SELECT COUNT(*) FROM dbo.student_service_requests WHERE student_id = ?";

    private static final RowMapper<StudentServiceRequest> MAPPER = rs -> new StudentServiceRequest(
            rs.getInt("request_id"),
            rs.getInt("student_id"),
            rs.getString("service_name"),
            rs.getString("status"),
            rs.getString("details"),
            DaoUtils.getLocalDateTime(rs, "submitted_at")
    );

    /** Records a newly submitted request and returns its generated id. */
    public int insert(int studentId, String serviceName, String status, String details) {
        return insertAndReturnKey(INSERT, studentId, serviceName, status, details);
    }

    /** Every request this student has submitted, most recent first. */
    public List<StudentServiceRequest> findByStudent(int studentId) {
        return queryList(SELECT_BY_STUDENT, MAPPER, studentId);
    }

    /** How many requests this student has submitted — what "My Requests" counts. */
    public int countByStudent(int studentId) {
        return queryInt(COUNT_BY_STUDENT, studentId);
    }
}
