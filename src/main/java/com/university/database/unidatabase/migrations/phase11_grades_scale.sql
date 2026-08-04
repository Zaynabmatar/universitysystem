/* ============================================================================
   PHASE 11 MIGRATION — align dbo.grades with project_details.md Section 5.
   Run once against universitymanagementDB. Each step is its own batch (GO)
   so a later ALTER TABLE never binds against stale, pre-rename metadata.
   ============================================================================ */
SET NOCOUNT ON;
PRINT N'--- Phase 11 migration: dbo.grades scale ---';
GO

/* ---- 1. drop the constraints that block the rename -------------------- */
ALTER TABLE dbo.grades DROP CONSTRAINT CK_grades_partial;
GO
ALTER TABLE dbo.grades DROP CONSTRAINT CK_grades_lab;
GO
ALTER TABLE dbo.grades DROP CONSTRAINT CK_grades_letter;
GO

/* ---- 2. rename the mark columns ------------------------------------- */
EXEC sp_rename 'dbo.grades.partial_mark', 'coursework_mark', 'COLUMN';
GO
EXEC sp_rename 'dbo.grades.lab_mark', 'midterm_mark', 'COLUMN';
GO

ALTER TABLE dbo.grades ADD CONSTRAINT CK_grades_coursework CHECK (coursework_mark BETWEEN 0 AND 100);
GO
ALTER TABLE dbo.grades ADD CONSTRAINT CK_grades_midterm CHECK (midterm_mark BETWEEN 0 AND 100);
GO
ALTER TABLE dbo.grades ADD CONSTRAINT CK_grades_letter CHECK (letter_grade IN
    (N'A', N'A-', N'B+', N'B', N'B-', N'C+', N'C', N'C-', N'D+', N'D', N'F', N'W', N'I'));
GO

/* ---- 3. recompute the existing seed rows under the new formula ------- */
UPDATE dbo.grades
SET total_mark = CAST(coursework_mark * 0.30 + midterm_mark * 0.30 + final_mark * 0.40 AS DECIMAL(5,2))
WHERE coursework_mark IS NOT NULL AND midterm_mark IS NOT NULL AND final_mark IS NOT NULL;
GO

UPDATE dbo.grades
SET letter_grade =
        CASE WHEN total_mark >= 95 THEN N'A'
             WHEN total_mark >= 90 THEN N'A-'
             WHEN total_mark >= 85 THEN N'B+'
             WHEN total_mark >= 80 THEN N'B'
             WHEN total_mark >= 75 THEN N'B-'
             WHEN total_mark >= 70 THEN N'C+'
             WHEN total_mark >= 65 THEN N'C'
             WHEN total_mark >= 60 THEN N'C-'
             WHEN total_mark >= 55 THEN N'D+'
             WHEN total_mark >= 50 THEN N'D'
             ELSE N'F' END,
    grade_points =
        CASE WHEN total_mark >= 95 THEN 4.00
             WHEN total_mark >= 90 THEN 3.70
             WHEN total_mark >= 85 THEN 3.30
             WHEN total_mark >= 80 THEN 3.00
             WHEN total_mark >= 75 THEN 2.70
             WHEN total_mark >= 70 THEN 2.30
             WHEN total_mark >= 65 THEN 2.00
             WHEN total_mark >= 60 THEN 1.70
             WHEN total_mark >= 55 THEN 1.30
             WHEN total_mark >= 50 THEN 1.00
             ELSE 0.00 END,
    result_status =
        CASE WHEN total_mark >= 50 THEN N'PASSED' ELSE N'FAILED' END
WHERE total_mark IS NOT NULL;
GO

/* ---- 4. trg_Grade_Audit — Section 4.16, "filled automatically by
           database triggers, not by Java" -------------------------------- */
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

    -- UPDATE: a row exists in both inserted and deleted.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT
        COALESCE(i.last_modified_by, i.submitted_by),
        N'UPDATE', N'grades', i.grade_id,
        CONCAT(N'total=', CONVERT(NVARCHAR(20), d.total_mark), N'; letter=', d.letter_grade,
               N'; points=', CONVERT(NVARCHAR(20), d.grade_points), N'; submitted=', CONVERT(NVARCHAR(2), d.is_submitted)),
        CONCAT(N'total=', CONVERT(NVARCHAR(20), i.total_mark), N'; letter=', i.letter_grade,
               N'; points=', CONVERT(NVARCHAR(20), i.grade_points), N'; submitted=', CONVERT(NVARCHAR(2), i.is_submitted)),
        CONCAT(N'Grade changed for enrollment ', i.enrollment_id)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.grade_id = i.grade_id;

    -- INSERT: the row is in inserted but not in deleted.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT
        COALESCE(i.last_modified_by, i.submitted_by),
        N'INSERT', N'grades', i.grade_id,
        NULL,
        CONCAT(N'total=', CONVERT(NVARCHAR(20), i.total_mark), N'; letter=', i.letter_grade,
               N'; points=', CONVERT(NVARCHAR(20), i.grade_points), N'; submitted=', CONVERT(NVARCHAR(2), i.is_submitted)),
        CONCAT(N'Grade created for enrollment ', i.enrollment_id)
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.grade_id = i.grade_id);

    -- DELETE: the row is in deleted but not in inserted.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT
        COALESCE(d.last_modified_by, d.submitted_by),
        N'DELETE', N'grades', d.grade_id,
        CONCAT(N'total=', CONVERT(NVARCHAR(20), d.total_mark), N'; letter=', d.letter_grade,
               N'; points=', CONVERT(NVARCHAR(20), d.grade_points), N'; submitted=', CONVERT(NVARCHAR(2), d.is_submitted)),
        NULL,
        CONCAT(N'Grade deleted for enrollment ', d.enrollment_id)
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.grade_id = d.grade_id);
END;
GO

PRINT N'--- Phase 11 migration complete ---';
GO
