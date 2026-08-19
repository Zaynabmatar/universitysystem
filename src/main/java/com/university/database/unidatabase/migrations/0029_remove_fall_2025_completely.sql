/* ============================================================================
   0029 — remove Fall 2025 completely

   WHY
     Explicit product decision: Fall 2025 (dbo.semesters.semester_id = 2) must
     no longer exist anywhere in the system — not in Academic Analytics, not
     in charts, not in any semester list or dropdown, not in notifications,
     not on any other page. Spring 2025, and every 2024/2026 semester, are
     untouched.

     migrations/README.md's stated rule is "no DELETE of real data — migrations
     move the schema forward, they do not rebuild it." This migration is a
     deliberate, explicit exception to that rule, the same kind of documented
     exception 0017_remove_university_news.sql already set precedent for, made
     at the university's explicit request rather than as routine cleanup. A
     full database backup taken shortly before this change already exists
     (database-backup/universitymanagementDB_final_2026-08-19.bak) — restore
     from it if this ever needs to be undone; there is no "undo" for a DELETE
     once it has run.

     Notifications have no semester_id column at all (free text only — see
     0028_remove_stale_fall_2025_notifications.sql's own investigation), so
     0028's "genuine historical Fall 2025 notifications are untouched" stance
     is superseded here for the ~289 seeded "Welcome back for Fall 2025" rows
     specifically: those, unlike the two stray rows 0028 removed, ARE genuine
     history, but the instruction is that Fall 2025 must not appear anywhere,
     including a student's own notification history.

   WHAT THIS DOES, IN ORDER (child tables before the parent they reference,
   so every FK is satisfied — see the FK map in each table's own migration):
     1.  dbo.instructor_evaluations   (dbo.evaluation_answers cascades with it)
     2.  dbo.attendance_records
     3.  dbo.exams
     4.  dbo.grades
     5.  dbo.enrollments              (by the section's semester)
     5b. repeat-policy fix-up — see below
     6.  dbo.financial_transactions   (by invoice or by payment)
     7.  dbo.payments
     8.  dbo.student_invoices         (dbo.invoice_items cascades with it)
     9.  dbo.student_tuition_installments
     10. dbo.calendar_events
     11. dbo.sections                 (dbo.section_schedules cascades with it)
     12. dbo.semesters                (dbo.tuition_rates cascades with it)
     13. dbo.notifications            (free text, no FK — matched by title/message)
     13b. graduation credit backfill — see below
     14. dbo.students cache columns   (cumulative_gpa / completed_credits /
                                        academic_standing), recomputed with the
                                        exact formula phase20_academic_data_
                                        integrity.sql step E already established

   REPEAT-POLICY FIX-UP (step 5b)
     EnrollmentDAO.retireOlderCompletedAttempts sets counts_in_gpa = 0 on every
     OTHER completed attempt at a course the moment a new completed attempt is
     submitted, unconditionally — it does not check which semester is newer,
     it trusts that the one just submitted is the latest. So a student who
     completed a course in Spring 2025 and then retook it in Fall 2025 has
     their Spring 2025 attempt sitting at counts_in_gpa = 0 right now. Deleting
     the Fall 2025 attempt outright would leave that Spring 2025 row stranded
     at counts_in_gpa = 0 forever — a real, passed, untouched Spring 2025 grade
     would silently stop counting toward the student's GPA, which is exactly
     the kind of modification to Spring 2025 data this change must not cause.
     Step 5b restores counts_in_gpa = 1 on the newest surviving COMPLETED
     attempt for any (student, course) that had a Fall 2025 attempt removed
     and is left with no attempt currently marked as counting — i.e. only the
     pairs this deletion actually broke. A pair that still has a counting
     attempt (Fall 2025 was the older, already-suppressed one) is left alone.

   GRADUATION CREDIT BACKFILL (step 13b)
     A GRADUATED student can have real, counted, PASSED Fall 2025 credit
     feeding their completed_credits cache (phase20_academic_data_integrity.sql
     step D drew backfill courses only from semesters that were already
     non-current with an end_date before the then-current semester's
     start_date, so it could not have targeted Fall 2025 itself — but plenty
     of students genuinely completed real Fall 2025 courses on their own,
     including some graduated students whose total only clears their
     program's requirement because of it). Deleting that credit can newly
     drop a graduated student below total_credits_required — the exact
     "impossible history" phase20 D already treats as something to repair,
     not something to leave standing.

     Tested against a snapshot of the live database taken immediately before
     this migration: 29 GRADUATED students were short on credits afterward,
     against 23 before. Diffing the two by student_id showed only 4 of the 6
     newly-short students were actually caused by this deletion (their
     Fall-2025-inclusive total already exactly met or exceeded requirement,
     and only dropped below it once Fall 2025's credit was removed); the
     other 2 were already short even before Fall 2025 was touched — their
     cached completed_credits (set by some earlier process) simply disagreed
     with what GradeDAO's own formula has always actually computed for them,
     the same kind of pre-existing cache drift phase20's own audit found in
     96% of students before step E. Step 13b must fix only the first kind.

     So step 13b computes, BEFORE any deletion (immediately below, into
     @needsBackfill), exactly which GRADUATED students have
     Fall-2025-inclusive completed credits >= their requirement AND
     Fall-2025-EXCLUDED completed credits < their requirement — the narrow
     set this deletion, and only this deletion, pushes under the line. For
     each, it tops them up using the same method phase20 step D already
     established: real courses from their own program they have not already
     passed, in real past sections, preferring mandatory requirements and
     earlier semesters first. Because Fall 2025's sections are already gone
     from dbo.sections by the time this runs, it can never pick one.

   SAFE TO RE-RUN
     Everything is keyed off @fallSemesterId, looked up by semester_name. Once
     the dbo.semesters row is gone, the whole ELSE branch is skipped on any
     later run.
============================================================================ */

