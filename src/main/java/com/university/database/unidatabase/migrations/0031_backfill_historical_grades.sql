/* ============================================================================
   0031 — backfill missing/incomplete historical grades

   WHY
     Audit request: every course a student previously took, in a semester that
     is not the current one, and whose enrollment is not DROPPED or WITHDRAWN,
     must end up with a valid, submitted grade record (Coursework+Midterm+Final
     for a course with no lab, Lab+Midterm+Final for one that has a lab —
     dbo.courses.has_lab — with Total/Letter/GradePoints/ResultStatus computed
     the same way GradeService/GradeCalculator computes them live). A snapshot
     of the live database taken before this migration found 324 such
     enrollments (all already status = COMPLETED; none missing a dbo.grades
     row outright — every one already had a row, just an incomplete or
     never-submitted one: 20 fully-marked rows stuck at is_submitted = 0, 270
     lab courses with no lab_mark, 34 non-lab courses with no coursework_mark,
     35 with no midterm_mark, 35 with no final_mark).

   WHAT COUNTS AS "HISTORICAL"
     Any enrollment whose section belongs to a semester where is_current = 0.
     The current semester is deliberately never touched here — its courses are
     still in progress by design, exactly as the audit asked. (Every semester
     that is not current in this system's lifecycle is also already in the
     past — the live data above confirms it: the latest non-current end_date,
     2025-06-15, is well before the current semester's own start_date,
     2026-08-01 — but the is_current check alone is what actually decides it,
     the same signal SemesterService/AdminSemestersController use everywhere
     else for "the term still in progress".)

   WHAT GETS A RANDOM MARK, AND WHAT DOESN'T
     Only a component that is genuinely still NULL is randomly generated
     (uniformly between 40% and 100% of that component's own course-configured
     max mark, matching the instructor grade sheet's own 0..max legality rule,
     GradeCalculator.isValidMark). A mark an instructor already entered is
     COALESCEd through untouched — this migration completes a record, it does
     not overwrite real marks. Total/Letter/GradePoints/ResultStatus are then
     (re)computed from whatever the final mix of real + generated marks is,
     with the exact Section 5.1/5.2 formula GradeCalculator.totalMark/
     letterGrade uses (weights and max marks read from the course, same as
     production), so a mix of real and generated components can land on
     either side of the pass/fail line — that is expected, not a bug.

   WHAT "SUBMITTED/COMPLETED" MEANS HERE
     Mirrors GradeService.submitSection (rule G6): is_submitted = 1,
     submitted_by/submitted_at stamped, every component's *_published flag and
     *_published_mark snapshot set exactly the way Submit-and-Lock sets them
     (coursework only when the course has no lab, lab only when it does,
     midterm/final always), and the enrollment status moved ENROLLED ->
     COMPLETED (none of the 324 needed that move — all were already
     COMPLETED — but the step exists for correctness on any database where
     one still is). submitted_by prefers the section's own instructor's
     user_id, falling back to the earliest ADMIN account only when the
     section has none (an unassigned "TBA" section).

   CACHE REFRESH, SCOPED
     Only the students who actually got a grade touched here have their
     cumulative_gpa/completed_credits/academic_standing cache columns
     recomputed, using the exact aggregate formula
     phase20_academic_data_integrity.sql step E and
     0029_remove_fall_2025_completely.sql step 14 already established for a
     bulk backfill like this one. Deliberately narrower than the live
     AcademicService.refreshAcademicRecord: no probation_count change and no
     "your standing changed" notification are fired for a years-old semester
     being backfilled today — 0029 step 14 made the same call for the same
     reason.

   SAFE TO RE-RUN
     Every target is re-selected by the same "missing or incomplete or
     unsubmitted" condition each time; a row this migration already completed
     no longer matches it, so a second run finds nothing left to touch.
============================================================================ */

SET QUOTED_IDENTIFIER ON;
SET NOCOUNT ON;

DECLARE @now DATETIME2 = SYSDATETIME();
DECLARE @fallbackAdminUserId INT = (SELECT TOP 1 user_id FROM dbo.users WHERE role = N'ADMIN' ORDER BY user_id);

