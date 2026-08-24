package com.university.model;

import java.time.LocalDateTime;

/**
 * One row of {@code dbo.student_service_requests} — a request a student
 * submitted from Online Service / Student Services (Enrollment Certificate,
 * Official Transcript, Student ID Card, Transportation, Financial, IT
 * Support, University Internet Service). Backs the "My Requests" list and
 * count on that same screen.
 */
public record StudentServiceRequest(
        int requestId,
        int studentId,
        String serviceName,
        String status,
        String details,
        LocalDateTime submittedAt
) {
}
