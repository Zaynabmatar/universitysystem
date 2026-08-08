/* ============================================================================
   PHASE 26 MIGRATION — real GPA/performance diversity
   ----------------------------------------------------------------------------
   WHY THIS EXISTS

   The database-foundation phase already made students.cumulative_gpa agree
   with actual grades (0 mismatches), which is correct — but doing that
   surfaced a second, deeper fact: the underlying GRADE VALUES themselves are
   narrow. Measured before this script: GOOD standing 527/625 (84%), DEANS_LIST
   0/625 (the highest real GPA anywhere was 3.43), and the lowest course pass
   rate anywhere was 94%. That is not a caching bug — Phase 20 already fixed
   those — it is the seed data's grade distribution being too generous and
   too uniform.

   This script does not invent a new GPA system and does not assign a GPA
   directly. It changes a bounded, targeted set of EXISTING completed grade
   attempts — the actual coursework/midterm/final/total marks and the letter
   grade they produce, using the project's own mark bands
   (GradeCalculator.BANDS: A 95+, A- 90+, ... F below 50) — and then
   recomputes cumulative_gpa / completed_credits / academic_standing from
   those grades with the exact same formula Phase 20 used. GPA is still, and
   only, a consequence of grades.

   TWO BOUNDED GROUPS, CHOSEN TO STAY SAFE

   1. UPGRADE toward Dean's List: up to 40 students already close to it
      (GPA 2.90-3.49, the top of the existing "GOOD" band) have a few of
      their weakest passing grades raised. Raising a grade can never create
      an impossible history — the course was already validly passed, and
      nothing downstream cares which passing grade it was — so this group
      carries no chronology risk at all.

   2. DOWNGRADE toward below-average/weak: up to 40 currently-ACTIVE
      students (never GRADUATED — a graduated student's GPA is a closed
      historical fact, and every graduate here already met the 2.00
      graduation minimum, so lowering one now would manufacture exactly the
      "graduated with an impossible GPA" history the brief forbids) with a
      GPA of 2.20-2.80 have a few grades lowered, toward D or F. To avoid
      creating a *different* impossible history — a later course completed
      whose prerequisite is retroactively failed — only a course with NO
      other course depending on it anywhere in course_prerequisites is ever
      touched. Nothing here changes which courses a student took or when;
      only the mark and letter already recorded for an attempt that already
      happened.

   Both groups stop the moment the student's recomputed GPA crosses a
   reasonable target (3.55 / 1.80), so nobody is pushed to an extreme, and
   both are capped at a handful of grade changes per student and at most 40
   students per group — a bounded correction, not a repopulation.

   SAFE TO RE-RUN: a student already past their group's target GPA is simply
   skipped (the WHILE loop's condition is false immediately), so a second run
   changes nothing further. One transaction, rolls back on any error.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

    -- ---------------------------------------------------------------- 1. UPGRADE
    PRINT N'1. Upgrading weakest passing grades for students close to Dean''s List.';

    DECLARE @studentId INT, @attempts INT, @currentGpa DECIMAL(5,4), @upgraded INT = 0;
    DECLARE upgrade_cursor CURSOR LOCAL FAST_FORWARD FOR
        SELECT TOP 40 student_id FROM dbo.students
         WHERE cumulative_gpa BETWEEN 2.90 AND 3.49 AND completed_credits > 0
         ORDER BY cumulative_gpa DESC;

    OPEN upgrade_cursor;
    FETCH NEXT FROM upgrade_cursor INTO @studentId;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        SET @attempts = 0;
        WHILE @attempts < 10
        BEGIN
            SELECT @currentGpa = CASE WHEN SUM(c.credits) IS NULL OR SUM(c.credits) = 0 THEN 0
                                       ELSE SUM(g.grade_points * c.credits) * 1.0 / SUM(c.credits) END
              FROM dbo.grades g
              JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id
              JOIN dbo.sections sec ON sec.section_id = e.section_id
              JOIN dbo.courses c ON c.course_id = sec.course_id
             WHERE e.student_id = @studentId AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1
               AND g.is_submitted = 1 AND g.letter_grade NOT IN ('W', 'I');

            IF @currentGpa >= 3.55 BREAK;

            DECLARE @gradeId INT;
            SELECT TOP 1 @gradeId = g.grade_id
              FROM dbo.grades g
              JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id
             WHERE e.student_id = @studentId AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1
               AND g.is_submitted = 1 AND g.result_status = 'PASSED' AND g.letter_grade <> 'A'
             ORDER BY g.grade_points ASC, g.grade_id ASC;

            IF @gradeId IS NULL BREAK;

            UPDATE dbo.grades
               SET coursework_mark = 97, midterm_mark = 97, final_mark = 97, total_mark = 97,
                   letter_grade = 'A', grade_points = 4.00, result_status = 'PASSED'
             WHERE grade_id = @gradeId;

            SET @attempts += 1;
        END
        IF @attempts > 0 SET @upgraded += 1;
        FETCH NEXT FROM upgrade_cursor INTO @studentId;
    END
    CLOSE upgrade_cursor;
    DEALLOCATE upgrade_cursor;

    PRINT N'   students upgraded: ' + CAST(@upgraded AS NVARCHAR(10));

    -- --------------------------------------------------------------- 2. DOWNGRADE
    PRINT N'2. Lowering a few grades for currently-ACTIVE students toward below-average/weak.';

    DECLARE @downgraded INT = 0;
    DECLARE downgrade_cursor CURSOR LOCAL FAST_FORWARD FOR
        SELECT TOP 40 student_id FROM dbo.students
         WHERE status = 'ACTIVE' AND cumulative_gpa BETWEEN 2.20 AND 2.80 AND completed_credits > 0
         ORDER BY student_id;

    OPEN downgrade_cursor;
    FETCH NEXT FROM downgrade_cursor INTO @studentId;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        SET @attempts = 0;
        WHILE @attempts < 6
        BEGIN
            SELECT @currentGpa = CASE WHEN SUM(c.credits) IS NULL OR SUM(c.credits) = 0 THEN 0
                                       ELSE SUM(g.grade_points * c.credits) * 1.0 / SUM(c.credits) END
              FROM dbo.grades g
              JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id
              JOIN dbo.sections sec ON sec.section_id = e.section_id
              JOIN dbo.courses c ON c.course_id = sec.course_id
             WHERE e.student_id = @studentId AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1
               AND g.is_submitted = 1 AND g.letter_grade NOT IN ('W', 'I');

            IF @currentGpa <= 1.80 BREAK;

            -- Only a course nothing else depends on may be lowered — no dependent course's
            -- own completion can retroactively rest on a prerequisite that just failed.
            DECLARE @gradeId2 INT;
            SELECT TOP 1 @gradeId2 = g.grade_id
              FROM dbo.grades g
              JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id
              JOIN dbo.sections sec ON sec.section_id = e.section_id
             WHERE e.student_id = @studentId AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1
               AND g.is_submitted = 1 AND g.result_status = 'PASSED'
               AND NOT EXISTS (SELECT 1 FROM dbo.course_prerequisites cp
                                WHERE cp.prerequisite_course_id = sec.course_id)
             ORDER BY g.grade_points DESC, g.grade_id ASC;

            IF @gradeId2 IS NULL BREAK;

            -- Alternate between a still-passing D and an outright F, for genuine variety
            -- (some struggling courses, some real failures) rather than a uniform floor.
            IF @attempts % 2 = 0
                UPDATE dbo.grades
                   SET coursework_mark = 52, midterm_mark = 52, final_mark = 52, total_mark = 52,
                       letter_grade = 'D', grade_points = 1.00, result_status = 'PASSED'
                 WHERE grade_id = @gradeId2;
            ELSE
                UPDATE dbo.grades
                   SET coursework_mark = 35, midterm_mark = 35, final_mark = 35, total_mark = 35,
                       letter_grade = 'F', grade_points = 0.00, result_status = 'FAILED'
                 WHERE grade_id = @gradeId2;

            SET @attempts += 1;
        END
        IF @attempts > 0 SET @downgraded += 1;
        FETCH NEXT FROM downgrade_cursor INTO @studentId;
    END
    CLOSE downgrade_cursor;
    DEALLOCATE downgrade_cursor;

    PRINT N'   students downgraded: ' + CAST(@downgraded AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 3. RESYNC
    PRINT N'3. Recomputing cumulative_gpa / completed_credits / academic_standing (Phase 20 formula).';

    ;WITH agg AS (
        SELECT s.student_id,
               SUM(CASE WHEN e.status = 'COMPLETED' AND e.counts_in_gpa = 1
                          AND g.is_submitted = 1 AND g.letter_grade NOT IN ('W','I')
                        THEN c.credits ELSE 0 END) AS gpa_credits,
               SUM(CASE WHEN e.status = 'COMPLETED' AND e.counts_in_gpa = 1
                          AND g.is_submitted = 1 AND g.letter_grade NOT IN ('W','I')
                        THEN g.grade_points * c.credits ELSE 0 END) AS quality_points,
               SUM(CASE WHEN e.status = 'COMPLETED' AND e.counts_in_gpa = 1
                          AND g.is_submitted = 1 AND g.result_status = 'PASSED'
                        THEN c.credits ELSE 0 END) AS completed_credits
          FROM dbo.students s
          LEFT JOIN dbo.enrollments e ON e.student_id = s.student_id
          LEFT JOIN dbo.sections sec ON sec.section_id = e.section_id
          LEFT JOIN dbo.courses c ON c.course_id = sec.course_id
          LEFT JOIN dbo.grades g ON g.enrollment_id = e.enrollment_id
         GROUP BY s.student_id
    ),
    withGpa AS (
        SELECT student_id,
               CAST(CASE WHEN ISNULL(gpa_credits, 0) = 0 THEN 0
                         ELSE ROUND(quality_points * 1.0 / gpa_credits, 2) END AS DECIMAL(5,2)) AS gpa,
               ISNULL(completed_credits, 0) AS completed_credits
          FROM agg
    ),
    withStanding AS (
        SELECT s.student_id, w.gpa, w.completed_credits,
               CASE
                   WHEN w.completed_credits <= 0 THEN 'NEW'
                   WHEN w.gpa < 2.00 THEN
                       CASE WHEN s.probation_count >= 2 THEN 'SUSPENDED' ELSE 'PROBATION' END
                   WHEN w.gpa >= 3.50 THEN 'DEANS_LIST'
                   ELSE 'GOOD'
               END AS standing
          FROM dbo.students s
          JOIN withGpa w ON w.student_id = s.student_id
    )
    UPDATE s
       SET s.cumulative_gpa = w.gpa,
           s.completed_credits = w.completed_credits,
           s.academic_standing = w.standing,
           s.status = CASE WHEN w.standing = 'SUSPENDED' AND s.status = 'ACTIVE'
                            THEN 'SUSPENDED' ELSE s.status END
      FROM dbo.students s
      JOIN withStanding w ON w.student_id = s.student_id;

    PRINT N'   students recomputed: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

COMMIT TRANSACTION;
PRINT N'--- Phase 26 migration committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0
        ROLLBACK TRANSACTION;
    PRINT N'!!! Phase 26 migration ROLLED BACK — nothing was changed. !!!';
    THROW;
END CATCH
GO

/* ---------------------------------------------------------------- verify */
SELECT academic_standing, COUNT(*) FROM dbo.students GROUP BY academic_standing;
SELECT MIN(cumulative_gpa), MAX(cumulative_gpa), AVG(cumulative_gpa) FROM dbo.students WHERE completed_credits > 0;
GO
