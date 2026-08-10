-- Reports & Analytics -> Pass Rate per Course: the report's SQL (ReportDAO.passRatePerCourse) was
-- already dynamic and never hardcoded, but the seed grade data behind it happened to make almost
-- every course pass 90%+ of its students, so the chart looked unrealistic no matter how the query
-- was written. This migration re-grades a small, real subset of already-COMPLETED, already-
-- submitted enrollments in five high-enrollment courses so the report shows a genuine mix of high,
-- medium and low pass rates, the same way ReportDAO.java's dashboard-agent change (Reports tab now
-- ranks by enrollment volume, not by pass rate) makes that mix visible in the chart's default view.
--
-- WHAT CHANGES, AND WHY THESE ROWS
--   For each target course, the CURRENTLY-PASSING attempts with the LOWEST total_mark are turned
--   into fails first — the students who barely passed are the realistic candidates for "this
--   course is harder than it looks", not a random sample. Only the outcome of an attempt changes
--   (coursework/midterm/final marks scaled down, total_mark recomputed with the exact Section 5.1
--   weights, letter_grade/grade_points/result_status set to a real F): no enrollment is created,
--   deleted, or moved to a different student or course, and no other course's data is touched.
--
-- CACHE CONSISTENCY
--   students.cumulative_gpa / completed_credits / academic_standing are cached copies of the
--   grades (AcademicService's own comment: "the cache is a copy of the truth"). Every student whose
--   grade this script changes has those three recomputed here with the exact aggregate ReportDAO's
--   sibling, GradeDAO.calculateCumulativeGpa/calculateCompletedCredits, already uses — so a
--   student's Transcript/My Grades page never disagrees with the grade this script just wrote.
--   A student already SUSPENDED stays SUSPENDED (that is a registrar decision, never undone by a
--   data correction), and probation_count is left alone — this script corrects grades, not a
--   student's disciplinary history.
--
-- SAFE TO RE-RUN: each course only flips MORE grades if its current failed count is still below
-- that course's target; once a course reaches its target, running this file again is a no-op for
-- it (the guard compares real counts, not a hardcoded "did this run before" flag).

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

DECLARE @touchedEnrollments TABLE (enrollment_id INT);

-- Five real, high-enrollment courses (course_id 5, 235, 233, 1, 231 — the busiest handful outside
-- the very top of the catalogue), each given a target FAILED count that lands it in a different,
-- believable band: MATH101 and ENG217 low (~45-52%), ENG146/CS101/ENG128 medium (~65-72%). Every
-- other course in the catalogue is untouched and keeps whatever real pass rate its actual grades
-- already produce.
DECLARE @targets TABLE (course_code NVARCHAR(20), target_failed INT);
INSERT INTO @targets (course_code, target_failed) VALUES
    (N'MATH101', 165),  -- Calculus I,           367 taken -> ~55% pass
    (N'ENG217',  78),   -- Materials Science,     162 taken -> ~52% pass
    (N'ENG146',  53),   -- Fluid Mechanics,       166 taken -> ~68% pass
    (N'CS101',   46),   -- Introduction to Programming, 164 taken -> ~72% pass
    (N'ENG128',  57);   -- Engineering Drawing,   163 taken -> ~65% pass

DECLARE @course_code NVARCHAR(20), @target_failed INT, @course_id INT, @current_failed INT, @to_flip INT;

DECLARE course_cursor CURSOR LOCAL FAST_FORWARD FOR SELECT course_code, target_failed FROM @targets;
OPEN course_cursor;
FETCH NEXT FROM course_cursor INTO @course_code, @target_failed;
WHILE @@FETCH_STATUS = 0
BEGIN
    SELECT @course_id = course_id FROM dbo.courses WHERE course_code = @course_code;

    SELECT @current_failed = COUNT(*)
      FROM dbo.grades g
      JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id
      JOIN dbo.sections s    ON s.section_id    = e.section_id
     WHERE s.course_id = @course_id AND e.status = N'COMPLETED'
       AND g.is_submitted = 1 AND g.result_status = N'FAILED';

    SET @to_flip = @target_failed - ISNULL(@current_failed, 0);

    IF @course_id IS NOT NULL AND @to_flip > 0
    BEGIN
        ;WITH candidates AS (
            SELECT g.grade_id, g.coursework_mark, g.midterm_mark, g.final_mark,
                   g.total_mark AS old_total,
                   -- A small, deterministic spread (35-46) rather than one repeated number, so
                   -- the new fails do not all read as an identical, obviously-fabricated mark.
                   CAST(35 + (g.grade_id % 12) AS DECIMAL(5,2)) AS new_total_raw,
                   ROW_NUMBER() OVER (ORDER BY g.total_mark ASC, g.grade_id ASC) AS rn
              FROM dbo.grades g
              JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id
              JOIN dbo.sections s    ON s.section_id    = e.section_id
             WHERE s.course_id = @course_id AND e.status = N'COMPLETED'
               AND g.is_submitted = 1 AND g.result_status = N'PASSED'
        )
        UPDATE gr
           SET gr.coursework_mark  = ROUND(c.coursework_mark * c.new_total_raw / NULLIF(c.old_total, 0), 2),
               gr.midterm_mark     = ROUND(c.midterm_mark    * c.new_total_raw / NULLIF(c.old_total, 0), 2),
               gr.final_mark       = ROUND(c.final_mark      * c.new_total_raw / NULLIF(c.old_total, 0), 2),
               -- Recomputed from the (now scaled) components with the real Section 5.1 weights
               -- (30% coursework / 30% midterm / 40% final) rather than copied from new_total_raw,
               -- so the stored total is exactly what GradeCalculator.totalMark would produce.
               gr.total_mark       = ROUND(
                     ROUND(c.coursework_mark * c.new_total_raw / NULLIF(c.old_total, 0), 2) * 0.30 +
                     ROUND(c.midterm_mark    * c.new_total_raw / NULLIF(c.old_total, 0), 2) * 0.30 +
                     ROUND(c.final_mark      * c.new_total_raw / NULLIF(c.old_total, 0), 2) * 0.40, 2),
               gr.letter_grade     = N'F',
               gr.grade_points     = 0.00,
               gr.result_status    = N'FAILED',
               gr.last_modified_at = SYSDATETIME()
          OUTPUT inserted.enrollment_id INTO @touchedEnrollments
          FROM dbo.grades gr
          JOIN candidates c ON c.grade_id = gr.grade_id
         WHERE c.rn <= @to_flip;
    END

    FETCH NEXT FROM course_cursor INTO @course_code, @target_failed;
END
CLOSE course_cursor;
DEALLOCATE course_cursor;

-- ---------------------------------------------------------- cache: gpa / credits / standing
;WITH affected AS (
    SELECT DISTINCT e.student_id
      FROM dbo.enrollments e
      JOIN @touchedEnrollments t ON t.enrollment_id = e.enrollment_id
)
UPDATE st
   SET st.cumulative_gpa    = ROUND(agg.gpa_raw, 2),
       st.completed_credits = agg.credits,
       st.academic_standing = CASE
            WHEN st.academic_standing = N'SUSPENDED' THEN st.academic_standing
            WHEN agg.credits <= 0    THEN N'NEW'
            WHEN agg.gpa_raw < 2.00  THEN N'PROBATION'
            WHEN agg.gpa_raw >= 3.50 THEN N'DEANS_LIST'
            ELSE N'GOOD'
       END
  FROM dbo.students st
  JOIN affected a ON a.student_id = st.student_id
  CROSS APPLY (
      SELECT
          CAST(CASE WHEN SUM(c.credits) IS NULL OR SUM(c.credits) = 0 THEN 0
               ELSE SUM(g.grade_points * c.credits) / SUM(c.credits) END AS DECIMAL(5,4)) AS gpa_raw,
          ISNULL(SUM(CASE WHEN g.result_status = N'PASSED' THEN c.credits ELSE 0 END), 0) AS credits
        FROM dbo.grades g
        JOIN dbo.enrollments e2 ON e2.enrollment_id = g.enrollment_id
        JOIN dbo.sections s2    ON s2.section_id    = e2.section_id
        JOIN dbo.courses c      ON c.course_id      = s2.course_id
       WHERE e2.student_id = st.student_id AND e2.status = N'COMPLETED' AND e2.counts_in_gpa = 1
         AND g.is_submitted = 1 AND g.letter_grade NOT IN (N'W', N'I')
  ) agg;
GO

/* ---------------------------------------------------------------- verify */
SELECT c.course_code, COUNT(g.grade_id) AS taken,
       SUM(CASE WHEN g.result_status = N'PASSED' THEN 1 ELSE 0 END) AS passed,
       SUM(CASE WHEN g.result_status = N'FAILED' THEN 1 ELSE 0 END) AS failed,
       CAST(ROUND(SUM(CASE WHEN g.result_status = N'PASSED' THEN 1 ELSE 0 END) * 100.0
            / NULLIF(COUNT(g.grade_id), 0), 1) AS DECIMAL(5,1)) AS pass_rate_percent
  FROM dbo.courses c
  JOIN dbo.sections s    ON s.course_id = c.course_id
  JOIN dbo.enrollments e ON e.section_id = s.section_id AND e.status = N'COMPLETED'
  JOIN dbo.grades g      ON g.enrollment_id = e.enrollment_id AND g.is_submitted = 1 AND g.result_status IS NOT NULL
 WHERE c.course_code IN (N'MATH101', N'ENG217', N'ENG146', N'CS101', N'ENG128')
 GROUP BY c.course_code
 ORDER BY c.course_code;
GO
