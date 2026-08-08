/* ============================================================================
   0004 — finish phase20's graduated-student credit backfill

   WHY
     phase20_academic_data_integrity.sql step D backfills enough historical
     COMPLETED/PASSED coursework for every GRADUATED student whose real grades
     fall short of their program's total_credits_required. Its loop declares
     @courseId/@sectionId/@courseCredits/@instructorUserId fresh on every pass
     but never resets them to NULL before the SELECT TOP 1 that fills them —
     and in T-SQL, executing a bare DECLARE again does not clear a variable
     that was already assigned a value, so once a student's pool of eligible
     sections ran out mid-loop, the loop kept reusing the previous iteration's
     values instead of seeing "no more candidates" and stopping cleanly.

     On this database that reuse never actually raised an error, but it did
     leave 26 GRADUATED students still short of their program's credit
     requirement after phase20 committed and was recorded in
     dbo.schema_migrations — confirmed against phase20's own end-of-script
     verification query, which reports graduated_still_short_on_credits = 26
     on this database. Each of those 26 has dozens of unused eligible sections
     available right now, so the shortfall is this loop bug, not a lack of
     real historical data to draw from.

     phase20_academic_data_integrity.sql has already run and is recorded as
     applied — per this project's migration rules it is never edited after
     the fact, so the fix (resetting the loop variables every pass) is
     applied here instead, scoped to only the students phase20 left short.

   SAFE TO RE-RUN
     The cursor's own WHERE clause selects only students currently short on
     credits, so once a student is caught up a later run simply finds them
     absent from that cursor and does nothing for them. The cache refresh at
     the end only touches students this run actually inserted coursework for.
============================================================================ */

SET NOCOUNT ON;

DECLARE @backfilled TABLE (student_id INT NOT NULL PRIMARY KEY);

DECLARE @gradStudentId INT, @programId INT, @required INT, @admission DATE;
DECLARE @currentStart DATE = (SELECT start_date FROM dbo.semesters WHERE is_current = 1);

DECLARE grad_cursor CURSOR LOCAL FAST_FORWARD FOR
    SELECT s.student_id, s.program_id, p.total_credits_required, s.admission_date
      FROM dbo.students s
      JOIN dbo.programs p ON p.program_id = s.program_id
     WHERE s.status = 'GRADUATED'
       AND s.completed_credits < p.total_credits_required;

OPEN grad_cursor;
FETCH NEXT FROM grad_cursor INTO @gradStudentId, @programId, @required, @admission;
WHILE @@FETCH_STATUS = 0
BEGIN
    DECLARE @completed INT;
    SELECT @completed = ISNULL(SUM(c.credits), 0)
      FROM dbo.grades g
      JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id
      JOIN dbo.sections sec ON sec.section_id = e.section_id
      JOIN dbo.courses c ON c.course_id = sec.course_id
     WHERE e.student_id = @gradStudentId AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1
       AND g.is_submitted = 1 AND g.result_status = 'PASSED';

    DECLARE @attempts INT = 0;
    DECLARE @insertedForStudent INT = 0;

    WHILE @completed < @required AND @attempts < 30
    BEGIN
        -- Reset every pass: without this, a pass where the SELECT TOP 1 below
        -- matches zero rows leaves these holding the previous pass's values,
        -- so "no more candidates" is never seen and the loop keeps reusing an
        -- already-inserted section instead of stopping.
        DECLARE @courseId INT = NULL, @sectionId INT = NULL, @courseCredits INT = NULL, @instructorUserId INT = NULL;

        SELECT TOP 1 @courseId = c.course_id, @sectionId = sec.section_id, @courseCredits = c.credits
          FROM dbo.program_requirements pr
          JOIN dbo.courses c ON c.course_id = pr.course_id
          JOIN dbo.sections sec ON sec.course_id = c.course_id
          JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id
         WHERE pr.program_id = @programId
           AND sem.is_current = 0
           AND sem.start_date >= @admission
           AND (@currentStart IS NULL OR sem.end_date < @currentStart)
           AND NOT EXISTS (
                 SELECT 1 FROM dbo.enrollments e2
                 JOIN dbo.sections sec2 ON sec2.section_id = e2.section_id
                WHERE e2.student_id = @gradStudentId AND sec2.course_id = c.course_id
                  AND e2.status = 'COMPLETED')
           AND NOT EXISTS (
                 SELECT 1 FROM dbo.enrollments e3
                WHERE e3.student_id = @gradStudentId AND e3.section_id = sec.section_id)
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
        VALUES (@gradStudentId, @sectionId, @enrollDate, 'COMPLETED', 0, 1);

        DECLARE @newEnrollmentId INT = SCOPE_IDENTITY();

        INSERT INTO dbo.grades (enrollment_id, coursework_mark, midterm_mark, final_mark, total_mark,
                                 letter_grade, grade_points, result_status, is_submitted,
                                 submitted_by, submitted_at)
        VALUES (@newEnrollmentId, 78.00, 76.00, 78.00, 77.30, 'B-', 2.70, 'PASSED', 1,
                @instructorUserId, DATEADD(DAY, 45, @enrollDate));

        SET @completed += @courseCredits;
        SET @attempts += 1;
        SET @insertedForStudent += 1;
    END

    IF @insertedForStudent > 0
        INSERT INTO @backfilled (student_id) VALUES (@gradStudentId);

    FETCH NEXT FROM grad_cursor INTO @gradStudentId, @programId, @required, @admission;
END
CLOSE grad_cursor;
DEALLOCATE grad_cursor;

-- Refresh the cumulative_gpa / completed_credits / academic_standing cache
-- (same formulas as phase20 step E) for exactly the students this migration
-- added coursework for.
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
      JOIN @backfilled b ON b.student_id = s.student_id
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

DECLARE @backfilledCount INT = (SELECT COUNT(*) FROM @backfilled);
PRINT N'0004: graduated students backfilled = ' + CAST(@backfilledCount AS NVARCHAR(10));