/* ---- every enrollment this migration must give a valid grade record to ---- */
DECLARE @targets TABLE (
    enrollment_id          INT PRIMARY KEY,
    student_id             INT NOT NULL,
    grade_id               INT NULL,
    has_lab                BIT NOT NULL,
    coursework_weight      DECIMAL(5,2) NOT NULL,
    midterm_weight         DECIMAL(5,2) NOT NULL,
    final_weight           DECIMAL(5,2) NOT NULL,
    coursework_max_mark    DECIMAL(6,2) NOT NULL,
    midterm_max_mark       DECIMAL(6,2) NOT NULL,
    final_max_mark         DECIMAL(6,2) NOT NULL,
    existing_coursework_mark DECIMAL(5,2) NULL,
    existing_midterm_mark    DECIMAL(5,2) NULL,
    existing_lab_mark        DECIMAL(5,2) NULL,
    existing_final_mark      DECIMAL(5,2) NULL,
    submitted_by_user_id   INT NULL
);

INSERT INTO @targets
SELECT
    e.enrollment_id, e.student_id, g.grade_id, c.has_lab,
    c.coursework_weight, c.midterm_weight, c.final_weight,
    c.coursework_max_mark, c.midterm_max_mark, c.final_max_mark,
    g.coursework_mark, g.midterm_mark, g.lab_mark, g.final_mark,
    COALESCE(i.user_id, @fallbackAdminUserId)
FROM dbo.enrollments e
JOIN dbo.sections sec ON sec.section_id = e.section_id
JOIN dbo.courses c ON c.course_id = sec.course_id
JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id
LEFT JOIN dbo.instructors i ON i.instructor_id = sec.instructor_id
LEFT JOIN dbo.grades g ON g.enrollment_id = e.enrollment_id
WHERE e.status IN (N'ENROLLED', N'COMPLETED')
  AND sem.is_current = 0
  AND (
        g.grade_id IS NULL
     OR g.is_submitted = 0
     OR g.midterm_mark IS NULL
     OR g.final_mark IS NULL
     OR (c.has_lab = 1 AND g.lab_mark IS NULL)
     OR (c.has_lab = 0 AND g.coursework_mark IS NULL)
     OR g.total_mark IS NULL OR g.letter_grade IS NULL
     OR g.grade_points IS NULL OR g.result_status IS NULL
  );

/* ---- generate the missing components, compute Total/Letter/Points/Status ---- */
DECLARE @generated TABLE (
    enrollment_id   INT PRIMARY KEY,
    coursework_mark DECIMAL(5,2) NULL,
    midterm_mark    DECIMAL(5,2) NOT NULL,
    lab_mark        DECIMAL(5,2) NULL,
    final_mark      DECIMAL(5,2) NOT NULL,
    total_mark      DECIMAL(5,2) NOT NULL,
    letter_grade    NVARCHAR(2) NOT NULL,
    grade_points    DECIMAL(3,2) NOT NULL,
    result_status   NVARCHAR(10) NOT NULL
);

