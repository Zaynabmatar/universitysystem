-- Adds grade_points to the columns trg_Grade_Audit (0023_grade_audit_detail.sql) tracks.
--
-- 0023 tracks coursework_mark/midterm_mark/lab_mark/final_mark, the four publish flags,
-- is_submitted, total_mark and letter_grade -- but never grade_points. An Admin Correction
-- (GradeService.adminOverride) or a Submit and Lock always writes grade_points alongside
-- letter_grade (GradeDAO.overrideSubmitted / the plain UPDATE), so today that column's change
-- is silently invisible in the Activity Log detail even though letter_grade's is shown right
-- next to it. This migration adds a grade_points branch to both the old_value and new_value
-- CASE lists, following the same CROSS APPLY changed-columns pattern as every other tracked
-- column (0014_audit_log_triggers.sql, 0021_section_audit_trigger.sql, 0023).
--
-- Safe to re-run: the trigger is dropped and recreated if it exists.

IF OBJECT_ID('dbo.trg_Grade_Audit', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_Grade_Audit;
GO

CREATE TRIGGER dbo.trg_Grade_Audit
ON dbo.grades
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted) AND NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    -- UPDATE: only the tracked columns that actually changed.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT
        COALESCE(i.last_modified_by, i.submitted_by), N'UPDATE', N'grades', i.grade_id,
        c.old_value, c.new_value,
        CONCAT(N'Grade changed for enrollment ', i.enrollment_id, N': ', c.new_value)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.grade_id = i.grade_id
        CROSS APPLY (
            SELECT
                old_value = CONCAT(
                    CASE WHEN ISNULL(i.coursework_mark, -1) <> ISNULL(d.coursework_mark, -1)
                         THEN CONCAT(N'coursework_mark=', ISNULL(CONVERT(NVARCHAR(20), d.coursework_mark), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.midterm_mark, -1) <> ISNULL(d.midterm_mark, -1)
                         THEN CONCAT(N'midterm_mark=', ISNULL(CONVERT(NVARCHAR(20), d.midterm_mark), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.lab_mark, -1) <> ISNULL(d.lab_mark, -1)
                         THEN CONCAT(N'lab_mark=', ISNULL(CONVERT(NVARCHAR(20), d.lab_mark), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.final_mark, -1) <> ISNULL(d.final_mark, -1)
                         THEN CONCAT(N'final_mark=', ISNULL(CONVERT(NVARCHAR(20), d.final_mark), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.coursework_published <> d.coursework_published
                         THEN CONCAT(N'coursework_published=', d.coursework_published, N'; ') ELSE N'' END,
                    CASE WHEN i.midterm_published <> d.midterm_published
                         THEN CONCAT(N'midterm_published=', d.midterm_published, N'; ') ELSE N'' END,
                    CASE WHEN i.lab_published <> d.lab_published
                         THEN CONCAT(N'lab_published=', d.lab_published, N'; ') ELSE N'' END,
                    CASE WHEN i.final_published <> d.final_published
                         THEN CONCAT(N'final_published=', d.final_published, N'; ') ELSE N'' END,
                    CASE WHEN i.is_submitted <> d.is_submitted
                         THEN CONCAT(N'submitted=', d.is_submitted, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.total_mark, -1) <> ISNULL(d.total_mark, -1)
                         THEN CONCAT(N'total=', ISNULL(CONVERT(NVARCHAR(20), d.total_mark), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.letter_grade, N'~') <> ISNULL(d.letter_grade, N'~')
                         THEN CONCAT(N'letter=', ISNULL(d.letter_grade, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.grade_points, -1) <> ISNULL(d.grade_points, -1)
                         THEN CONCAT(N'points=', ISNULL(CONVERT(NVARCHAR(20), d.grade_points), N'(none)'), N'; ') ELSE N'' END
                ),
                new_value = CONCAT(
                    CASE WHEN ISNULL(i.coursework_mark, -1) <> ISNULL(d.coursework_mark, -1)
                         THEN CONCAT(N'coursework_mark=', ISNULL(CONVERT(NVARCHAR(20), i.coursework_mark), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.midterm_mark, -1) <> ISNULL(d.midterm_mark, -1)
                         THEN CONCAT(N'midterm_mark=', ISNULL(CONVERT(NVARCHAR(20), i.midterm_mark), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.lab_mark, -1) <> ISNULL(d.lab_mark, -1)
                         THEN CONCAT(N'lab_mark=', ISNULL(CONVERT(NVARCHAR(20), i.lab_mark), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.final_mark, -1) <> ISNULL(d.final_mark, -1)
                         THEN CONCAT(N'final_mark=', ISNULL(CONVERT(NVARCHAR(20), i.final_mark), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.coursework_published <> d.coursework_published
                         THEN CONCAT(N'coursework_published=', i.coursework_published, N'; ') ELSE N'' END,
                    CASE WHEN i.midterm_published <> d.midterm_published
                         THEN CONCAT(N'midterm_published=', i.midterm_published, N'; ') ELSE N'' END,
                    CASE WHEN i.lab_published <> d.lab_published
                         THEN CONCAT(N'lab_published=', i.lab_published, N'; ') ELSE N'' END,
                    CASE WHEN i.final_published <> d.final_published
                         THEN CONCAT(N'final_published=', i.final_published, N'; ') ELSE N'' END,
                    CASE WHEN i.is_submitted <> d.is_submitted
                         THEN CONCAT(N'submitted=', i.is_submitted, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.total_mark, -1) <> ISNULL(d.total_mark, -1)
                         THEN CONCAT(N'total=', ISNULL(CONVERT(NVARCHAR(20), i.total_mark), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.letter_grade, N'~') <> ISNULL(d.letter_grade, N'~')
                         THEN CONCAT(N'letter=', ISNULL(i.letter_grade, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.grade_points, -1) <> ISNULL(d.grade_points, -1)
                         THEN CONCAT(N'points=', ISNULL(CONVERT(NVARCHAR(20), i.grade_points), N'(none)'), N'; ') ELSE N'' END
                )
        ) AS c
    WHERE LEN(c.new_value) > 0 OR LEN(c.old_value) > 0;

    -- INSERT: the row is in inserted but not in deleted -- the first Save Draft for an enrollment.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT
        COALESCE(i.last_modified_by, i.submitted_by), N'INSERT', N'grades', i.grade_id,
        NULL,
        CONCAT(
            CASE WHEN i.coursework_mark IS NOT NULL THEN CONCAT(N'coursework_mark=', CONVERT(NVARCHAR(20), i.coursework_mark), N'; ') ELSE N'' END,
            CASE WHEN i.midterm_mark IS NOT NULL THEN CONCAT(N'midterm_mark=', CONVERT(NVARCHAR(20), i.midterm_mark), N'; ') ELSE N'' END,
            CASE WHEN i.lab_mark IS NOT NULL THEN CONCAT(N'lab_mark=', CONVERT(NVARCHAR(20), i.lab_mark), N'; ') ELSE N'' END,
            CASE WHEN i.final_mark IS NOT NULL THEN CONCAT(N'final_mark=', CONVERT(NVARCHAR(20), i.final_mark), N'; ') ELSE N'' END,
            N'submitted=', CONVERT(NVARCHAR(2), i.is_submitted)),
        CONCAT(N'Grade created for enrollment ', i.enrollment_id)
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.grade_id = i.grade_id);

    -- DELETE: the row is in deleted but not in inserted.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT
        COALESCE(d.last_modified_by, d.submitted_by), N'DELETE', N'grades', d.grade_id,
        CONCAT(N'total=', CONVERT(NVARCHAR(20), d.total_mark), N'; letter=', d.letter_grade,
               N'; points=', CONVERT(NVARCHAR(20), d.grade_points), N'; submitted=', CONVERT(NVARCHAR(2), d.is_submitted)),
        NULL,
        CONCAT(N'Grade deleted for enrollment ', d.enrollment_id)
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.grade_id = d.grade_id);
END;
GO
