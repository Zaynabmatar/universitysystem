/* ============================================================================
   PHASE 22 MIGRATION — one or two genuinely FULL sections in the now-current
                        semester, so "attempt a full section" can be tested
   ----------------------------------------------------------------------------
   WHY THIS EXISTS

   After Phase 21 prepared and advanced to Fall 2026, its 159 sections range
   from 13% to 95% occupied but none sit exactly at capacity — there is
   nothing left in the now-current semester to demonstrate rule R5 (seat
   unavailable) against. This tops up the one or two sections that were
   already one seat away from full (found by querying, not guessed) with one
   more real ENROLLED row each, from a student who is not already in that
   course this semester, still fits the 18-credit ceiling with it added, and
   has no timetable clash with it — the same eligibility RegistrationService
   itself would check.

   SAFE TO RE-RUN: each INSERT is guarded by NOT EXISTS on
   (student, section) and by "this section still has a free seat," so a
   second run tops up nothing that is already full.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

    DECLARE @sectionId INT, @courseId INT, @credits INT;
    DECLARE nearfull_cursor CURSOR LOCAL FAST_FORWARD FOR
        SELECT sec.section_id, sec.course_id, c.credits
          FROM dbo.sections sec
          JOIN dbo.courses c ON c.course_id = sec.course_id
         WHERE sec.semester_id = 19 AND sec.status = 'OPEN'
           AND sec.capacity - sec.enrolled_count = 1;

    OPEN nearfull_cursor;
    FETCH NEXT FROM nearfull_cursor INTO @sectionId, @courseId, @credits;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        DECLARE @studentId INT;
        SELECT TOP 1 @studentId = st.student_id
          FROM dbo.students st
         WHERE st.status = 'ACTIVE'
           AND NOT EXISTS (
                 SELECT 1 FROM dbo.enrollments e2
                 JOIN dbo.sections sec2 ON sec2.section_id = e2.section_id
                WHERE e2.student_id = st.student_id AND sec2.course_id = @courseId
                  AND sec2.semester_id = 19 AND e2.status = 'ENROLLED')
           AND @credits + ISNULL((
                 SELECT SUM(c3.credits) FROM dbo.enrollments e3
                 JOIN dbo.sections sec3 ON sec3.section_id = e3.section_id
                 JOIN dbo.courses c3 ON c3.course_id = sec3.course_id
                WHERE e3.student_id = st.student_id AND sec3.semester_id = 19
                  AND e3.status = 'ENROLLED'), 0) <= 18
           AND NOT EXISTS (
                 SELECT 1 FROM dbo.enrollments e4
                 JOIN dbo.sections sec4 ON sec4.section_id = e4.section_id
                 JOIN dbo.section_schedules ss4 ON ss4.section_id = sec4.section_id
                 JOIN dbo.section_schedules ssNew ON ssNew.section_id = @sectionId
                WHERE e4.student_id = st.student_id AND sec4.semester_id = 19 AND e4.status = 'ENROLLED'
                  AND ss4.day_of_week = ssNew.day_of_week
                  AND ss4.start_time < ssNew.end_time AND ssNew.start_time < ss4.end_time)
         ORDER BY st.student_id;

        IF @studentId IS NOT NULL
        BEGIN
            INSERT INTO dbo.enrollments (student_id, section_id, enrollment_date, status, is_repeat, counts_in_gpa)
            VALUES (@studentId, @sectionId, SYSDATETIME(), 'ENROLLED', 0, 1);

            UPDATE dbo.sections SET enrolled_count = enrolled_count + 1 WHERE section_id = @sectionId;

            PRINT N'   filled the last seat in section ' + CAST(@sectionId AS NVARCHAR(10))
                + N' with student ' + CAST(@studentId AS NVARCHAR(10));
        END
        ELSE
            PRINT N'   no eligible student found to fill section ' + CAST(@sectionId AS NVARCHAR(10)) + N' - left as is.';

        FETCH NEXT FROM nearfull_cursor INTO @sectionId, @courseId, @credits;
    END
    CLOSE nearfull_cursor;
    DEALLOCATE nearfull_cursor;

COMMIT TRANSACTION;
PRINT N'--- Phase 22 migration committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0
        ROLLBACK TRANSACTION;
    PRINT N'!!! Phase 22 migration ROLLED BACK — nothing was changed. !!!';
    THROW;
END CATCH
GO

/* ---------------------------------------------------------------- verify */
SELECT COUNT(*) AS full_sections_now
  FROM dbo.sections WHERE semester_id = 19 AND status = 'OPEN' AND enrolled_count >= capacity;
GO
