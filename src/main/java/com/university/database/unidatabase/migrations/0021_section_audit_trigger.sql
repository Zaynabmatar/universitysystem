-- Audits dbo.sections the same way 0014_audit_log_triggers.sql audited every other
-- administered table: one AFTER INSERT, UPDATE, DELETE trigger, writing straight into the
-- existing dbo.audit_log, nothing new on the Java side.
--
-- Admin > Sections no longer has a hard Delete button (it offers Activate/Deactivate only, backed
-- by the existing SectionStatus OPEN/CANCELLED values -- no new status was needed), so the
-- meaningful action here is the UPDATE branch: course/semester/instructor/campus/section_number/
-- capacity/room/status, which is exactly how a Deactivate ("status=CANCELLED") or Activate
-- ("status=OPEN") action shows up, alongside ordinary edits from SectionFormDialog. There is
-- nothing to distinguish "Deactivate" from any other status-changing UPDATE, the same way
-- trg_User_Audit does not split is_active out from its other tracked columns.
--
-- enrolled_count is deliberately left OUT of the tracked columns, the same way
-- 0014_audit_log_triggers.sql documents for every other cached/derived counter: it changes on
-- every enrolment/drop and auditing it would bury the real administrative edits in noise.
--
-- INSERT/DELETE are covered for completeness, matching every other trg_*_Audit trigger --
-- SectionService no longer offers a delete at all (a zero-enrolment section could still be
-- removed directly in SSMS), so that branch exists purely so a direct database delete is not
-- invisible, never because the application is expected to use it.

IF OBJECT_ID('dbo.trg_Section_Audit', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_Section_Audit;
GO

CREATE TRIGGER dbo.trg_Section_Audit
ON dbo.sections
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted) AND NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    DECLARE @actor INT = TRY_CONVERT(INT, SESSION_CONTEXT(N'app_user_id'));

    -- UPDATE: only the tracked columns that actually changed -- SectionDAO.update writes every
    -- column on every save, so without this check pressing "Edit" -> Save with nothing changed
    -- would log a change that never happened.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'UPDATE', N'sections', i.section_id, c.old_value, c.new_value,
           CONCAT(N'Section ', i.section_id, N' updated: ', c.new_value)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.section_id = i.section_id
        CROSS APPLY (
            SELECT
                old_value = CONCAT(
                    CASE WHEN i.course_id <> d.course_id THEN CONCAT(N'course_id=', d.course_id, N'; ') ELSE N'' END,
                    CASE WHEN i.semester_id <> d.semester_id THEN CONCAT(N'semester_id=', d.semester_id, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.instructor_id, -1) <> ISNULL(d.instructor_id, -1)
                         THEN CONCAT(N'instructor_id=', ISNULL(CONVERT(NVARCHAR(10), d.instructor_id), N'(TBA)'), N'; ') ELSE N'' END,
                    CASE WHEN i.campus_id <> d.campus_id THEN CONCAT(N'campus_id=', d.campus_id, N'; ') ELSE N'' END,
                    CASE WHEN i.section_number <> d.section_number THEN CONCAT(N'section_number=', d.section_number, N'; ') ELSE N'' END,
                    CASE WHEN i.capacity <> d.capacity THEN CONCAT(N'capacity=', d.capacity, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.room, NCHAR(1)) <> ISNULL(d.room, NCHAR(1))
                         THEN CONCAT(N'room=', ISNULL(d.room, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.status <> d.status THEN CONCAT(N'status=', d.status, N'; ') ELSE N'' END
                ),
                new_value = CONCAT(
                    CASE WHEN i.course_id <> d.course_id THEN CONCAT(N'course_id=', i.course_id, N'; ') ELSE N'' END,
                    CASE WHEN i.semester_id <> d.semester_id THEN CONCAT(N'semester_id=', i.semester_id, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.instructor_id, -1) <> ISNULL(d.instructor_id, -1)
                         THEN CONCAT(N'instructor_id=', ISNULL(CONVERT(NVARCHAR(10), i.instructor_id), N'(TBA)'), N'; ') ELSE N'' END,
                    CASE WHEN i.campus_id <> d.campus_id THEN CONCAT(N'campus_id=', i.campus_id, N'; ') ELSE N'' END,
                    CASE WHEN i.section_number <> d.section_number THEN CONCAT(N'section_number=', i.section_number, N'; ') ELSE N'' END,
                    CASE WHEN i.capacity <> d.capacity THEN CONCAT(N'capacity=', i.capacity, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.room, NCHAR(1)) <> ISNULL(d.room, NCHAR(1))
                         THEN CONCAT(N'room=', ISNULL(i.room, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.status <> d.status THEN CONCAT(N'status=', i.status, N'; ') ELSE N'' END
                )
        ) AS c
    WHERE LEN(c.new_value) > 0;

    -- INSERT: a new section offering is created.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'INSERT', N'sections', i.section_id, NULL,
           CONCAT(N'course_id=', i.course_id, N'; semester_id=', i.semester_id, N'; section_number=',
                  i.section_number, N'; capacity=', i.capacity, N'; status=', i.status),
           CONCAT(N'Created section ', i.section_number, N' (section_id ', i.section_id, N').')
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.section_id = i.section_id);

    -- DELETE: Admin > Sections offers no Delete action any more (Activate/Deactivate only); kept
    -- so a direct SSMS delete is not invisible, the same as every other trg_*_Audit trigger.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'DELETE', N'sections', d.section_id,
           CONCAT(N'course_id=', d.course_id, N'; semester_id=', d.semester_id, N'; section_number=',
                  d.section_number, N'; status=', d.status),
           NULL,
           CONCAT(N'Deleted section ', d.section_number, N' (section_id ', d.section_id, N').')
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.section_id = d.section_id);
END;
GO

/* ---------------------------------------------------------------- verify */
SELECT name, OBJECT_NAME(parent_id) AS audited_table
FROM sys.triggers
WHERE name = N'trg_Section_Audit';
GO
