/* ============================================================================
   PHASE 19 MIGRATION — semester chronology: fix a corrupt date, block going
                        backward at the database level
   ----------------------------------------------------------------------------
   WHY THIS EXISTS

   1. Fall 2023 (semester_id 16) was stored with end_date = 2026-09-09 — three
      years after the semester it belongs to actually ran, and after every
      later semester in the table. That is a data-entry defect, not a design
      choice: every other Fall semester in the table ends in mid-January of
      the following year. Corrected to 2024-01-16, in line with the Fall 2022
      (-> 2023-01-17) and Fall 2024 (-> 2025-01-14) rows either side of it.
      Guarded so a second run is a no-op once the date is already fixed.

   2. dbo.semesters already has a filtered unique index
      (UX_semesters_one_current) guaranteeing at most one row has
      is_current = 1 — so two semesters can never both be "current" at once.
      What nothing enforced is chronology: SemesterDAO.makeCurrent() only
      cleared the old flag and set the new one, with no check that the newly
      chosen semester is not one that has already run. An admin could select
      Fall 2023 from the dropdown and make it current again even after the
      university had moved on to Fall 2026. Hiding the "Make Current" action
      in the UI for a past semester (done in the same commit as this script)
      stops the ordinary path, but the brief is explicit that the database
      itself must refuse it too, so this adds a trigger.

   HOW THE TRIGGER WORKS
     SemesterDAO.makeCurrent() is changed (same commit) from two UPDATE
     statements to one: it flips every semester's is_current in a single
     statement, using a CASE, so that statement's `inserted`/`deleted`
     pseudo-tables contain BOTH the row losing the flag and the row gaining
     it. The trigger then refuses the whole statement if the row losing the
     flag has a LATER start_date than the row gaining it — i.e. chronology may
     only move forward. Setting the very first current semester (nothing was
     current before) or re-selecting the semester that is already current are
     both no-ops for this check and are allowed.

   SAFE TO RUN ONCE, AND SAFE TO RE-RUN: the date fix is guarded by comparing
   the current value, and the trigger is dropped and recreated so a second run
   just re-applies the same definition. Everything runs in one transaction
   that rolls back on any error.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

    -- --------------------------------------------------------------- 1.
    IF EXISTS (SELECT 1 FROM dbo.semesters
                WHERE semester_id = 16 AND semester_name = N'Fall 2023'
                  AND end_date <> '2024-01-16')
    BEGIN
        PRINT N'1. Correcting the corrupt end_date on Fall 2023 (semester_id 16).';
        UPDATE dbo.semesters
           SET end_date = '2024-01-16'
         WHERE semester_id = 16 AND semester_name = N'Fall 2023';
    END
    ELSE
        PRINT N'1. Fall 2023''s end_date is already correct - nothing to do.';

COMMIT TRANSACTION;
PRINT N'--- Phase 19 step 1 committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0
        ROLLBACK TRANSACTION;
    PRINT N'!!! Phase 19 migration ROLLED BACK — nothing was changed. !!!';
    THROW;
END CATCH
GO

-- ------------------------------------------------------------------- 2.
-- CREATE/ALTER TRIGGER must be the only statement in its batch.
IF OBJECT_ID('dbo.trg_semesters_block_backward_activation', 'TR') IS NOT NULL
BEGIN
    PRINT N'2. Dropping the existing trg_semesters_block_backward_activation to re-create it.';
    DROP TRIGGER dbo.trg_semesters_block_backward_activation;
END
GO

PRINT N'2. Creating trg_semesters_block_backward_activation.';
GO

CREATE TRIGGER dbo.trg_semesters_block_backward_activation
ON dbo.semesters
AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;

    -- Only relevant when this statement touched is_current at all; an
    -- ordinary edit to a semester's own dates/name never fires past here.
    IF NOT UPDATE(is_current)
        RETURN;

    DECLARE @newId INT, @newStart DATE;
    SELECT @newId = semester_id, @newStart = start_date
      FROM inserted
     WHERE is_current = 1;

    -- This statement only cleared flags (e.g. a delete-time cleanup) and did
    -- not activate anything new — nothing to check.
    IF @newId IS NULL
        RETURN;

    -- Refuse only when some OTHER semester that held the flag before this
    -- statement ran chronologically AFTER the one about to receive it. Re-
    -- activating the semester that is already current, or setting the very
    -- first current semester, both pass this check.
    IF EXISTS (
        SELECT 1
          FROM deleted d
         WHERE d.is_current = 1
           AND d.semester_id <> @newId
           AND d.start_date > @newStart
    )
    BEGIN
        ROLLBACK TRANSACTION;
        THROW 51001, N'Cannot make an earlier semester current. The current semester can only move forward in time.', 1;
    END
END;
GO

/* ---------------------------------------------------------------- verify */
SELECT semester_id, semester_name, start_date, end_date, is_current
  FROM dbo.semesters ORDER BY start_date;
SELECT COUNT(*) AS currently_current FROM dbo.semesters WHERE is_current = 1;
SELECT name FROM sys.triggers WHERE name = 'trg_semesters_block_backward_activation';
GO