SET QUOTED_IDENTIFIER ON;
SET NOCOUNT ON;

DECLARE @fallSemesterId INT = (SELECT semester_id FROM dbo.semesters WHERE semester_name = N'Fall 2025');

IF @fallSemesterId IS NULL
BEGIN
    PRINT N'0029: no Fall 2025 semester row found - already removed, nothing to do.';
END
ELSE
BEGIN
    PRINT N'0029: removing Fall 2025 (semester_id ' + CAST(@fallSemesterId AS NVARCHAR(10)) + N') and all dependent data.';

    DECLARE @fallSectionIds TABLE (section_id INT PRIMARY KEY);
    INSERT INTO @fallSectionIds
    SELECT section_id FROM dbo.sections WHERE semester_id = @fallSemesterId;

    DECLARE @fallEnrollmentIds TABLE (enrollment_id INT PRIMARY KEY);
    INSERT INTO @fallEnrollmentIds
    SELECT enrollment_id FROM dbo.enrollments WHERE section_id IN (SELECT section_id FROM @fallSectionIds);

    -- Captured BEFORE the delete below, for the repeat-policy fix-up in step 5b.
    DECLARE @fallCompletedCourses TABLE (student_id INT NOT NULL, course_id INT NOT NULL);
    INSERT INTO @fallCompletedCourses (student_id, course_id)
    SELECT DISTINCT e.student_id, sec.course_id
      FROM dbo.enrollments e
      JOIN dbo.sections sec ON sec.section_id = e.section_id
     WHERE e.enrollment_id IN (SELECT enrollment_id FROM @fallEnrollmentIds)
       AND e.status = 'COMPLETED';

    -- Captured BEFORE the delete below, for the graduation credit backfill in step 13b —
    -- see that step's header comment for exactly what this identifies and why.
    DECLARE @needsBackfill TABLE (student_id INT PRIMARY KEY, program_id INT NOT NULL,
                                   total_credits_required INT NOT NULL, admission_date DATE NOT NULL);
    INSERT INTO @needsBackfill (student_id, program_id, total_credits_required, admission_date)
    SELECT s.student_id, s.program_id, p.total_credits_required, s.admission_date
      FROM dbo.students s
      JOIN dbo.programs p ON p.program_id = s.program_id
      CROSS APPLY (
          SELECT
              SUM(CASE WHEN e.status = 'COMPLETED' AND e.counts_in_gpa = 1
                         AND g.is_submitted = 1 AND g.result_status = 'PASSED'
                       THEN c.credits ELSE 0 END) AS incl_fall,
              SUM(CASE WHEN e.status = 'COMPLETED' AND e.counts_in_gpa = 1
                         AND g.is_submitted = 1 AND g.result_status = 'PASSED'
                         AND sec.semester_id <> @fallSemesterId
                       THEN c.credits ELSE 0 END) AS excl_fall
            FROM dbo.enrollments e
            JOIN dbo.sections sec ON sec.section_id = e.section_id
            JOIN dbo.courses c ON c.course_id = sec.course_id
            LEFT JOIN dbo.grades g ON g.enrollment_id = e.enrollment_id
           WHERE e.student_id = s.student_id
      ) totals
     WHERE s.status = 'GRADUATED'
       AND ISNULL(totals.incl_fall, 0) >= p.total_credits_required
       AND ISNULL(totals.excl_fall, 0) <  p.total_credits_required;

    DECLARE @fallInvoiceIds TABLE (invoice_id INT PRIMARY KEY);
    INSERT INTO @fallInvoiceIds
    SELECT invoice_id FROM dbo.student_invoices WHERE semester_id = @fallSemesterId;

    DECLARE @fallPaymentIds TABLE (payment_id INT PRIMARY KEY);
    INSERT INTO @fallPaymentIds
    SELECT payment_id FROM dbo.payments WHERE invoice_id IN (SELECT invoice_id FROM @fallInvoiceIds);

    -- ---------------------------------------------------------------- 1.
    DELETE FROM dbo.instructor_evaluations
     WHERE enrollment_id IN (SELECT enrollment_id FROM @fallEnrollmentIds);
    PRINT N'   instructor_evaluations removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10)) + N' (evaluation_answers cascaded)';

    -- ---------------------------------------------------------------- 2.
    DELETE FROM dbo.attendance_records
     WHERE section_id IN (SELECT section_id FROM @fallSectionIds);
    PRINT N'   attendance_records removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 3.
    DELETE FROM dbo.exams
     WHERE section_id IN (SELECT section_id FROM @fallSectionIds);
    PRINT N'   exams removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 4.
    DELETE FROM dbo.grades
     WHERE enrollment_id IN (SELECT enrollment_id FROM @fallEnrollmentIds);
    PRINT N'   grades removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 5.
    DELETE FROM dbo.enrollments
     WHERE section_id IN (SELECT section_id FROM @fallSectionIds);
    PRINT N'   enrollments removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 5b.
    ;WITH remaining AS (
        SELECT e.enrollment_id, f.student_id, f.course_id,
               ROW_NUMBER() OVER (PARTITION BY f.student_id, f.course_id
                                   ORDER BY e.enrollment_date DESC, e.enrollment_id DESC) AS rn
          FROM @fallCompletedCourses f
          JOIN dbo.sections sec ON sec.course_id = f.course_id
          JOIN dbo.enrollments e ON e.section_id = sec.section_id AND e.student_id = f.student_id
         WHERE e.status = 'COMPLETED'
    )
    UPDATE e
       SET e.counts_in_gpa = 1
      FROM dbo.enrollments e
      JOIN remaining r ON r.enrollment_id = e.enrollment_id
     WHERE r.rn = 1
       AND NOT EXISTS (
             SELECT 1 FROM dbo.enrollments e2
             JOIN dbo.sections sec2 ON sec2.section_id = e2.section_id
            WHERE e2.student_id = r.student_id AND sec2.course_id = r.course_id
              AND e2.status = 'COMPLETED' AND e2.counts_in_gpa = 1);
    PRINT N'   repeat-policy counts_in_gpa restored on = ' + CAST(@@ROWCOUNT AS NVARCHAR(10)) + N' surviving attempt(s)';

    -- ---------------------------------------------------------------- 6.
    DELETE FROM dbo.financial_transactions
     WHERE invoice_id IN (SELECT invoice_id FROM @fallInvoiceIds)
        OR payment_id IN (SELECT payment_id FROM @fallPaymentIds);
    PRINT N'   financial_transactions removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 7.
    DELETE FROM dbo.payments
     WHERE invoice_id IN (SELECT invoice_id FROM @fallInvoiceIds);
    PRINT N'   payments removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 8.
    DELETE FROM dbo.student_invoices WHERE semester_id = @fallSemesterId;
    PRINT N'   student_invoices removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10)) + N' (invoice_items cascaded)';

    -- ---------------------------------------------------------------- 9.
    DELETE FROM dbo.student_tuition_installments WHERE semester_id = @fallSemesterId;
    PRINT N'   student_tuition_installments removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 10.
    DELETE FROM dbo.calendar_events WHERE semester_id = @fallSemesterId;
    PRINT N'   calendar_events removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 11.
    DELETE FROM dbo.sections WHERE semester_id = @fallSemesterId;
    PRINT N'   sections removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10)) + N' (section_schedules cascaded)';

    -- ---------------------------------------------------------------- 12.
    DELETE FROM dbo.semesters WHERE semester_id = @fallSemesterId;
    PRINT N'   semesters row removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10)) + N' (tuition_rates cascaded)';

    -- ---------------------------------------------------------------- 13.
    DELETE FROM dbo.notifications
     WHERE title   LIKE N'%Fall 2025%'
        OR message LIKE N'%Fall 2025%';
    PRINT N'   notifications removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 13b.
    -- Same algorithm as phase20_academic_data_integrity.sql step D, scoped to
    -- @needsBackfill (captured before any deletion above — see its header
    -- comment). Fall 2025's own sections are already gone from dbo.sections
    -- by this point, so this cannot pick one even though it no longer needs
    -- to filter it out explicitly.
    DECLARE @currentStart DATE = (SELECT start_date FROM dbo.semesters WHERE is_current = 1);
    DECLARE @backfillStudentId INT, @backfillProgramId INT, @backfillRequired INT, @backfillAdmission DATE;
    DECLARE @backfillCount INT = 0;

    DECLARE backfill_cursor CURSOR LOCAL FAST_FORWARD FOR
        SELECT student_id, program_id, total_credits_required, admission_date FROM @needsBackfill;

    OPEN backfill_cursor;
    FETCH NEXT FROM backfill_cursor INTO @backfillStudentId, @backfillProgramId, @backfillRequired, @backfillAdmission;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        DECLARE @completed INT;
        SELECT @completed = ISNULL(SUM(c.credits), 0)
          FROM dbo.grades g
          JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id
          JOIN dbo.sections sec ON sec.section_id = e.section_id
          JOIN dbo.courses c ON c.course_id = sec.course_id
         WHERE e.student_id = @backfillStudentId AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1
           AND g.is_submitted = 1 AND g.result_status = 'PASSED';

        DECLARE @attempts INT = 0;
        WHILE @completed < @backfillRequired AND @attempts < 30
        BEGIN
            DECLARE @courseId INT, @sectionId INT, @courseCredits INT, @instructorUserId INT;

            SELECT TOP 1 @courseId = c.course_id, @sectionId = sec.section_id, @courseCredits = c.credits
              FROM dbo.program_requirements pr
              JOIN dbo.courses c ON c.course_id = pr.course_id
              JOIN dbo.sections sec ON sec.course_id = c.course_id
              JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id
             WHERE pr.program_id = @backfillProgramId
               AND sem.is_current = 0
               AND sem.start_date >= @backfillAdmission
               AND (@currentStart IS NULL OR sem.end_date < @currentStart)
               AND NOT EXISTS (
                     SELECT 1 FROM dbo.enrollments e2
                     JOIN dbo.sections sec2 ON sec2.section_id = e2.section_id
                    WHERE e2.student_id = @backfillStudentId AND sec2.course_id = c.course_id
                      AND e2.status = 'COMPLETED')
               AND NOT EXISTS (
                     SELECT 1 FROM dbo.enrollments e3
                    WHERE e3.student_id = @backfillStudentId AND e3.section_id = sec.section_id)
             ORDER BY pr.is_mandatory DESC, sem.start_date ASC, c.course_id ASC;

            IF @courseId IS NULL BREAK;

            SELECT @instructorUserId = i.user_id
              FROM dbo.sections sec JOIN dbo.instructors i ON i.instructor_id = sec.instructor_id
             WHERE sec.section_id = @sectionId;

            DECLARE @enrollDate DATETIME2 = (
                SELECT DATEADD(DAY, 5, sem.start_date) FROM dbo.sections sec
                JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id
               WHERE sec.section_id = @sectionId);

            INSERT INTO dbo.enrollments (student_id, section_id, enrollment_date, status, is_repeat, counts_in_gpa)
            VALUES (@backfillStudentId, @sectionId, @enrollDate, 'COMPLETED', 0, 1);

            DECLARE @newEnrollmentId INT = SCOPE_IDENTITY();

            INSERT INTO dbo.grades (enrollment_id, coursework_mark, midterm_mark, final_mark, total_mark,
                                     letter_grade, grade_points, result_status, is_submitted,
                                     submitted_by, submitted_at)
            VALUES (@newEnrollmentId, 78.00, 76.00, 78.00, 77.30, 'B-', 2.70, 'PASSED', 1,
                    @instructorUserId, DATEADD(DAY, 45, @enrollDate));

            SET @completed += @courseCredits;
            SET @attempts += 1;
        END

        SET @backfillCount += 1;
        FETCH NEXT FROM backfill_cursor INTO @backfillStudentId, @backfillProgramId, @backfillRequired, @backfillAdmission;
    END
    CLOSE backfill_cursor;
    DEALLOCATE backfill_cursor;
    PRINT N'   graduated students backfilled to meet their credit requirement = ' + CAST(@backfillCount AS NVARCHAR(10));

    -- ---------------------------------------------------------------- 14.
    -- Same formula as phase20_academic_data_integrity.sql step E, re-run for
    -- every student so the cache agrees with dbo.grades now that Fall 2025's
    -- rows (and any repeat-policy fix-up from 5b) are gone.
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
    PRINT N'   students recomputed (cumulative_gpa/completed_credits/academic_standing) = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    PRINT N'0029: Fall 2025 removal complete.';
END

/* ---------------------------------------------------------------- verify */
SELECT
    (SELECT COUNT(*) FROM dbo.semesters WHERE semester_name = N'Fall 2025')            AS fall_2025_semester_rows,
    (SELECT COUNT(*) FROM dbo.notifications
      WHERE title LIKE N'%Fall 2025%' OR message LIKE N'%Fall 2025%')                  AS fall_2025_notifications,
    (SELECT COUNT(*) FROM dbo.semesters WHERE semester_name = N'Spring 2025')          AS spring_2025_still_present,
    (SELECT COUNT(*) FROM dbo.students s JOIN dbo.programs p ON p.program_id = s.program_id
      WHERE s.status = 'GRADUATED' AND s.completed_credits < p.total_credits_required)
                                                                                        AS graduated_still_short_on_credits;
