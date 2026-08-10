/* ==========================================================================
   0013 - remove the bad Spring 2027 row that overlaps Fall 2026, and add a
   trigger so no semester's term dates can ever overlap another's again.
   --------------------------------------------------------------------------
   1. Spring 2027 (semester_id 3) was seeded with start_date = 2026-10-07,
      one day BEFORE Fall 2026 (semester_id 19, the current semester) ends on
      2026-10-08. That is bad data, not a real semester: it has zero sections
      and zero invoices, so it is safe to delete outright rather than just
      correcting its dates. Guarded so re-running this script after the row
      is already gone is a no-op.

   2. Nothing before this stopped ANY two semesters (not just the current
      one) from being inserted or edited with overlapping term dates -
      CK_semesters_dates only checks end_date > start_date within a single
      row. This adds trg_semesters_no_date_overlap, which refuses an
      INSERT/UPDATE when the affected row's [start_date, end_date] interval
      overlaps any OTHER semester's, using the standard interval test
      (a.start <= b.end AND b.start <= a.end). Touching the boundary counts
      as an overlap, so a new semester's start_date must be strictly AFTER
      the previous semester's end_date, matching SemesterService's
      create()/update() validation (same commit) and the "no overlapping
      term dates" requirement.

   SAFE TO RE-RUN: the delete is guarded by existence + dependency checks,
   and the trigger is dropped and recreated.
   ========================================================================== */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

-- 1. Remove the bad Spring 2027 row, but only the specific corrupt one, and
--    only if nothing has come to depend on it since it was seeded.
IF EXISTS (SELECT 1 FROM dbo.semesters
            WHERE semester_name = N'Spring 2027' AND start_date = '2026-10-07')
BEGIN
    IF EXISTS (SELECT 1 FROM dbo.sections se
                 JOIN dbo.semesters sem ON sem.semester_id = se.semester_id
                WHERE sem.semester_name = N'Spring 2027' AND sem.start_date = '2026-10-07')
       OR EXISTS (SELECT 1 FROM dbo.student_invoices si
                    JOIN dbo.semesters sem ON sem.semester_id = si.semester_id
                   WHERE sem.semester_name = N'Spring 2027' AND sem.start_date = '2026-10-07')
    BEGIN
        THROW 51004, N'The bad Spring 2027 row now has sections or invoices attached to it - not deleting it automatically.', 1;
    END

    DELETE FROM dbo.semesters WHERE semester_name = N'Spring 2027' AND start_date = '2026-10-07';
    PRINT N'1. Deleted the bad Spring 2027 row that overlapped Fall 2026.';
END
ELSE
    PRINT N'1. The bad Spring 2027 row is already gone - nothing to do.';
GO

-- 2. Make sure Fall 2026 is the only current semester (belt and suspenders
--    alongside 0010_semester_single_open_lock.sql).
UPDATE dbo.semesters SET is_current = 0 WHERE semester_name <> N'Fall 2026' AND is_current = 1;
UPDATE dbo.semesters SET is_current = 1 WHERE semester_name = N'Fall 2026' AND is_current = 0;
GO

-- 3. CREATE/ALTER TRIGGER must be the only statement in its batch.
IF OBJECT_ID('dbo.trg_semesters_no_date_overlap', 'TR') IS NOT NULL
BEGIN
    PRINT N'3. Dropping the existing trg_semesters_no_date_overlap to re-create it.';
    DROP TRIGGER dbo.trg_semesters_no_date_overlap;
END
GO

PRINT N'3. Creating trg_semesters_no_date_overlap.';
GO

CREATE TRIGGER dbo.trg_semesters_no_date_overlap
ON dbo.semesters
AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;

    -- An UPDATE that only touches is_current (no start_date/end_date change)
    -- can never create an overlap - trg_semesters_enforce_single_open already
    -- owns that column. UPDATE() is true for every inserted column on a plain
    -- INSERT too, so this never skips a fresh row.
    IF NOT (UPDATE(start_date) OR UPDATE(end_date))
        RETURN;

    IF EXISTS (
        SELECT 1
          FROM inserted i
          JOIN dbo.semesters other
            ON other.semester_id <> i.semester_id
           AND i.start_date <= other.end_date
           AND other.start_date <= i.end_date
    )
    BEGIN
        ROLLBACK TRANSACTION;
        THROW 51005, N'That semester''s term dates overlap another semester''s. A new semester must start strictly after the previous one ends.', 1;
    END
END;
GO

/* ---------------------------------------------------------------- verify */
SELECT semester_id, semester_name, start_date, end_date, is_current
  FROM dbo.semesters ORDER BY start_date;
SELECT name FROM sys.triggers WHERE name = 'trg_semesters_no_date_overlap';
GO
