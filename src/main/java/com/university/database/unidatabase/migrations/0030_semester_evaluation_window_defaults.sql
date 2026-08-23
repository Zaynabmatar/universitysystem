/* ==========================================================================
   0030 - give every semester an automatic instructor evaluation window.
   --------------------------------------------------------------------------
   Section: Semester management / Instructor Evaluation window.

   Until now evaluation_start/evaluation_end (added by 0016) were only ever
   set when an Admin filled them in by hand on the Add/Edit Semester dialog,
   so most semesters - historical, current, and future-scheduled - carry
   NULL there. NULL is why the "grade auto-releases after the evaluation
   deadline" behaviour in GradeDAO.findStudentGradeRows never actually fires
   for them: its check is `sem.evaluation_end IS NOT NULL AND
   SYSDATETIME() > sem.evaluation_end`.

   This migration (together with SemesterService, same commit, for every
   semester created or edited from here on) gives every row a window:
     evaluation_start = start_date
     evaluation_end   = end_date + 21 days
   pulled in earlier when the NEXT semester (by start_date) already begins
   before that 21-day mark, so two semesters' evaluation windows never
   overlap - the same rule SemesterService now enforces on every save.

   Only rows that are actually missing a window, or whose stored
   evaluation_end/evaluation_start no longer satisfies that rule, are
   touched - an evaluation window an Admin already set by hand that still
   fits within the new bounds is left exactly as it is.

   SAFE TO RE-RUN: every row is recomputed the same way; a second run finds
   nothing left to change.
   ========================================================================== */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

WITH bounds AS (
    SELECT
        s.semester_id,
        CAST(s.start_date AS DATETIME2)                       AS default_start,
        -- End of the 21st day after end_date (23:59:59), matching
        -- SemesterService.maxEvaluationEnd's Semester::getEndDate().plusDays(21).atTime(23,59,59).
        DATEADD(SECOND, -1, DATEADD(DAY, 22, CAST(s.end_date AS DATETIME2))) AS hard_cap_end,
        (SELECT MIN(o.start_date) FROM dbo.semesters o
          WHERE o.start_date > s.end_date)                     AS next_start
    FROM dbo.semesters s
),
targets AS (
    SELECT
        b.semester_id,
        b.default_start,
        CASE
            WHEN b.next_start IS NOT NULL
                 AND DATEADD(SECOND, -1, CAST(b.next_start AS DATETIME2)) < b.hard_cap_end
                THEN DATEADD(SECOND, -1, CAST(b.next_start AS DATETIME2))
            ELSE b.hard_cap_end
        END AS max_end
    FROM bounds b
)
UPDATE sem
SET
    evaluation_end = CASE
        WHEN sem.evaluation_end IS NULL THEN t.max_end
        WHEN sem.evaluation_end > t.max_end THEN t.max_end
        ELSE sem.evaluation_end
    END,
    evaluation_start = CASE
        WHEN sem.evaluation_start IS NULL THEN t.default_start
        WHEN sem.evaluation_start >= (CASE
                WHEN sem.evaluation_end IS NULL THEN t.max_end
                WHEN sem.evaluation_end > t.max_end THEN t.max_end
                ELSE sem.evaluation_end END)
            THEN t.default_start
        ELSE sem.evaluation_start
    END
FROM dbo.semesters sem
JOIN targets t ON t.semester_id = sem.semester_id
WHERE sem.evaluation_start IS NULL
   OR sem.evaluation_end IS NULL
   OR sem.evaluation_end > t.max_end
   OR sem.evaluation_start >= (CASE
        WHEN sem.evaluation_end IS NULL THEN t.max_end
        WHEN sem.evaluation_end > t.max_end THEN t.max_end
        ELSE sem.evaluation_end END);
GO

/* ---------------------------------------------------------------- verify */
SELECT semester_id, semester_name, start_date, end_date, evaluation_start, evaluation_end
FROM dbo.semesters
ORDER BY start_date;
GO