;WITH filled AS (
    SELECT
        t.enrollment_id, t.has_lab,
        t.coursework_weight, t.midterm_weight, t.final_weight,
        -- Never above 100: the component CHECK constraints (CK_grades_coursework
        -- etc.) cap every mark at 100 regardless of a course's own configured
        -- max mark, so the "out of" used for a generated mark is clamped the
        -- same way to guarantee the CHECK is always satisfied.
        CASE WHEN t.coursework_max_mark > 100 THEN 100 ELSE t.coursework_max_mark END AS cw_max,
        CASE WHEN t.midterm_max_mark    > 100 THEN 100 ELSE t.midterm_max_mark    END AS mt_max,
        CASE WHEN t.final_max_mark      > 100 THEN 100 ELSE t.final_max_mark      END AS fl_max,
        COALESCE(t.existing_coursework_mark,
            CAST((CASE WHEN t.coursework_max_mark > 100 THEN 100 ELSE t.coursework_max_mark END)
                 * (0.40 + RAND(CHECKSUM(NEWID())) * 0.60) AS DECIMAL(5,2))) AS cw_mark,
        COALESCE(t.existing_midterm_mark,
            CAST((CASE WHEN t.midterm_max_mark > 100 THEN 100 ELSE t.midterm_max_mark END)
                 * (0.40 + RAND(CHECKSUM(NEWID())) * 0.60) AS DECIMAL(5,2))) AS mt_mark,
        COALESCE(t.existing_lab_mark,
            CAST((CASE WHEN t.coursework_max_mark > 100 THEN 100 ELSE t.coursework_max_mark END)
                 * (0.40 + RAND(CHECKSUM(NEWID())) * 0.60) AS DECIMAL(5,2))) AS lab_mark,
        COALESCE(t.existing_final_mark,
            CAST((CASE WHEN t.final_max_mark > 100 THEN 100 ELSE t.final_max_mark END)
                 * (0.40 + RAND(CHECKSUM(NEWID())) * 0.60) AS DECIMAL(5,2))) AS fl_mark
    FROM @targets t
),
totaled AS (
    SELECT *,
        CAST(
            CASE WHEN has_lab = 1
                 THEN (lab_mark / cw_max * coursework_weight)
                    + (mt_mark  / mt_max * midterm_weight)
                    + (fl_mark  / fl_max * final_weight)
                 ELSE (cw_mark / cw_max * coursework_weight)
                    + (mt_mark / mt_max * midterm_weight)
                    + (fl_mark / fl_max * final_weight)
            END AS DECIMAL(5,2)
        ) AS total_mark
    FROM filled
),
lettered AS (
    SELECT *,
        -- Section 5.2 bands, highest first — the same table GradeCalculator.letterGrade walks.
        CASE
            WHEN total_mark >= 95 THEN N'A'
            WHEN total_mark >= 90 THEN N'A-'
            WHEN total_mark >= 85 THEN N'B+'
            WHEN total_mark >= 80 THEN N'B'
            WHEN total_mark >= 75 THEN N'B-'
            WHEN total_mark >= 70 THEN N'C+'
            WHEN total_mark >= 65 THEN N'C'
            WHEN total_mark >= 60 THEN N'C-'
            WHEN total_mark >= 55 THEN N'D+'
            WHEN total_mark >= 50 THEN N'D'
            ELSE N'F'
        END AS letter_grade
    FROM totaled
)
INSERT INTO @generated (enrollment_id, coursework_mark, midterm_mark, lab_mark, final_mark,
                         total_mark, letter_grade, grade_points, result_status)
SELECT
    enrollment_id,
    CASE WHEN has_lab = 0 THEN cw_mark END,
    mt_mark,
    CASE WHEN has_lab = 1 THEN lab_mark END,
    fl_mark,
    total_mark,
    letter_grade,
    -- LetterGrade.getGradePoints() — the same 11-band table, F reads 0.00.
    CAST(CASE letter_grade
        WHEN N'A'  THEN 4.00 WHEN N'A-' THEN 3.70 WHEN N'B+' THEN 3.30
        WHEN N'B'  THEN 3.00 WHEN N'B-' THEN 2.70 WHEN N'C+' THEN 2.30
        WHEN N'C'  THEN 2.00 WHEN N'C-' THEN 1.70 WHEN N'D+' THEN 1.30
        WHEN N'D'  THEN 1.00 ELSE 0.00
    END AS DECIMAL(3,2)),
    -- LetterGrade.toResultStatus() — never W/I here (those enrollments were
    -- excluded up front), so PASSED unless the letter is F.
    CASE WHEN letter_grade = N'F' THEN N'FAILED' ELSE N'PASSED' END
FROM lettered;

/* ---- fix the incomplete/unsubmitted rows that already exist ---- */
UPDATE g
SET coursework_mark = gen.coursework_mark,
    midterm_mark     = gen.midterm_mark,
    lab_mark         = gen.lab_mark,
    final_mark       = gen.final_mark,
    total_mark       = gen.total_mark,
    letter_grade     = gen.letter_grade,
    grade_points     = gen.grade_points,
    result_status    = gen.result_status,
    is_submitted     = 1,
    submitted_by     = ISNULL(g.submitted_by, t.submitted_by_user_id),
    submitted_at     = ISNULL(g.submitted_at, @now),
    -- Same flags Submit-and-Lock stamps (GradeService.submitSection ->
    -- GradeDAO.publishComponents(gradeId, !hasLab, true, hasLab, true)).
    coursework_published = CASE WHEN t.has_lab = 0 THEN 1 ELSE coursework_published END,
    midterm_published    = 1,
    lab_published        = CASE WHEN t.has_lab = 1 THEN 1 ELSE lab_published END,
    final_published      = 1,
    coursework_published_mark = CASE WHEN t.has_lab = 0 THEN gen.coursework_mark ELSE coursework_published_mark END,
    midterm_published_mark    = gen.midterm_mark,
    lab_published_mark        = CASE WHEN t.has_lab = 1 THEN gen.lab_mark ELSE lab_published_mark END,
    final_published_mark      = gen.final_mark
