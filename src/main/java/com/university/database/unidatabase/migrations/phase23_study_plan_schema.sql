/* ============================================================================
   PHASE 23 MIGRATION — Study Plan schema: requirement types, credit-based
                        eligibility, longer program durations, and the new
                        curriculum courses the Study Plan needs
   ----------------------------------------------------------------------------
   WHY THIS EXISTS

   This is the first migration of the Study Plan phase. The database foundation
   phase (phase18-phase22) is COMPLETE and is not touched here — this migration
   only adds what Study Plan genuinely cannot represent with the existing
   schema, on top of it.

   1. dbo.program_requirements has only a boolean is_mandatory — no way to
      distinguish "Mandatory" from "Internship" from "Final Project" from
      "University Requirement" etc. as the brief's Study Plan requires. A
      requirement_type column is added. is_mandatory is kept exactly as it is
      (nothing reads it differently) so RecommendationDAO and every existing
      caller keep working unchanged; requirement_type is additive.

   2. Some Study Plan eligibility rules are credit-based, not course-based
      ("minimum 90 completed credits" for a Final Project) — course_prerequisites
      only models course-to-course relationships. min_completed_credits is
      added to program_requirements: NULL for every ordinary course, set only
      on the handful of rows that carry a credit threshold.

   3. recommended_semester is CHECKed to 1-8. Two programs need more real
      semesters to keep every normal semester at or under 18 credits without
      inventing artificial ones: Bachelor of Laws (150 credits) needs 9,
      BSc Pharmacy (160 credits) needs 9. The check is widened to 1-10 —
      enough for both, not open-ended.

   4. One pre-existing data defect, found while building the Study Plan
      curriculum: course ART667 ("analog electrinics", dept_id 14 = Engineering)
      has a code that does not match the department's own ENG### numbering and
      a misspelled, lowercased title. It is used by both BSCE and BSEE and is
      about to be shown to students inside their Study Plan, so its code and
      title are corrected here (course_id is unchanged, so no foreign key is
      affected).

   5. Every new course the Study Plan curriculum needs (Phase 24) is inserted
      here first, so Phase 24 only ever references course_ids that already
      exist. These are short (1-2 credit) seminar/workshop/lab/practicum
      courses plus a small number of full 3-credit courses — the low-credit
      ones exist specifically so a normal semester can reach exactly 7 real
      courses without exceeding 18 credits (Section 5 of the brief), never as
      fake placeholders: each has a real, distinct, academically ordinary
      title and is referenced by a real program_requirements row in Phase 24.

   SAFE TO RE-RUN: every ALTER is guarded, the course inserts are guarded by
   course_code, and the whole thing runs in one transaction that rolls back on
   any error.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

/* ------------------------------------------------------------------ 1a.
   Adding a column and referencing it (even in a CHECK constraint on the
   same table) cannot happen in the same batch — the batch is compiled as a
   whole before any of it runs, and the new column does not exist yet as far
   as that compilation is concerned. Phase 17 hit exactly this; each ADD
   COLUMN here gets its own batch, same as that one did. DDL outside an
   explicit transaction commits immediately, so by the time the BEGIN
   TRANSACTION below starts, every column referenced inside it already
   exists for real. */
IF COL_LENGTH('dbo.program_requirements', 'requirement_type') IS NULL
BEGIN
    PRINT N'1a. Adding program_requirements.requirement_type.';
    ALTER TABLE dbo.program_requirements
        ADD requirement_type NVARCHAR(40) NOT NULL
            CONSTRAINT DF_program_requirements_reqtype DEFAULT (N'Mandatory');
END
ELSE
    PRINT N'1a. program_requirements.requirement_type already exists - nothing to add.';
GO

IF COL_LENGTH('dbo.program_requirements', 'min_completed_credits') IS NULL
BEGIN
    PRINT N'1b. Adding program_requirements.min_completed_credits.';
    ALTER TABLE dbo.program_requirements ADD min_completed_credits INT NULL;
END
ELSE
    PRINT N'1b. program_requirements.min_completed_credits already exists - nothing to add.';
GO

