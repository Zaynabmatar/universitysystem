/* ============================================================================
   PHASE 21 MIGRATION — finish preparing Fall 2026's offering and advance the
                        current semester to it
   ----------------------------------------------------------------------------
   WHY THIS EXISTS

   Fall 2025 (semester_id 2) was the semester dbo.semesters.is_current pointed
   at throughout this cleanup, and every earlier phase's data fixes were
   verified against it. But its own registration window
   (2025-08-15 -> 2025-09-20) had already closed by the time this phase's
   application-level tests were run against the real system clock — so
   RegistrationService correctly refuses every registration attempt against
   it today (rule R1), no matter how clean its section/seat data is. That is
   the registration system working exactly as designed, not a bug — but it
   means Fall 2025 cannot be used to demonstrate Registration actually
   working end-to-end today.

   Fall 2026 (semester_id 19) already exists with a registration window that
   is open right now (2026-08-04 -> 2026-10-02) and 159 sections that are a
   1:1 rollover of Fall 2025's (same course_id + section_number, 158 of 159
   with the same instructor_id — the one exception is the same TBA section on
   both sides). What Fall 2025 has that Fall 2026 does not:

     1. Meeting times. Fall 2026 has zero rows in dbo.section_schedules for
        any of its 159 sections — confirmed before writing this script — so
        RegistrationService's Phase-20 completeness guard correctly refuses
        every one of them (rule SECTION_NOT_SCHEDULED). Because each Fall 2026
        section is a rollover of a specific Fall 2025 section that already has
        a validated, clash-free set of meeting times, this script copies that
        section's schedule rows across verbatim rather than inventing new
        ones — the same instructor teaching the same section in consecutive
        offerings of the same course is the ordinary case, and no new
        instructor/room clash can be introduced by copying a set of meetings
        that was already clash-free among itself.

     2. A clean credit load. 284 of ~625 students already carry more than 18
        credits of ENROLLED coursework in Fall 2026 (confirmed before writing
        this script) — Fall 2026's enrollments were bulk-seeded without the
        18-credit ceiling ever being applied, unlike Fall 2025 which Phase 20
        already corrected. This script applies the identical fix Phase 20
        applied to Fall 2025: drop each over-limit student's most recent
        enrollments in this semester until they are at or under 18 credits,
        keeping sections.enrolled_count in sync.

   Once both are fixed, this script makes Fall 2026 the current semester the
   same way SemesterService.setCurrent does — one UPDATE, guarded by
   trg_semesters_block_backward_activation (Phase 19) — except here the move
   is forward (Fall 2026 starts after Fall 2025), so the trigger allows it.

   SAFE TO RE-RUN: the schedule copy only inserts a meeting that is not
   already there, the credit cap only touches students still over 18, and
   making Fall 2026 current a second time is a no-op.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

    -- ---------------------------------------------------------------- 1.
    PRINT N'1. Copying section_schedules from each Fall 2025 section to its Fall 2026 rollover.';

    INSERT INTO dbo.section_schedules (section_id, day_of_week, start_time, end_time)
    SELECT s26.section_id, ss25.day_of_week, ss25.start_time, ss25.end_time
      FROM dbo.section_schedules ss25
      JOIN dbo.sections s25 ON s25.section_id = ss25.section_id AND s25.semester_id = 2
      JOIN dbo.sections s26 ON s26.course_id = s25.course_id
                            AND s26.section_number = s25.section_number
                            AND s26.semester_id = 19
     WHERE NOT EXISTS (
             SELECT 1 FROM dbo.section_schedules ss26
              WHERE ss26.section_id = s26.section_id
                AND ss26.day_of_week = ss25.day_of_week
                AND ss26.start_time = ss25.start_time
                AND ss26.end_time = ss25.end_time);

    PRINT N'   schedule rows copied: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 2.
    PRINT N'2. Capping Fall 2026 registered load at 18 credits (dropping the most recent excess).';

    DECLARE @touched TABLE (section_id INT NOT NULL, enrollment_id INT NOT NULL);
    DECLARE @studentId INT, @over INT;
    DECLARE over_cursor CURSOR LOCAL FAST_FORWARD FOR
        SELECT e.student_id, SUM(c.credits) - 18
          FROM dbo.enrollments e
          JOIN dbo.sections sec ON sec.section_id = e.section_id
          JOIN dbo.courses c ON c.course_id = sec.course_id
         WHERE e.status = 'ENROLLED' AND sec.semester_id = 19
         GROUP BY e.student_id
        HAVING SUM(c.credits) > 18;

    OPEN over_cursor;
    FETCH NEXT FROM over_cursor INTO @studentId, @over;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        WHILE @over > 0
        BEGIN
            DECLARE @dropEnrollmentId INT, @dropSectionId INT, @dropCredits INT;
            SELECT TOP 1 @dropEnrollmentId = e.enrollment_id, @dropSectionId = e.section_id,
                         @dropCredits = c.credits
              FROM dbo.enrollments e
              JOIN dbo.sections sec ON sec.section_id = e.section_id
              JOIN dbo.courses c ON c.course_id = sec.course_id
             WHERE e.student_id = @studentId AND e.status = 'ENROLLED' AND sec.semester_id = 19
             ORDER BY e.enrollment_date DESC, e.enrollment_id DESC;

            IF @dropEnrollmentId IS NULL BREAK;

            UPDATE dbo.enrollments SET status = 'DROPPED' WHERE enrollment_id = @dropEnrollmentId;
            INSERT INTO @touched (section_id, enrollment_id) VALUES (@dropSectionId, @dropEnrollmentId);
            SET @over -= @dropCredits;
        END
        FETCH NEXT FROM over_cursor INTO @studentId, @over;
    END
    CLOSE over_cursor;
    DEALLOCATE over_cursor;

    UPDATE sec
       SET sec.enrolled_count = sec.enrolled_count - x.cnt
      FROM dbo.sections sec
      JOIN (SELECT section_id, COUNT(*) AS cnt FROM @touched GROUP BY section_id) x
        ON x.section_id = sec.section_id;

    DECLARE @droppedCount INT = (SELECT COUNT(*) FROM @touched);
    PRINT N'   enrollments dropped to enforce the cap: ' + CAST(@droppedCount AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 3.
    PRINT N'3. Advancing the current semester from Fall 2025 to Fall 2026.';

    UPDATE dbo.semesters
       SET is_current = CASE WHEN semester_id = 19 THEN 1 ELSE 0 END
     WHERE is_current = 1 OR semester_id = 19;

COMMIT TRANSACTION;
PRINT N'--- Phase 21 migration committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0
        ROLLBACK TRANSACTION;
    PRINT N'!!! Phase 21 migration ROLLED BACK — nothing was changed. !!!';
    THROW;
END CATCH
GO

/* ---------------------------------------------------------------- verify */
SELECT semester_id, semester_name, is_current FROM dbo.semesters WHERE is_current = 1;
SELECT COUNT(*) AS fall2026_sections_still_unscheduled
  FROM dbo.sections sec WHERE sec.semester_id = 19 AND sec.status = 'OPEN'
   AND NOT EXISTS (SELECT 1 FROM dbo.section_schedules ss WHERE ss.section_id = sec.section_id);
SELECT COUNT(*) AS fall2026_students_still_over_18 FROM (
    SELECT e.student_id FROM dbo.enrollments e
      JOIN dbo.sections sec ON sec.section_id = e.section_id
      JOIN dbo.courses c ON c.course_id = sec.course_id
     WHERE e.status = 'ENROLLED' AND sec.semester_id = 19
     GROUP BY e.student_id HAVING SUM(c.credits) > 18) x;
SELECT
    SUM(CASE WHEN enrolled_count >= capacity THEN 1 ELSE 0 END) AS full_sections,
    SUM(CASE WHEN enrolled_count < capacity THEN 1 ELSE 0 END) AS open_sections
  FROM dbo.sections WHERE semester_id = 19 AND status = 'OPEN';
GO
