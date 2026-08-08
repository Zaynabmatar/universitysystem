/* ============================================================================
   0002 — add dbo.exams

   WHY
     The Student "Classes" page needs an Exams section (course, date, day,
     time, duration, room) that is driven by the student's real enrollments,
     and there was no table anywhere to store a scheduled exam. Grades already
     have midterm_mark/final_mark, but those are numeric marks tied 1:1 to an
     enrollment — not a scheduled event with a date/time/room that an
     instructor announces to a whole section.

   WHAT IT DOES
     Creates dbo.exams: one row per section per exam. day_of_week is not
     stored — it is derived from exam_date, which is the only source of
     truth. A student's exams are read through
     exams -> sections -> enrollments (status <> DROPPED) -> student_id, so
     no exam data is duplicated per student and a student never sees an exam
     for a section they are not enrolled in.

   SAFE TO RE-RUN
     Guarded on sys.tables, so a second run is a no-op.
============================================================================ */

SET NOCOUNT ON;

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = N'exams' AND schema_id = SCHEMA_ID(N'dbo'))
BEGIN
    PRINT N'0002: creating dbo.exams.';

    CREATE TABLE dbo.exams
    (
        exam_id          INT          IDENTITY(1,1) NOT NULL,
        section_id       INT          NOT NULL,
        exam_date        DATE         NOT NULL,
        start_time       TIME         NOT NULL,
        duration_minutes INT          NOT NULL,
        room             NVARCHAR(20) NULL,
        created_by       INT          NULL,
        created_at       DATETIME2    NOT NULL
                         CONSTRAINT DF_exams_created_at DEFAULT (SYSUTCDATETIME()),

        CONSTRAINT PK_exams PRIMARY KEY (exam_id),

        CONSTRAINT FK_exams_section    FOREIGN KEY (section_id)
            REFERENCES dbo.sections (section_id) ON DELETE NO ACTION,
        CONSTRAINT FK_exams_created_by FOREIGN KEY (created_by)
            REFERENCES dbo.users (user_id)       ON DELETE NO ACTION,

        CONSTRAINT CK_exams_duration CHECK (duration_minutes > 0)
    );

    CREATE NONCLUSTERED INDEX IX_exams_section ON dbo.exams (section_id);
END
ELSE
BEGIN
    PRINT N'0002: dbo.exams already exists - nothing to do.';
END