BEGIN TRY
BEGIN TRANSACTION;

    -- ---------------------------------------------------------------- 1.
    IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_program_requirements_reqtype')
    BEGIN
        PRINT N'1. Adding CK_program_requirements_reqtype and backfilling from is_mandatory.';
        ALTER TABLE dbo.program_requirements
            ADD CONSTRAINT CK_program_requirements_reqtype CHECK (requirement_type IN (
                N'Mandatory', N'Program Elective', N'University Requirement',
                N'Department Requirement', N'Free Elective', N'Internship', N'Final Project'));

        -- Backfill from the existing is_mandatory flag so no row is left at the
        -- bare default once real classification is decided in Phase 24.
        UPDATE dbo.program_requirements SET requirement_type = N'Mandatory' WHERE is_mandatory = 1;
        UPDATE dbo.program_requirements SET requirement_type = N'Free Elective' WHERE is_mandatory = 0;
    END
    ELSE
        PRINT N'1. CK_program_requirements_reqtype already exists - nothing to add.';

    -- ---------------------------------------------------------------- 2.
    IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_program_requirements_mincredits')
    BEGIN
        PRINT N'2. Adding CK_program_requirements_mincredits.';
        ALTER TABLE dbo.program_requirements
            ADD CONSTRAINT CK_program_requirements_mincredits
                CHECK (min_completed_credits IS NULL OR min_completed_credits >= 0);
    END
    ELSE
        PRINT N'2. CK_program_requirements_mincredits already exists - nothing to add.';

    -- ---------------------------------------------------------------- 3.
    IF EXISTS (SELECT 1 FROM sys.check_constraints
                WHERE name = 'CK_program_requirements_semester'
                  AND definition NOT LIKE '%10%')
    BEGIN
        PRINT N'3. Widening CK_program_requirements_semester from 1-8 to 1-10.';
        ALTER TABLE dbo.program_requirements DROP CONSTRAINT CK_program_requirements_semester;
        ALTER TABLE dbo.program_requirements
            ADD CONSTRAINT CK_program_requirements_semester CHECK (recommended_semester BETWEEN 1 AND 10);
    END
    ELSE
        PRINT N'3. CK_program_requirements_semester already allows up to 10 - nothing to do.';

    -- ------------------------------------------------------------- 3b.
    -- courses.level_year is CHECKed to 1-4 (a four-year-degree assumption).
    -- The new Law/Pharmacy courses below are placed in their course's real
    -- academic year, which for a 9-semester program runs past year 4.
    IF EXISTS (SELECT 1 FROM sys.check_constraints
                WHERE name = 'CK_courses_level_year'
                  AND definition NOT LIKE '%10%')
    BEGIN
        PRINT N'3b. Widening CK_courses_level_year from 1-4 to 1-10.';
        ALTER TABLE dbo.courses DROP CONSTRAINT CK_courses_level_year;
        ALTER TABLE dbo.courses ADD CONSTRAINT CK_courses_level_year CHECK (level_year BETWEEN 1 AND 10);
    END
    ELSE
        PRINT N'3b. CK_courses_level_year already allows up to 10 - nothing to do.';

    -- ---------------------------------------------------------------- 4.
    IF EXISTS (SELECT 1 FROM dbo.courses WHERE course_code = N'ART667')
    BEGIN
        PRINT N'4. Correcting course ART667 -> ENG267 (Analog Electronics).';
        UPDATE dbo.courses
           SET course_code = N'ENG267', course_title = N'Analog Electronics'
         WHERE course_code = N'ART667';
    END
    ELSE
        PRINT N'4. ART667 already corrected - nothing to do.';

    -- ---------------------------------------------------------------- 5.
    PRINT N'5. Inserting new Study Plan curriculum courses (guarded by course_code).';

    ;WITH new_courses AS (
        SELECT * FROM (VALUES
        -- ARTS: shared university-requirement seminars/workshops used by BSCS
        (N'ARTS161', N'University Seminar I',            1, N'ARTS', 1),
        (N'ARTS162', N'Study Skills Workshop',            1, N'ARTS', 1),
        (N'ARTS163', N'Digital Literacy Workshop',        1, N'ARTS', 1),
        (N'ARTS164', N'Academic Writing Lab',             1, N'ARTS', 1),
        (N'ARTS171', N'Critical Thinking Seminar',        1, N'ARTS', 2),
        (N'ARTS172', N'Research Methods Workshop',        2, N'ARTS', 2),
        (N'ARTS174', N'Data Literacy Workshop',           2, N'ARTS', 2),
        (N'ARTS181', N'Professional Ethics Seminar',      1, N'ARTS', 3),
        (N'ARTS182', N'Communication Skills Workshop',    2, N'ARTS', 3),
        (N'ARTS183', N'Leadership Seminar',               1, N'ARTS', 3),
        (N'ARTS191', N'Career Development Seminar',       1, N'ARTS', 4),
        (N'ARTS192', N'Capstone Preparation Workshop',    2, N'ARTS', 4),
        (N'ARTS193', N'Global Citizenship Seminar',       1, N'ARTS', 4),
        (N'ARTS194', N'Sustainability Workshop',          1, N'ARTS', 4),
        (N'ARTS201', N'University Seminar II',            1, N'ARTS', 1),
        (N'ARTS202', N'Wellness and Life Skills Seminar', 1, N'ARTS', 1),

        -- IT: BSIT-specific labs/seminars/workshops
        (N'ITU101', N'IT Skills Lab I',                    1, N'IT', 1),
        (N'ITU102', N'Technical Writing for IT Workshop',  2, N'IT', 1),
        (N'ITU103', N'Networking Practicum I',             1, N'IT', 2),
        (N'ITU104', N'Systems Support Seminar',            1, N'IT', 2),
        (N'ITU105', N'IT Troubleshooting Workshop',        2, N'IT', 2),
        (N'ITU106', N'Cloud Skills Lab',                   1, N'IT', 3),
        (N'ITU107', N'Security Awareness Seminar',         1, N'IT', 3),
        (N'ITU108', N'Business Analytics Workshop',        2, N'IT', 3),
        (N'ITU109', N'Automation Practicum',               1, N'IT', 4),
        (N'ITU110', N'IT Governance Seminar',              1, N'IT', 4),
        (N'ITU111', N'Systems Integration Workshop',       2, N'IT', 4),
        (N'ITU112', N'Career Readiness Seminar for IT',    1, N'IT', 4),
        (N'ITU113', N'IT Capstone Preparation Seminar',    1, N'IT', 4),
        (N'ITU114', N'Emerging Technologies Workshop',     2, N'IT', 4),
        (N'ITU115', N'Professional Certification Seminar', 1, N'IT', 4),
        (N'ITU116', N'IT Ethics Seminar',                  1, N'IT', 4),
        (N'ITU117', N'Enterprise Systems Workshop',        2, N'IT', 4),
        (N'ITU118', N'IT Leadership Seminar',              1, N'IT', 4),
        (N'ITU119', N'Technical Documentation Seminar',    1, N'IT', 4),
        (N'ITU120', N'IT Innovation Seminar',              1, N'IT', 4),
        (N'ITU121', N'Quality Assurance Seminar',          1, N'IT', 4),
        (N'ITU122', N'IT Project Practicum',               1, N'IT', 4),
        (N'ITU123', N'Senior IT Seminar',                  1, N'IT', 4),

        -- BUS: BBA-specific seminars
        (N'BUU101', N'Business Communication Lab',        1, N'BUS', 1),
        (N'BUU102', N'Professional Skills Workshop',       1, N'BUS', 1),
        (N'BUU103', N'Business Ethics Seminar',            1, N'BUS', 2),
        (N'BUU104', N'Analytical Skills Workshop',         1, N'BUS', 2),
        (N'BUU105', N'Teamwork Seminar',                   1, N'BUS', 2),
        (N'BUU106', N'Business Research Workshop',         1, N'BUS', 3),
        (N'BUU107', N'Negotiation Skills Seminar',         1, N'BUS', 3),
        (N'BUU108', N'Case Study Practicum',               1, N'BUS', 3),
        (N'BUU109', N'Business Plan Workshop',             1, N'BUS', 4),
        (N'BUU110', N'Corporate Governance Seminar',       1, N'BUS', 4),
        (N'BUU111', N'Career Development Seminar for Business', 1, N'BUS', 4),
        (N'BUU112', N'Business Capstone Preparation Seminar', 1, N'BUS', 5),
        (N'BUU113', N'Industry Practicum Seminar',         1, N'BUS', 5),
        (N'BUU114', N'Data-Driven Decision Making Workshop', 1, N'BUS', 5),
        (N'BUU115', N'Global Business Seminar',            1, N'BUS', 6),
        (N'BUU116', N'Digital Marketing Workshop',         1, N'BUS', 6),
        (N'BUU117', N'Business Law Seminar',               1, N'BUS', 6),
        (N'BUU118', N'Financial Literacy Workshop',        1, N'BUS', 7),
        (N'BUU119', N'Strategic Thinking Seminar',         1, N'BUS', 7),
        (N'BUU120', N'Senior Business Seminar',            1, N'BUS', 7),
        (N'BUU121', N'Business Ethics Capstone Seminar',   1, N'BUS', 8),
        (N'BUU122', N'Entrepreneurial Practicum',          1, N'BUS', 8),
        (N'BUU123', N'Investment Readiness Seminar',       1, N'BUS', 8),

        -- ENG: shared by BSCE and BSEE
        (N'ENU101', N'Engineering Design Project I',       3, N'ENG', 1),
        (N'ENU102', N'Engineering Practice Workshop',      2, N'ENG', 1),
        (N'ENU103', N'Technical Drawing Lab',              1, N'ENG', 2),
        (N'ENU104', N'Engineering Ethics Seminar',         1, N'ENG', 2),
        (N'ENU105', N'Applied Engineering Lab',            2, N'ENG', 3),
        (N'ENU106', N'Engineering Design Project II',      1, N'ENG', 3),
        (N'ENU107', N'Engineering Standards Seminar',      1, N'ENG', 4),
        (N'ENU108', N'Field Engineering Practicum',        1, N'ENG', 4),
        (N'ENU109', N'Engineering Economics Workshop',     2, N'ENG', 5),
        (N'ENU110', N'Sustainable Engineering Seminar',    1, N'ENG', 5),
        (N'ENU111', N'Engineering Management Workshop',    1, N'ENG', 6),
        (N'ENU112', N'Engineering Research Seminar',       1, N'ENG', 6),
        (N'ENU113', N'Engineering Capstone Workshop',      2, N'ENG', 7),
        (N'ENU114', N'Senior Engineering Seminar',         1, N'ENG', 7),

        -- SCI: BSMATH-specific
        (N'SCU101', N'Mathematical Problem-Solving Lab',   1, N'SCI', 1),
        (N'SCU102', N'Scientific Writing Workshop',        1, N'SCI', 1),
        (N'SCU103', N'Mathematics Software Lab',           1, N'SCI', 2),
        (N'SCU104', N'Statistical Reasoning Seminar',      1, N'SCI', 2),
        (N'SCU105', N'Applied Mathematics Workshop',       1, N'SCI', 3),
        (N'SCU106', N'Mathematical Modelling Lab',         1, N'SCI', 3),
        (N'SCU107', N'Research Seminar in Mathematics',    1, N'SCI', 4),
        (N'SCU108', N'Mathematics Capstone Workshop',      1, N'SCI', 4),
        (N'SCU109', N'Career Seminar for Mathematicians',  1, N'SCI', 5),
        (N'SCU110', N'Quantitative Reasoning Workshop',    1, N'SCI', 5),
        (N'SCU111', N'Analytical Methods Seminar',         1, N'SCI', 6),
        (N'SCU112', N'Mathematics Teaching Practicum',     1, N'SCI', 6),
        (N'SCU113', N'Advanced Problem-Solving Seminar',   1, N'SCI', 7),
        (N'SCU114', N'Senior Mathematics Seminar',         1, N'SCI', 7),
        (N'SCU115', N'Mathematics Ethics Seminar',         1, N'SCI', 8),
        (N'SCU116', N'Mathematics Capstone Seminar II',    1, N'SCI', 8),
        (N'SCU117', N'Numerical Computing Workshop',       1, N'SCI', 8),

        -- ARTS (BAENG-specific, distinct codes from the BSCS shared pool)
        (N'ARU101', N'Literary Studies Lab',               1, N'ARTS', 1),
        (N'ARU102', N'Language Skills Workshop',           1, N'ARTS', 1),
        (N'ARU103', N'Literary Criticism Seminar',         1, N'ARTS', 2),
        (N'ARU104', N'Comparative Studies Workshop',       1, N'ARTS', 2),
        (N'ARU105', N'Research Writing Lab',               1, N'ARTS', 3),
        (N'ARU106', N'Cultural Analysis Seminar',          1, N'ARTS', 3),
        (N'ARU107', N'Translation Practicum',              1, N'ARTS', 4),
        (N'ARU108', N'Literature Capstone Workshop',       1, N'ARTS', 4),
        (N'ARU109', N'Career Seminar for Humanities',      1, N'ARTS', 5),
        (N'ARU110', N'Editing and Publishing Workshop',    1, N'ARTS', 5),
        (N'ARU111', N'Rhetoric and Persuasion Seminar',    1, N'ARTS', 6),
        (N'ARU112', N'Digital Humanities Workshop',        1, N'ARTS', 6),
        (N'ARU113', N'Senior Thesis Preparation Seminar',  1, N'ARTS', 7),
        (N'ARU114', N'Literature Ethics Seminar',          1, N'ARTS', 7),
        (N'ARU115', N'Capstone Writing Seminar',           1, N'ARTS', 8),
        (N'ARU116', N'Public Humanities Seminar',          1, N'ARTS', 8),
        (N'ARU117', N'Language and Society Workshop',      1, N'ARTS', 8),

        -- LAW: LLB-specific
        (N'LWU101', N'Legal Skills Lab I',                 2, N'LAW', 1),
        (N'LWU102', N'Legal Writing Workshop',             2, N'LAW', 1),
        (N'LWU103', N'Moot Court Practicum I',             1, N'LAW', 2),
        (N'LWU104', N'Legal Ethics Seminar',               1, N'LAW', 2),
        (N'LWU105', N'Legal Research Workshop',            1, N'LAW', 3),
        (N'LWU106', N'Moot Court Practicum II',            1, N'LAW', 3),
        (N'LWU107', N'Legal Drafting Workshop',            1, N'LAW', 4),
        (N'LWU108', N'Client Interviewing Seminar',        1, N'LAW', 4),
        (N'LWU109', N'Legal Clinic Practicum I',           2, N'LAW', 5),
        (N'LWU110', N'Arbitration Skills Seminar',         1, N'LAW', 5),
        (N'LWU111', N'Legal Clinic Practicum II',          2, N'LAW', 6),
        (N'LWU112', N'Negotiation Seminar',                2, N'LAW', 6),
        (N'LWU113', N'Legal Clinic Practicum III',         1, N'LAW', 6),
        (N'LWU114', N'Advocacy Workshop',                  2, N'LAW', 7),
        (N'LWU115', N'Career Seminar for Law',             1, N'LAW', 7),
        (N'LWU116', N'Bar Preparation Workshop',           2, N'LAW', 8),
        (N'LWU117', N'Judicial Practicum',                 1, N'LAW', 8),
        (N'LWU118', N'Senior Law Seminar',                 1, N'LAW', 8),
        (N'LWU119', N'Legal Capstone Seminar',             2, N'LAW', 9),
        (N'LWU120', N'Professional Responsibility Seminar', 1, N'LAW', 9),
        (N'LWU121', N'Bar Preparation Workshop II',        1, N'LAW', 9),
        (N'LWU122', N'Public Interest Law Seminar',        1, N'LAW', 9),

        -- PHARM: BPHARM-specific
        (N'PHU101', N'Pharmacy Skills Lab I',              2, N'PHARM', 1),
        (N'PHU102', N'Introduction to Pharmacy Practice',  3, N'PHARM', 1),
        (N'PHU103', N'Compounding Practicum',              2, N'PHARM', 2),
        (N'PHU104', N'Pharmaceutical Calculations Workshop', 1, N'PHARM', 2),
        (N'PHU105', N'Pharmacy Skills Lab II',              2, N'PHARM', 3),
        (N'PHU106', N'Pharmacy Ethics Seminar',            1, N'PHARM', 3),
        (N'PHU107', N'Clinical Skills Workshop',           2, N'PHARM', 4),
        (N'PHU108', N'Patient Counselling Seminar',        1, N'PHARM', 4),
        (N'PHU109', N'Pharmacy Practice Lab',              2, N'PHARM', 5),
        (N'PHU110', N'Pharmacovigilance Seminar',          1, N'PHARM', 5),
        (N'PHU111', N'Pharmacy Management Workshop',       2, N'PHARM', 6),
        (N'PHU112', N'Pharmacy Research Seminar',          1, N'PHARM', 6),
        (N'PHU113', N'Applied Pharmaceutics Workshop',     3, N'PHARM', 7),
        (N'PHU114', N'Pharmacy Regulatory Affairs Seminar', 2, N'PHARM', 7),
        (N'PHU115', N'Career Seminar for Pharmacy',        1, N'PHARM', 8),
        (N'PHU116', N'Pharmacy Capstone Seminar',          1, N'PHARM', 8),
        (N'PHU117', N'Clinical Rotation Preparation Workshop', 2, N'PHARM', 9),
        (N'PHU118', N'Community Practice Practicum',       2, N'PHARM', 9)
        ) AS v(course_code, course_title, credits, dept_code, level_year)
    )
    INSERT INTO dbo.courses (course_code, course_title, credits, dept_id, level_year, is_active)
    SELECT nc.course_code, nc.course_title, nc.credits, d.dept_id, nc.level_year, 1
      FROM new_courses nc
      JOIN dbo.departments d ON d.dept_code = nc.dept_code
     WHERE NOT EXISTS (SELECT 1 FROM dbo.courses c WHERE c.course_code = nc.course_code);

    PRINT N'   new courses inserted: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));

COMMIT TRANSACTION;
PRINT N'--- Phase 23 migration committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0
        ROLLBACK TRANSACTION;
    PRINT N'!!! Phase 23 migration ROLLED BACK — nothing was changed. !!!';
    THROW;
END CATCH
GO

/* ---------------------------------------------------------------- verify */
SELECT COUNT(*) AS total_courses FROM dbo.courses;
SELECT COUNT(*) AS courses_with_requirement_type FROM dbo.program_requirements WHERE requirement_type IS NOT NULL;
GO
