/* ============================================================================
   0015 — add dbo.calendar_events

   WHY
     The Academic Calendar (Student "My Calendar", Admin "Academic Calendar")
     reads most of its dates straight from tables that already exist —
     dbo.semesters (term dates, registration window, drop/withdraw deadlines),
     dbo.exams and dbo.student_invoices (due dates) — so none of that is
     duplicated here. What has never had anywhere to live is an Admin-authored
     entry that isn't one of those system dates: a holiday, an orientation
     day, a one-off university event. This table holds only that.

   WHAT IT DOES
     Creates dbo.calendar_events: one row per custom calendar entry, always
     scoped to one semester (the calendar always follows a selected/current
     semester), with a fixed event_type (matching enums.CalendarEventType —
     the color per type is fixed in the UI, not stored here) and a date range
     (start_date = end_date for a single-day entry). is_active supports
     "Deactivate" without losing history; the row is only removed by an
     explicit delete.

   SAFE TO RE-RUN
     Guarded on sys.tables, so a second run is a no-op.
============================================================================ */

SET NOCOUNT ON;

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = N'calendar_events' AND schema_id = SCHEMA_ID(N'dbo'))
BEGIN
    PRINT N'0015: creating dbo.calendar_events.';

    CREATE TABLE dbo.calendar_events
    (
        event_id      INT            IDENTITY(1,1) NOT NULL,
        semester_id   INT            NOT NULL,
        title         NVARCHAR(150)  NOT NULL,
        event_type    NVARCHAR(30)   NOT NULL,
        start_date    DATE           NOT NULL,
        end_date      DATE           NOT NULL,
        description   NVARCHAR(500)  NULL,
        is_active     BIT            NOT NULL
                      CONSTRAINT DF_calendar_events_is_active DEFAULT (1),
        created_by    INT            NULL,
        created_at    DATETIME2      NOT NULL
                      CONSTRAINT DF_calendar_events_created_at DEFAULT (SYSUTCDATETIME()),
        updated_at    DATETIME2      NULL,

        CONSTRAINT PK_calendar_events PRIMARY KEY (event_id),

        CONSTRAINT FK_calendar_events_semester   FOREIGN KEY (semester_id)
            REFERENCES dbo.semesters (semester_id) ON DELETE NO ACTION,
        CONSTRAINT FK_calendar_events_created_by FOREIGN KEY (created_by)
            REFERENCES dbo.users (user_id)         ON DELETE NO ACTION,

        CONSTRAINT CK_calendar_events_dates CHECK (end_date >= start_date),
        CONSTRAINT CK_calendar_events_type  CHECK (event_type IN (
            N'REGISTRATION', N'ADD_DROP', N'WITHDRAW', N'EXAM', N'PAYMENT',
            N'HOLIDAY', N'UNIVERSITY_EVENT', N'OTHER'))
    );

    CREATE NONCLUSTERED INDEX IX_calendar_events_semester ON dbo.calendar_events (semester_id);
END
ELSE
BEGIN
    PRINT N'0015: dbo.calendar_events already exists - nothing to do.';
END