FROM dbo.grades g
JOIN @targets t ON t.grade_id = g.grade_id
JOIN @generated gen ON gen.enrollment_id = t.enrollment_id;

DECLARE @updatedCount INT = @@ROWCOUNT;

/* ---- create a grade row from scratch for enrollments that had none at all ---- */
INSERT INTO dbo.grades (enrollment_id, coursework_mark, midterm_mark, lab_mark, final_mark,
                         total_mark, letter_grade, grade_points, result_status, is_submitted,
                         submitted_by, submitted_at,
                         coursework_published, midterm_published, lab_published, final_published,
                         coursework_published_mark, midterm_published_mark, lab_published_mark, final_published_mark)
SELECT
    t.enrollment_id, gen.coursework_mark, gen.midterm_mark, gen.lab_mark, gen.final_mark,
    gen.total_mark, gen.letter_grade, gen.grade_points, gen.result_status, 1,
    t.submitted_by_user_id, @now,
    CASE WHEN t.has_lab = 0 THEN 1 ELSE 0 END, 1,
    CASE WHEN t.has_lab = 1 THEN 1 ELSE 0 END, 1,
    gen.coursework_mark, gen.midterm_mark, gen.lab_mark, gen.final_mark
FROM @targets t
JOIN @generated gen ON gen.enrollment_id = t.enrollment_id
WHERE t.grade_id IS NULL;

DECLARE @insertedCount INT = @@ROWCOUNT;

/* ---- rule G6: a submitted grade means the enrollment is COMPLETED ---- */
UPDATE e
SET e.status = N'COMPLETED'
FROM dbo.enrollments e
JOIN @targets t ON t.enrollment_id = e.enrollment_id
WHERE e.status = N'ENROLLED';

DECLARE @completedCount INT = @@ROWCOUNT;

/* ---- recompute the cache columns, only for students actually touched above ---- */
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
     WHERE s.student_id IN (SELECT DISTINCT student_id FROM @targets)
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

DECLARE @studentsRecomputed INT = @@ROWCOUNT;

PRINT N'0031: historical grade rows fixed (already existed, was incomplete/unsubmitted) = ' + CAST(@updatedCount AS NVARCHAR(10));
PRINT N'0031: historical grade rows created (enrollment had none at all) = ' + CAST(@insertedCount AS NVARCHAR(10));
PRINT N'0031: enrollments moved ENROLLED -> COMPLETED = ' + CAST(@completedCount AS NVARCHAR(10));
PRINT N'0031: students with cached GPA/credits/standing recomputed = ' + CAST(@studentsRecomputed AS NVARCHAR(10));

/* ---------------------------------------------------------------- verify */
SELECT COUNT(*) AS still_missing_a_valid_historical_grade
FROM dbo.enrollments e
JOIN dbo.sections sec ON sec.section_id = e.section_id
JOIN dbo.courses c ON c.course_id = sec.course_id
JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id
LEFT JOIN dbo.grades g ON g.enrollment_id = e.enrollment_id
WHERE e.status IN (N'ENROLLED', N'COMPLETED')
  AND sem.is_current = 0
  AND (
        g.grade_id IS NULL
     OR g.is_submitted = 0
     OR g.midterm_mark IS NULL
     OR g.final_mark IS NULL
     OR (c.has_lab = 1 AND g.lab_mark IS NULL)
     OR (c.has_lab = 0 AND g.coursework_mark IS NULL)
     OR g.total_mark IS NULL OR g.letter_grade IS NULL
     OR g.grade_points IS NULL OR g.result_status IS NULL
  );
GO
