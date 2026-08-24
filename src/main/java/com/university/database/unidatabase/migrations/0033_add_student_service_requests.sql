/*
    Student Service Requests

    - Stores every request a student submits from Online Service / Student
      Services (Enrollment Certificate, Official Transcript, Student ID Card,
      Transportation, Financial, IT Support, University Internet Service).
    - Backs the "My Requests" count/list on that same screen, which previously
      had nothing to read from and always showed zero.
*/

IF OBJECT_ID('dbo.student_service_requests', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.student_service_requests
    (
        request_id    INT            IDENTITY(1,1) NOT NULL,
        student_id    INT            NOT NULL,
        service_name  NVARCHAR(100)  NOT NULL,
        status        NVARCHAR(30)   NOT NULL
                      CONSTRAINT DF_student_service_requests_status
                      DEFAULT (N'Will be ready soon'),
        details       NVARCHAR(1000) NULL,
        submitted_at  DATETIME2      NOT NULL
                      CONSTRAINT DF_student_service_requests_submitted
                      DEFAULT (SYSDATETIME()),

        CONSTRAINT PK_student_service_requests
            PRIMARY KEY (request_id),

        CONSTRAINT FK_student_service_requests_student
            FOREIGN KEY (student_id)
            REFERENCES dbo.students (student_id)
            ON DELETE CASCADE
    );

    CREATE INDEX IX_student_service_requests_student
        ON dbo.student_service_requests (student_id, submitted_at DESC);
END;

-- New requests are never created "Ready for pick up" — that status is only
-- assigned later, when staff finish processing a request. Anything already
-- sitting on the old default or on a pickup status before this migration ran
-- is not actually ready, so back it out to the real starting status.
UPDATE dbo.student_service_requests
SET status = N'Will be ready soon'
WHERE status IN (N'Request Received', N'Ready for pick up', N'Ready for Pickup', N'Ready for Pick Up');
