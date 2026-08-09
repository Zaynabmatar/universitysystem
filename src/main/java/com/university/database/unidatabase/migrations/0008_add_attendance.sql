-- Instructor Attendance feature: one row per enrolled student per scheduled class date.
-- A student's status (PRESENT/ABSENT) on a given section+date is unique, so re-saving the
-- same date always updates the existing row instead of creating a duplicate.

IF OBJECT_ID('dbo.attendance_records', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.attendance_records
    (
        attendance_id INT           IDENTITY(1,1) NOT NULL,
        enrollment_id INT           NOT NULL,
        section_id    INT           NOT NULL,
        class_date    DATE          NOT NULL,
        status        NVARCHAR(10)  NOT NULL
                      CONSTRAINT DF_attendance_records_status DEFAULT (N'PRESENT'),
        recorded_by   INT           NOT NULL,
        recorded_at   DATETIME2     NOT NULL
                      CONSTRAINT DF_attendance_records_recorded_at DEFAULT (SYSDATETIME()),

        CONSTRAINT PK_attendance_records PRIMARY KEY (attendance_id),

        -- The duplicate guard required by the feature spec: at most one attendance row per
        -- student (enrollment) per class date.
        CONSTRAINT UQ_attendance_records_enrollment_date UNIQUE (enrollment_id, class_date),

        CONSTRAINT FK_attendance_records_enrollment FOREIGN KEY (enrollment_id)
            REFERENCES dbo.enrollments (enrollment_id),
        CONSTRAINT FK_attendance_records_section FOREIGN KEY (section_id)
            REFERENCES dbo.sections (section_id),
        CONSTRAINT FK_attendance_records_recorded_by FOREIGN KEY (recorded_by)
            REFERENCES dbo.users (user_id),

        CONSTRAINT CK_attendance_records_status CHECK (status IN (N'PRESENT', N'ABSENT'))
    );
END
GO

IF OBJECT_ID('dbo.attendance_records', 'U') IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sys.indexes
                    WHERE name = 'IX_attendance_records_section_date'
                      AND object_id = OBJECT_ID('dbo.attendance_records'))
BEGIN
    -- Speeds up "load the roster's attendance for section X on date Y", the screen's main query.
    CREATE INDEX IX_attendance_records_section_date
        ON dbo.attendance_records (section_id, class_date);
END
GO
