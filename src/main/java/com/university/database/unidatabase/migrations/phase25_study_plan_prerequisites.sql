/* ============================================================================
   PHASE 25 MIGRATION — multi-prerequisite example, and Final
                        Project / Internship credit-based eligibility
   ----------------------------------------------------------------------------
   WHY THIS EXISTS

   Two Study Plan eligibility concepts the brief requires were not yet
   exercised anywhere after Phase 24 built the base curriculum:

   1. A course with MORE THAN ONE mandatory prerequisite, where ALL of them
      must be satisfied (not just one). ARTS192 (BSCS's Final Project row, see
      step 2) already required CS425; it now also requires CS337 (Algorithms
      Design, BSCS semester 6) — a real dependency for a capstone project, and
      both prerequisites sit in earlier semesters than ARTS192 itself.

   2. Final Project / Internship rows with a genuine credit-based eligibility
      condition (program_requirements.min_completed_credits, added in Phase
      23), each also given a course prerequisite already in the program's own
      plan — the compound "course passed AND minimum credits" case the brief
      gives as an example ("MATH201 passed and minimum 75 completed
      credits"). One such row is added per bachelor's program, in a late
      semester, reclassifying an existing seminar-style row already there
      (no new course, no credit-total change) to Final Project or Internship.
      MSCS is left untouched, consistent with Phase 24's reasoning that its
      four light semesters are the "exceptional" case, not a normal
      curriculum needing a capstone slot.

   Every prerequisite added here already exists in the same program's own
   curriculum, in an earlier recommended_semester — checked the same way
   Phase 24's own verification query checks it.

   SAFE TO RE-RUN: every INSERT is guarded by NOT EXISTS on the exact pair it
   would create, and the requirement_type/min_completed_credits UPDATEs are
   idempotent (setting the same value twice changes nothing). One transaction,
   rolls back on any error.
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
    -- Multi-prerequisite example: ARTS192 (BSCS's Final Project row, given its
    -- CS425 prerequisite below in step 2) also requires CS337. ARTS192 is used by
    -- BSCS alone (checked before writing this script), so unlike CS425 — which
    -- MSCS's own curriculum also includes, without MATH131 — adding a second
    -- prerequisite here cannot leave any program's own plan missing it.
    PRINT N'1. Adding second prerequisite (CS337) to ARTS192.';
    INSERT INTO dbo.course_prerequisites (course_id, prerequisite_course_id, min_grade_points)
    SELECT c.course_id, p.course_id, 1.00
      FROM dbo.courses c, dbo.courses p
     WHERE c.course_code = N'ARTS192' AND p.course_code = N'CS337'
       AND NOT EXISTS (SELECT 1 FROM dbo.course_prerequisites cp
                        WHERE cp.course_id = c.course_id AND cp.prerequisite_course_id = p.course_id);

    -- ---------------------------------------------------------------- 2.
    -- Final Project / Internship rows: reclassify one existing row per
    -- bachelor's program, add its credit threshold, add its course prerequisite.
    PRINT N'2. Final Project / Internship rows: requirement_type, min_completed_credits, prerequisite.';

    ;WITH finalrows AS (
        SELECT * FROM (VALUES
            (N'BSCS',   N'ARTS192', N'Final Project', 100, N'CS425'),
            (N'BSIT',   N'ITU123',  N'Final Project',  95, N'IT451'),
            (N'BBA',    N'BUU123',  N'Final Project',  90, N'BUS456'),
            (N'BSCE',   N'ENU114',  N'Final Project', 110, N'ENG443'),
            (N'BSEE',   N'ENU114',  N'Final Project', 110, N'ENG443'),
            (N'BSMATH', N'SCU123',  N'Final Project',  90, N'MATH453'),
            (N'BAENG',  N'ARU123',  N'Final Project',  90, N'ARTS451'),
            (N'LLB',    N'LWU123',  N'Final Project', 130, N'LWU111'),
            (N'BPHARM', N'PHU113',  N'Internship',    110, N'PHR353'),
            (N'BPHARM', N'PHU117',  N'Internship',    140, N'PHR457')
        ) AS v(program_code, course_code, reqtype, min_credits, prereq_code)
    )
    UPDATE pr
       SET requirement_type = fr.reqtype,
           is_mandatory = 1,
           min_completed_credits = fr.min_credits
      FROM dbo.program_requirements pr
      JOIN dbo.programs p ON p.program_id = pr.program_id
      JOIN dbo.courses c  ON c.course_id  = pr.course_id
      JOIN finalrows fr ON fr.program_code = p.program_code AND fr.course_code = c.course_code
     WHERE pr.requirement_type <> fr.reqtype OR pr.min_completed_credits IS NULL OR pr.min_completed_credits <> fr.min_credits;

    PRINT N'   rows reclassified: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

    ;WITH finalrows AS (
        SELECT * FROM (VALUES
            (N'BSCS',   N'ARTS192', N'CS425'),
            (N'BSIT',   N'ITU123',  N'IT451'),
            (N'BBA',    N'BUU123',  N'BUS456'),
            (N'BSCE',   N'ENU114',  N'ENG443'),
            (N'BSEE',   N'ENU114',  N'ENG443'),
            (N'BSMATH', N'SCU123',  N'MATH453'),
            (N'BAENG',  N'ARU123',  N'ARTS451'),
            (N'LLB',    N'LWU123',  N'LWU111'),
            (N'BPHARM', N'PHU113',  N'PHR353'),
            (N'BPHARM', N'PHU117',  N'PHR457')
        ) AS v(program_code, course_code, prereq_code)
    )
    INSERT INTO dbo.course_prerequisites (course_id, prerequisite_course_id, min_grade_points)
    SELECT DISTINCT c.course_id, p.course_id, 1.00
      FROM finalrows fr
      JOIN dbo.courses c ON c.course_code = fr.course_code
      JOIN dbo.courses p ON p.course_code = fr.prereq_code
     WHERE NOT EXISTS (SELECT 1 FROM dbo.course_prerequisites cp
                        WHERE cp.course_id = c.course_id AND cp.prerequisite_course_id = p.course_id);

    PRINT N'   prerequisite rows added: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

COMMIT TRANSACTION;
PRINT N'--- Phase 25 migration committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0
        ROLLBACK TRANSACTION;
    PRINT N'!!! Phase 25 migration ROLLED BACK — nothing was changed. !!!';
    THROW;
END CATCH
GO

/* ---------------------------------------------------------------- verify */
SELECT p.program_code, c.course_code, pr.requirement_type, pr.min_completed_credits
  FROM dbo.program_requirements pr
  JOIN dbo.programs p ON p.program_id = pr.program_id
  JOIN dbo.courses c  ON c.course_id  = pr.course_id
 WHERE pr.requirement_type IN (N'Final Project', N'Internship')
 ORDER BY p.program_code;

SELECT cdep.course_code AS course, cpre.course_code AS prerequisite
  FROM dbo.course_prerequisites cp
  JOIN dbo.courses cdep ON cdep.course_id = cp.course_id
  JOIN dbo.courses cpre ON cpre.course_id = cp.prerequisite_course_id
 WHERE cdep.course_code = N'ARTS192';
GO
