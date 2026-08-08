/* ============================================================================
   PHASE 20 MIGRATION — academic data integrity: stale registrations, over-cap
                        loads, insufficient graduation credits, and the
                        cumulative_gpa / completed_credits / academic_standing
                        caches
   ----------------------------------------------------------------------------
   WHY THIS EXISTS

   An audit of the live database (run before writing this script) found:

     A. 108 rows in dbo.enrollments with status = 'ENROLLED' belonging to
        students whose dbo.students.status is already 'GRADUATED' — a
        graduated student who somehow never stopped being "currently
        registered." None of these rows have a grade attached, so they are
        not a forgotten completion — they are stale registrations that should
        never have survived graduation, and they were occupying real seats.

     B. 1 pair of ENROLLED rows for the same student, same course, same
        semester, in two different sections (student 217, course 2, both
        sections of it, Fall 2026) — a genuine duplicate active registration.

     C. 9 students (out of 625) already carrying more than 18 credits of
        ENROLLED coursework in the current semester. This matches
        RegistrationService.creditCapFor(), which — before this phase's Java
        change — allowed up to 21 credits for a GPA at or above 3.00. This
        phase's brief sets a flat 18-credit ceiling for every student, so
        these 9 are now over the (new) limit and are brought back under it.

     D. 32 of the 40 GRADUATED students have fewer real completed-and-passed
        credits than their program's total_credits_required — graduation with
        an incomplete degree, which the brief explicitly calls an impossible
        history. Each such student is given enough additional historical
        COMPLETED/PASSED enrollments (drawn from real courses in their own
        program, in real past sections that already exist, that they had not
        already passed) to meet their program's credit requirement.

     E. dbo.students.cumulative_gpa and completed_credits are supposed to be a
        cache of what GradeDAO.calculateCumulativeGpa /
        calculateCompletedCredits compute from dbo.grades — but 602 of 625
        students (96%) had a cumulative_gpa that disagreed with that
        calculation by more than 0.05, and 560 of 625 (90%) had a
        completed_credits that disagreed outright. That mismatch is also why
        academic_standing skewed so heavily toward GOOD (453 of 625, 72%): the
        standing was resting on a cache that did not reflect the grades
        actually in the database. This step recomputes both figures with the
        *exact* formulas GradeDAO already uses (same joins, same filters —
        e.central COMPLETED, counts_in_gpa = 1, is_submitted = 1, letter grade
        not W/I for GPA; result_status = 'PASSED' for completed credits) and
        then re-derives academic_standing with AcademicService.standingFor's
        exact thresholds (NEW / PROBATION / SUSPENDED / DEANS_LIST / GOOD),
        using each student's *existing* probation_count — this script does
        not attempt to replay years of term-by-term history to recompute that
        counter, only to make the cache agree with the grades that exist today.

   WHAT THIS SCRIPT DELIBERATELY DOES NOT DO
     It does not touch the ~5,400 historical enrollments the same audit found
     recorded before their own prerequisite was completed. Correcting those
     would mean re-dating or re-assigning thousands of historical enrollment
     rows across hundreds of students, which is a full historical rebuild, not
     a repair, and the brief is explicit that this database must be migrated
     and repaired, not rebuilt. RegistrationService already refuses a NEW
     registration that skips a prerequisite (rule R3), so this is pre-existing
     seed-data noise that cannot recur going forward, not a live defect. It is
     called out here, and in the final report, rather than silently left
     unmentioned.

   SAFE TO RE-RUN: step A/B/C only touch rows still in the state they target,
   step D stops adding courses once a student's requirement is met (and is a
   no-op on a second run because those students no longer fall short), and
   step E simply recomputes the same figures again. Everything runs inside one
   transaction that rolls back on any error.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

    DECLARE @touched TABLE (section_id INT NOT NULL, enrollment_id INT NOT NULL);

    -- ---------------------------------------------------------------- A.
    PRINT N'A. Dropping stale ENROLLED rows belonging to already-GRADUATED students.';

    UPDATE e
       SET e.status = 'DROPPED'
     OUTPUT inserted.section_id, inserted.enrollment_id INTO @touched
      FROM dbo.enrollments e
      JOIN dbo.students st ON st.student_id = e.student_id
     WHERE e.status = 'ENROLLED'
       AND st.status = 'GRADUATED';

    PRINT N'   rows dropped: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- B.
    PRINT N'B. Dropping duplicate same-course, same-semester ENROLLED rows (keeping the earliest).';

    ;WITH dup AS (
        SELECT e.enrollment_id, e.section_id,
               ROW_NUMBER() OVER (PARTITION BY e.student_id, sec.course_id, sec.semester_id
                                   ORDER BY e.enrollment_date ASC, e.enrollment_id ASC) AS rn
          FROM dbo.enrollments e
          JOIN dbo.sections sec ON sec.section_id = e.section_id
         WHERE e.status = 'ENROLLED'
    )
    UPDATE e
       SET e.status = 'DROPPED'
     OUTPUT inserted.section_id, inserted.enrollment_id INTO @touched
      FROM dbo.enrollments e
      JOIN dup ON dup.enrollment_id = e.enrollment_id
     WHERE dup.rn > 1;

    PRINT N'   rows dropped: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    -- ---------------------------------------------------------------- C.
    PRINT N'C. Capping current-semester registered load at 18 credits (dropping the most recent excess).';

    DECLARE @currentSemesterId INT = (SELECT semester_id FROM dbo.semesters WHERE is_current = 1);

    IF @currentSemesterId IS NOT NULL
    BEGIN
        DECLARE @studentId INT, @over INT;
        DECLARE over_cursor CURSOR LOCAL FAST_FORWARD FOR
            SELECT e.student_id, SUM(c.credits) - 18
              FROM dbo.enrollments e
              JOIN dbo.sections sec ON sec.section_id = e.section_id
              JOIN dbo.courses c ON c.course_id = sec.course_id
             WHERE e.status = 'ENROLLED' AND sec.semester_id = @currentSemesterId
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
                 WHERE e.student_id = @studentId AND e.status = 'ENROLLED'
                   AND sec.semester_id = @currentSemesterId
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
    END
    ELSE
        PRINT N'   no current semester is set - nothing to cap.';

    -- --------------------------------------------------- sync sections.enrolled_count
    PRINT N'   Syncing sections.enrolled_count for every section touched by A/B/C above.';

    UPDATE sec
       SET sec.enrolled_count = sec.enrolled_count - x.cnt
      FROM dbo.sections sec
      JOIN (SELECT section_id, COUNT(*) AS cnt FROM @touched GROUP BY section_id) x
        ON x.section_id = sec.section_id;

    -- ---------------------------------------------------------------- D.
    PRINT N'D. Backfilling insufficient completed credits for GRADUATED students.';

    DECLARE @gradStudentId INT, @programId INT, @required INT, @admission DATE;
    DECLARE @currentStart DATE = (SELECT start_date FROM dbo.semesters WHERE is_current = 1);

    DECLARE grad_cursor CURSOR LOCAL FAST_FORWARD FOR
        SELECT s.student_id, s.program_id, p.total_credits_required, s.admission_date
          FROM dbo.students s
          JOIN dbo.programs p ON p.program_id = s.program_id
         WHERE s.status = 'GRADUATED';

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
        WHILE @completed < @required AND @attempts < 30
        BEGIN
            DECLARE @courseId INT, @sectionId INT, @courseCredits INT, @instructorUserId INT;

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
        END

        FETCH NEXT FROM grad_cursor INTO @gradStudentId, @programId, @required, @admission;
    END
    CLOSE grad_cursor;
    DEALLOCATE grad_cursor;

    -- ---------------------------------------------------------------- E.
    PRINT N'E. Recomputing cumulative_gpa / completed_credits / academic_standing for every student.';

    ;WITH agg AS (
        SELECT s.student_id,
               -- Same predicate GradeDAO.gpaQuery uses for the cumulative GPA.
               SUM(CASE WHEN e.status = 'COMPLETED' AND e.counts_in_gpa = 1
                          AND g.is_submitted = 1 AND g.letter_grade NOT IN ('W','I')
                        THEN c.credits ELSE 0 END) AS gpa_credits,
               SUM(CASE WHEN e.status = 'COMPLETED' AND e.counts_in_gpa = 1
                          AND g.is_submitted = 1 AND g.letter_grade NOT IN ('W','I')
                        THEN g.grade_points * c.credits ELSE 0 END) AS quality_points,
               -- Same predicate GradeDAO.calculateCompletedCredits uses.
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
        -- ROUND (not a truncating CAST) to match BigDecimal.setScale(2, HALF_UP)
        -- in GradeDAO.gpaQuery as closely as T-SQL's rounding allows.
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
           -- Mirrors AcademicService.refreshAcademicRecord: only ever *forces*
           -- status to SUSPENDED from ACTIVE; graduated/withdrawn/already-
           -- suspended students keep whatever status they already have.
           s.status = CASE WHEN w.standing = 'SUSPENDED' AND s.status = 'ACTIVE'
                            THEN 'SUSPENDED' ELSE s.status END
      FROM dbo.students s
      JOIN withStanding w ON w.student_id = s.student_id;

    PRINT N'   students recomputed: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

COMMIT TRANSACTION;
PRINT N'--- Phase 20 migration committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0
        ROLLBACK TRANSACTION;
    PRINT N'!!! Phase 20 migration ROLLED BACK — nothing was changed. !!!';
    THROW;
END CATCH
GO

/* ---------------------------------------------------------------- verify */
SELECT
    (SELECT COUNT(*) FROM dbo.enrollments e JOIN dbo.students s ON s.student_id = e.student_id
      WHERE e.status = 'ENROLLED' AND s.status = 'GRADUATED')          AS graduated_still_enrolled,
    (SELECT COUNT(*) FROM (
        SELECT e.student_id, sec.course_id, sec.semester_id, COUNT(*) c
          FROM dbo.enrollments e JOIN dbo.sections sec ON sec.section_id = e.section_id
         WHERE e.status = 'ENROLLED'
         GROUP BY e.student_id, sec.course_id, sec.semester_id HAVING COUNT(*) > 1) x)
                                                                         AS duplicate_active_same_course,
    (SELECT COUNT(*) FROM (
        SELECT e.student_id FROM dbo.enrollments e
          JOIN dbo.sections sec ON sec.section_id = e.section_id
          JOIN dbo.courses c ON c.course_id = sec.course_id
          JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id AND sem.is_current = 1
         WHERE e.status = 'ENROLLED'
         GROUP BY e.student_id HAVING SUM(c.credits) > 18) x)            AS students_over_18_current,
    (SELECT COUNT(*) FROM dbo.students s JOIN dbo.programs p ON p.program_id = s.program_id
      WHERE s.status = 'GRADUATED' AND s.completed_credits < p.total_credits_required)
                                                                         AS graduated_still_short_on_credits,
    (SELECT COUNT(*) FROM dbo.students s
      WHERE ABS(s.cumulative_gpa - ISNULL((
                SELECT CAST(CASE WHEN SUM(c.credits) IS NULL OR SUM(c.credits) = 0 THEN 0
                                 ELSE SUM(g.grade_points * c.credits) / SUM(c.credits) END AS DECIMAL(5,4))
                  FROM dbo.grades g
                  JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id
                  JOIN dbo.sections sec ON sec.section_id = e.section_id
                  JOIN dbo.courses c ON c.course_id = sec.course_id
                 WHERE e.student_id = s.student_id AND e.status = 'COMPLETED' AND e.counts_in_gpa = 1
                   AND g.is_submitted = 1 AND g.letter_grade NOT IN ('W','I')), 0)) > 0.01)
                                                                         AS gpa_cache_mismatches,
    (SELECT academic_standing, COUNT(*) AS cnt FROM dbo.students GROUP BY academic_standing
      FOR JSON PATH)                                                    AS standing_distribution_json;
GO
