-- Semesters: a different semester may no longer become current directly on top of one that is
-- already current — Admin must explicitly close the open semester first.
--
-- dbo.semesters already had a filtered unique index (UX_semesters_one_current, base schema) so two
-- rows could never both read is_current = 1 at once, and trg_semesters_block_backward_activation
-- (Phase 19) blocked only a BACKWARD swap. Neither stopped a FORWARD swap: SemesterDAO.makeCurrent()
-- could still move the flag straight from one open semester to a later one in a single statement.
-- This migration replaces that trigger with a stricter one that refuses ANY swap between two
-- different semesters — opening a different semester is only allowed once nothing is current
-- (SemesterDAO.closeCurrent() clears the flag with nobody taking it, which this trigger always
-- allows, since UPDATE(is_current) with no row landing on 1 is a pure close).
--
-- SAFE TO RE-RUN: the trigger is dropped and recreated, so a second run just re-applies the same
-- definition.

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

IF OBJECT_ID('dbo.trg_semesters_block_backward_activation', 'TR') IS NOT NULL
BEGIN
    PRINT N'Dropping trg_semesters_block_backward_activation - superseded by trg_semesters_enforce_single_open.';
    DROP TRIGGER dbo.trg_semesters_block_backward_activation;
END
GO

IF OBJECT_ID('dbo.trg_semesters_enforce_single_open', 'TR') IS NOT NULL
BEGIN
    PRINT N'Dropping the existing trg_semesters_enforce_single_open to re-create it.';
    DROP TRIGGER dbo.trg_semesters_enforce_single_open;
END
GO

PRINT N'Creating trg_semesters_enforce_single_open.';
GO

CREATE TRIGGER dbo.trg_semesters_enforce_single_open
ON dbo.semesters
AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;

    -- Only relevant when this statement touched is_current at all; an ordinary edit to a
    -- semester's own dates/name never fires past here.
    IF NOT UPDATE(is_current)
        RETURN;

    DECLARE @newId INT;
    SELECT @newId = semester_id FROM inserted WHERE is_current = 1;

    -- This statement only cleared flags (a close, or a delete-time cleanup) and did not open
    -- anything new — always allowed, whatever was open before.
    IF @newId IS NULL
        RETURN;

    -- Refuse when some OTHER semester already held the flag before this statement. Re-activating
    -- the semester that is already current is a no-op for this check and stays allowed; opening a
    -- semester while nothing was current also passes, since no row in `deleted` matches.
    DECLARE @openName NVARCHAR(50);
    SELECT @openName = d.semester_name
      FROM deleted d
     WHERE d.is_current = 1
       AND d.semester_id <> @newId;

    IF @openName IS NOT NULL
    BEGIN
        ROLLBACK TRANSACTION;
        DECLARE @msg NVARCHAR(200) = @openName + N' is currently open. Close it before opening another semester.';
        THROW 51002, @msg, 1;
    END
END;
GO

-- ---------------------------------------------------------------- data: keep only Fall 2026 open
-- Matches the state SemesterService/the UI already enforce going forward; corrects it directly in
-- case any other row was ever left flagged by data entered before this migration existed.
UPDATE dbo.semesters SET is_current = 0 WHERE semester_name <> N'Fall 2026' AND is_current = 1;
UPDATE dbo.semesters SET is_current = 1 WHERE semester_name = N'Fall 2026' AND is_current = 0;
GO

/* ---------------------------------------------------------------- verify */
SELECT semester_id, semester_name, is_current FROM dbo.semesters WHERE is_current = 1;
SELECT COUNT(*) AS currently_current FROM dbo.semesters WHERE is_current = 1;
SELECT name FROM sys.triggers WHERE name = 'trg_semesters_enforce_single_open';
GO
