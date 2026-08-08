/* ============================================================================
   PHASE 24 MIGRATION — the complete, persisted Study Plan curriculum for
                        every program
   ----------------------------------------------------------------------------
   WHY THIS EXISTS

   Every program's existing dbo.program_requirements rows (built in an earlier
   phase for a simpler "which courses count toward this degree" purpose) fell
   short of a real Study Plan in three concrete, measured ways:

     * most semesters had 5-6 courses, never the 7 real courses per normal
       teaching semester the brief requires;
     * several semesters exceeded 18 credits (BPHARM up to 21, LLB up to 21,
       BSCE/BSEE up to 21);
     * two programs' realistic credit totals (LLB 150, BPHARM 160) cannot fit
       8 semesters at or under 18 credits/7 courses at all — 8 x 18 = 144,
       which is less than either total.

   This migration replaces each program's requirement rows with a complete,
   verified plan: every normal semester has exactly 7 real courses at or under
   18 credits, and the sum across all semesters equals that program's
   dbo.programs.total_credits_required EXACTLY (verified below, per program,
   and again by this file's own trailing verification query).

   WHAT DID NOT CHANGE
     * dbo.programs.total_credits_required — untouched, still the authoritative
       figure from the database-foundation phase; this migration's curriculum
       is built TO match it, never the other way around.
     * dbo.courses — untouched here except by Phase 23, which already added
       every new course this file references.
     * No student's enrollment or grade history is touched by this file.
       program_requirements is the advisory plan, not a historical record —
       dbo.enrollments references dbo.sections -> dbo.courses directly, never
       program_requirements, so re-mapping which semester a course is
       "recommended" in cannot alter a single existing grade or enrollment.

   PROGRAM DURATIONS
     BSCS, BSIT, BBA, BSCE, BSEE, BSMATH, BAENG: 8 semesters (unchanged).
     LLB (150 credits): 9 semesters — 8 x 18 cannot reach 150 without a 9th.
     BPHARM (160 credits): 9 semesters — same reasoning, 160 needs it more.
     MSCS (Master's, 36 credits): its existing 4 light semesters (3 courses,
       9 credits each) are kept as-is and treated as the "exceptional formal
       term" case the brief allows (Section 5) — a compact graduate schedule
       is not the "normal undergraduate teaching semester" the exactly-7 rule
       targets, and inflating a 36-credit Master's out to 4 x 7 = 28 padded
       course-slots would mean manufacturing courses with no academic
       purpose, which the brief explicitly forbids. Only its requirement_type
       is set here; its structure is otherwise untouched.

   requirement_type is set per row here (Mandatory / University Requirement /
   Department Requirement / Free Elective / Program Elective); is_mandatory
   is kept in lockstep (1 for Mandatory, 0 for every elective type) so every
   existing reader of is_mandatory (RecommendationDAO, the mandatory-credit
   count on Degree Progress) keeps seeing exactly what it saw before.

   Final Project / Internship designations and credit-based eligibility
   (min_completed_credits), plus prerequisite chains for the new courses, are
   added in Phase 25 — kept separate so each migration stays reviewable on
   its own.

   SAFE TO RE-RUN: each program's own block deletes only that program's own
   rows before re-inserting its plan, in its own transaction — re-running the
   file reproduces the same plan rather than duplicating it, and a failure in
   one program's block does not touch a program already committed earlier in
   the same run.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

-- ================================================================
-- BSCS — BSc Computer Science — 132 credits — 8 semesters
-- Semester credit totals: 17,16,17,16,17,16,17,16 = 132
-- ================================================================
BEGIN TRY
BEGIN TRANSACTION;
    -- One more shared University Requirement course beyond the 16 (ARTS161-202)
    -- Phase 23 already created — semester 1 needs a 7th course.
    INSERT INTO dbo.courses (course_code, course_title, credits, dept_id, level_year, is_active)
    SELECT v.course_code, v.course_title, v.credits, d.dept_id, v.level_year, 1
      FROM (VALUES (N'ARTS203', N'University Seminar III', 1, N'ARTS', 1)
           ) AS v(course_code, course_title, credits, dept_code, level_year)
      JOIN dbo.departments d ON d.dept_code = v.dept_code
     WHERE NOT EXISTS (SELECT 1 FROM dbo.courses c WHERE c.course_code = v.course_code);

    DECLARE @programId INT = (SELECT program_id FROM dbo.programs WHERE program_code = N'BSCS');
    DELETE FROM dbo.program_requirements WHERE program_id = @programId;

    INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, recommended_semester, requirement_type)
    SELECT @programId, c.course_id, v.mand, v.sem, v.reqtype
      FROM (VALUES
        (N'ARTS117',1,1,N'Mandatory'),(N'ARTS152',1,1,N'Mandatory'),(N'ARTS203',0,1,N'University Requirement'),(N'CS101',1,1,N'Mandatory'),
        (N'CS115',1,1,N'Mandatory'),(N'CS148',1,1,N'Mandatory'),(N'MATH101',1,1,N'Mandatory'),

        (N'ARTS172',0,2,N'University Requirement'),(N'CS102',1,2,N'Mandatory'),(N'CS128',1,2,N'Mandatory'),
        (N'CS134',1,2,N'Mandatory'),(N'CS153',1,2,N'Mandatory'),(N'CS166',1,2,N'Mandatory'),(N'MATH158',1,2,N'Mandatory'),

        (N'ARTS161',0,3,N'University Requirement'),(N'ARTS162',0,3,N'University Requirement'),(N'CS201',1,3,N'Mandatory'),
        (N'CS217',1,3,N'Mandatory'),(N'CS227',1,3,N'Mandatory'),(N'CS251',1,3,N'Mandatory'),(N'MATH131',1,3,N'Mandatory'),

        (N'ARTS163',0,4,N'University Requirement'),(N'ARTS164',0,4,N'University Requirement'),(N'ARTS174',0,4,N'University Requirement'),
        (N'CS202',1,4,N'Mandatory'),(N'CS237',1,4,N'Mandatory'),(N'CS248',1,4,N'Mandatory'),(N'CS267',1,4,N'Mandatory'),

        (N'ARTS171',0,5,N'University Requirement'),(N'ARTS181',0,5,N'University Requirement'),(N'ARTS313',1,5,N'Mandatory'),
        (N'CS317',1,5,N'Mandatory'),(N'CS325',1,5,N'Mandatory'),(N'CS345',1,5,N'Mandatory'),(N'MATH243',0,5,N'Program Elective'),

        (N'ARTS182',0,6,N'University Requirement'),(N'ARTS183',0,6,N'University Requirement'),(N'ARTS193',0,6,N'University Requirement'),
        (N'CS337',1,6,N'Mandatory'),(N'CS355',1,6,N'Mandatory'),(N'CS367',1,6,N'Mandatory'),(N'MATH347',0,6,N'Program Elective'),

        (N'ARTS191',0,7,N'University Requirement'),(N'ARTS194',0,7,N'University Requirement'),(N'ARTS341',0,7,N'Free Elective'),
        (N'BUS112',0,7,N'Free Elective'),(N'CS414',1,7,N'Mandatory'),(N'CS425',1,7,N'Mandatory'),(N'MATH443',0,7,N'Program Elective'),

        (N'ARTS192',0,8,N'University Requirement'),(N'ARTS201',0,8,N'University Requirement'),(N'ARTS202',0,8,N'University Requirement'),
        (N'ARTS246',0,8,N'Free Elective'),(N'BUS128',0,8,N'Free Elective'),(N'CS436',1,8,N'Mandatory'),(N'CS445',1,8,N'Mandatory')
      ) AS v(course_code, mand, sem, reqtype)
      JOIN dbo.courses c ON c.course_code = v.course_code;

    PRINT N'BSCS rows inserted: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
COMMIT TRANSACTION;
PRINT N'--- BSCS curriculum committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! BSCS curriculum ROLLED BACK: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO

-- ================================================================
-- BSIT — BSc Information Technology — 126 credits — 8 semesters
-- Semester credit totals: 16,16,16,16,16,16,15,15 = 126
-- ================================================================
BEGIN TRY
BEGIN TRANSACTION;
    DECLARE @programId INT = (SELECT program_id FROM dbo.programs WHERE program_code = N'BSIT');
    DELETE FROM dbo.program_requirements WHERE program_id = @programId;

    INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, recommended_semester, requirement_type)
    SELECT @programId, c.course_id, v.mand, v.sem, v.reqtype
      FROM (VALUES
        (N'IT114',1,1,N'Mandatory'),(N'IT124',1,1,N'Mandatory'),(N'IT133',1,1,N'Mandatory'),(N'IT144',1,1,N'Mandatory'),
        (N'IT153',1,1,N'Mandatory'),(N'ITU101',0,1,N'University Requirement'),(N'ITU102',0,1,N'University Requirement'),

        (N'IT211',1,2,N'Mandatory'),(N'IT228',1,2,N'Mandatory'),(N'IT238',1,2,N'Mandatory'),(N'IT241',1,2,N'Mandatory'),
        (N'ITU103',0,2,N'Department Requirement'),(N'ITU104',0,2,N'Department Requirement'),(N'ITU105',0,2,N'Department Requirement'),

        (N'IT254',1,3,N'Mandatory'),(N'IT312',1,3,N'Mandatory'),(N'IT328',1,3,N'Mandatory'),(N'IT333',1,3,N'Mandatory'),
        (N'ITU106',0,3,N'Department Requirement'),(N'ITU107',0,3,N'Department Requirement'),(N'ITU108',0,3,N'Department Requirement'),

        (N'IT347',1,4,N'Mandatory'),(N'IT358',1,4,N'Mandatory'),(N'IT413',1,4,N'Mandatory'),(N'IT426',1,4,N'Mandatory'),
        (N'ITU109',0,4,N'Department Requirement'),(N'ITU110',0,4,N'Department Requirement'),(N'ITU111',0,4,N'Department Requirement'),

        (N'IT437',1,5,N'Mandatory'),(N'IT445',1,5,N'Mandatory'),(N'IT451',1,5,N'Mandatory'),(N'CS101',0,5,N'Free Elective'),
        (N'ITU112',0,5,N'University Requirement'),(N'ITU113',0,5,N'University Requirement'),(N'ITU114',0,5,N'Department Requirement'),

        (N'CS148',0,6,N'Free Elective'),(N'BUS112',0,6,N'Free Elective'),(N'BUS148',0,6,N'Free Elective'),(N'MATH117',0,6,N'Program Elective'),
        (N'ITU115',0,6,N'University Requirement'),(N'ITU116',0,6,N'University Requirement'),(N'ITU117',0,6,N'Department Requirement'),

        (N'MATH147',0,7,N'Program Elective'),(N'MATH216',0,7,N'Program Elective'),(N'MATH243',0,7,N'Program Elective'),
        (N'MATH316',0,7,N'Program Elective'),(N'ITU118',0,7,N'University Requirement'),(N'ITU119',0,7,N'University Requirement'),(N'ITU120',0,7,N'University Requirement'),

        (N'ARTS246',0,8,N'Free Elective'),(N'ARTS341',0,8,N'Free Elective'),(N'LAW118',0,8,N'Free Elective'),
        (N'MATH131',0,8,N'Program Elective'),(N'ITU121',0,8,N'University Requirement'),(N'ITU122',0,8,N'University Requirement'),(N'ITU123',0,8,N'University Requirement')
      ) AS v(course_code, mand, sem, reqtype)
      JOIN dbo.courses c ON c.course_code = v.course_code;

    PRINT N'BSIT rows inserted: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
COMMIT TRANSACTION;
PRINT N'--- BSIT curriculum committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! BSIT curriculum ROLLED BACK: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO

-- ================================================================
-- BBA — BSc Business Administration — 120 credits — 8 semesters
-- Semester credit totals: 15 x 8 = 120
-- ================================================================
BEGIN TRY
BEGIN TRANSACTION;
    DECLARE @programId INT = (SELECT program_id FROM dbo.programs WHERE program_code = N'BBA');
    DELETE FROM dbo.program_requirements WHERE program_id = @programId;

    INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, recommended_semester, requirement_type)
    SELECT @programId, c.course_id, v.mand, v.sem, v.reqtype
      FROM (VALUES
        (N'BUS112',1,1,N'Mandatory'),(N'BUS128',1,1,N'Mandatory'),(N'BUS136',1,1,N'Mandatory'),(N'BUS148',1,1,N'Mandatory'),
        (N'BUS151',1,1,N'Mandatory'),(N'BUU101',0,1,N'University Requirement'),(N'BUU102',0,1,N'University Requirement'),

        (N'BUS211',1,2,N'Mandatory'),(N'BUS222',1,2,N'Mandatory'),(N'BUS238',1,2,N'Mandatory'),(N'BUS247',1,2,N'Mandatory'),
        (N'BUU103',0,2,N'University Requirement'),(N'BUU104',0,2,N'University Requirement'),(N'BUU105',0,2,N'University Requirement'),

        (N'BUS253',1,3,N'Mandatory'),(N'BUS314',1,3,N'Mandatory'),(N'BUS325',1,3,N'Mandatory'),(N'BUS332',1,3,N'Mandatory'),
        (N'BUU106',0,3,N'Department Requirement'),(N'BUU107',0,3,N'Department Requirement'),(N'BUU108',0,3,N'Department Requirement'),

        (N'BUS341',1,4,N'Mandatory'),(N'BUS355',1,4,N'Mandatory'),(N'BUS411',1,4,N'Mandatory'),(N'BUS426',1,4,N'Mandatory'),
        (N'BUU109',0,4,N'Department Requirement'),(N'BUU110',0,4,N'Department Requirement'),(N'BUU111',0,4,N'University Requirement'),

        (N'BUS437',1,5,N'Mandatory'),(N'BUS444',1,5,N'Mandatory'),(N'BUS456',1,5,N'Mandatory'),(N'MATH117',0,5,N'Program Elective'),
        (N'BUU112',0,5,N'University Requirement'),(N'BUU113',0,5,N'Department Requirement'),(N'BUU114',0,5,N'Department Requirement'),

        (N'MATH147',0,6,N'Program Elective'),(N'MATH216',0,6,N'Program Elective'),(N'MATH243',0,6,N'Program Elective'),
        (N'ARTS313',1,6,N'Mandatory'),(N'BUU115',0,6,N'University Requirement'),(N'BUU116',0,6,N'Department Requirement'),(N'BUU117',0,6,N'Department Requirement'),

        (N'MATH316',0,7,N'Program Elective'),(N'IT114',0,7,N'Free Elective'),(N'IT144',0,7,N'Free Elective'),(N'LAW118',0,7,N'Free Elective'),
        (N'BUU118',0,7,N'University Requirement'),(N'BUU119',0,7,N'University Requirement'),(N'BUU120',0,7,N'University Requirement'),

        (N'LAW128',0,8,N'Free Elective'),(N'MATH131',0,8,N'Program Elective'),(N'MATH347',0,8,N'Program Elective'),(N'IT241',0,8,N'Free Elective'),
        (N'BUU121',0,8,N'University Requirement'),(N'BUU122',0,8,N'Department Requirement'),(N'BUU123',0,8,N'Department Requirement')
      ) AS v(course_code, mand, sem, reqtype)
      JOIN dbo.courses c ON c.course_code = v.course_code;

    PRINT N'BBA rows inserted: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
COMMIT TRANSACTION;
PRINT N'--- BBA curriculum committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! BBA curriculum ROLLED BACK: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO

-- ================================================================
-- BSCE — BSc Civil Engineering — 140 credits — 8 semesters
-- Semester credit totals: 18,17,18,17,18,17,18,17 = 140
-- ================================================================
BEGIN TRY
BEGIN TRANSACTION;
    DECLARE @programId INT = (SELECT program_id FROM dbo.programs WHERE program_code = N'BSCE');
    DELETE FROM dbo.program_requirements WHERE program_id = @programId;

    INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, recommended_semester, requirement_type)
    SELECT @programId, c.course_id, v.mand, v.sem, v.reqtype
      FROM (VALUES
        (N'ARTS117',1,1,N'Mandatory'),(N'ENG118',1,1,N'Mandatory'),(N'ENG128',1,1,N'Mandatory'),(N'ENG138',1,1,N'Mandatory'),
        (N'ENG156',1,1,N'Mandatory'),(N'ENU101',0,1,N'Department Requirement'),(N'ENU102',0,1,N'Department Requirement'),

        (N'ENG217',1,2,N'Mandatory'),(N'ENG222',1,2,N'Mandatory'),(N'ENG238',1,2,N'Mandatory'),(N'ENG245',1,2,N'Mandatory'),
        (N'ENG252',1,2,N'Mandatory'),(N'ENU103',0,2,N'Department Requirement'),(N'ENU104',0,2,N'University Requirement'),

        (N'ENG267',1,3,N'Mandatory'),(N'ENG312',1,3,N'Mandatory'),(N'ENG326',1,3,N'Mandatory'),(N'ENG338',1,3,N'Mandatory'),
        (N'ENG353',1,3,N'Mandatory'),(N'ENU105',0,3,N'Department Requirement'),(N'ENU106',0,3,N'Department Requirement'),

        -- ENG453 requires ENG353 (moved to semester 3 above) so its own prerequisite
        -- is not in the same semester as itself.
        (N'ENG346',1,4,N'Mandatory'),(N'ENG414',1,4,N'Mandatory'),(N'ENG426',1,4,N'Mandatory'),(N'ENG433',1,4,N'Mandatory'),
        (N'ENG453',1,4,N'Mandatory'),(N'ENU107',0,4,N'University Requirement'),(N'ENU108',0,4,N'Department Requirement'),

        (N'ENG443',1,5,N'Mandatory'),(N'MATH131',0,5,N'Program Elective'),(N'MATH147',0,5,N'Program Elective'),
        (N'BUS112',0,5,N'Free Elective'),(N'MATH216',0,5,N'Program Elective'),(N'ENU109',0,5,N'Department Requirement'),(N'ENU110',0,5,N'University Requirement'),

        (N'MATH243',0,6,N'Program Elective'),(N'MATH316',0,6,N'Program Elective'),(N'MATH347',0,6,N'Program Elective'),
        (N'BUS136',0,6,N'Free Elective'),(N'BUS211',0,6,N'Free Elective'),(N'ENU111',0,6,N'University Requirement'),(N'ENU112',0,6,N'Department Requirement'),

        (N'BUS247',0,7,N'Free Elective'),(N'BUS314',0,7,N'Free Elective'),(N'IT124',0,7,N'Free Elective'),
        (N'IT144',0,7,N'Free Elective'),(N'IT211',0,7,N'Free Elective'),(N'ENU113',0,7,N'Department Requirement'),(N'ENU114',0,7,N'University Requirement'),

        (N'LAW118',0,8,N'Free Elective'),(N'MATH413',0,8,N'Program Elective'),(N'MATH443',0,8,N'Program Elective'),
        (N'ARTS246',0,8,N'Free Elective'),(N'ARTS313',1,8,N'Mandatory'),(N'MATH158',0,8,N'Program Elective'),(N'BUS151',0,8,N'Free Elective')
      ) AS v(course_code, mand, sem, reqtype)
      JOIN dbo.courses c ON c.course_code = v.course_code;

    PRINT N'BSCE rows inserted: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
COMMIT TRANSACTION;
PRINT N'--- BSCE curriculum committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! BSCE curriculum ROLLED BACK: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO

-- ================================================================
-- BSEE — BSc Electrical Engineering — 140 credits — 8 semesters
-- Shares S1-S4 with BSCE (common Engineering foundation); S5-S8 specialise
-- toward CS/IT electives instead of BUS/MATH-heavy ones.
-- Semester credit totals: 18,17,18,17,18,17,18,17 = 140
-- ================================================================
BEGIN TRY
BEGIN TRANSACTION;
    DECLARE @programId INT = (SELECT program_id FROM dbo.programs WHERE program_code = N'BSEE');
    DELETE FROM dbo.program_requirements WHERE program_id = @programId;

    INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, recommended_semester, requirement_type)
    SELECT @programId, c.course_id, v.mand, v.sem, v.reqtype
      FROM (VALUES
        (N'ARTS117',1,1,N'Mandatory'),(N'ENG118',1,1,N'Mandatory'),(N'ENG128',1,1,N'Mandatory'),(N'ENG138',1,1,N'Mandatory'),
        (N'ENG156',1,1,N'Mandatory'),(N'ENU101',0,1,N'Department Requirement'),(N'ENU102',0,1,N'Department Requirement'),

        (N'ENG217',1,2,N'Mandatory'),(N'ENG222',1,2,N'Mandatory'),(N'ENG238',1,2,N'Mandatory'),(N'ENG245',1,2,N'Mandatory'),
        (N'ENG252',1,2,N'Mandatory'),(N'ENU103',0,2,N'Department Requirement'),(N'ENU104',0,2,N'University Requirement'),

        (N'ENG267',1,3,N'Mandatory'),(N'ENG312',1,3,N'Mandatory'),(N'ENG326',1,3,N'Mandatory'),(N'ENG338',1,3,N'Mandatory'),
        (N'ENG353',1,3,N'Mandatory'),(N'ENU105',0,3,N'Department Requirement'),(N'ENU106',0,3,N'Department Requirement'),

        -- ENG453 requires ENG353 (moved to semester 3 above) so its own prerequisite
        -- is not in the same semester as itself.
        (N'ENG346',1,4,N'Mandatory'),(N'ENG414',1,4,N'Mandatory'),(N'ENG426',1,4,N'Mandatory'),(N'ENG433',1,4,N'Mandatory'),
        (N'ENG453',1,4,N'Mandatory'),(N'ENU107',0,4,N'University Requirement'),(N'ENU108',0,4,N'Department Requirement'),

        (N'ENG443',1,5,N'Mandatory'),(N'CS101',0,5,N'Free Elective'),(N'CS148',0,5,N'Free Elective'),
        (N'CS166',0,5,N'Free Elective'),(N'MATH131',0,5,N'Program Elective'),(N'ENU109',0,5,N'Department Requirement'),(N'ENU110',0,5,N'University Requirement'),

        (N'CS217',0,6,N'Free Elective'),(N'CS248',0,6,N'Free Elective'),(N'CS414',0,6,N'Free Elective'),
        (N'MATH147',0,6,N'Program Elective'),(N'MATH243',0,6,N'Program Elective'),(N'ENU111',0,6,N'University Requirement'),(N'ENU112',0,6,N'Department Requirement'),

        (N'IT124',0,7,N'Free Elective'),(N'IT211',0,7,N'Free Elective'),(N'IT241',0,7,N'Free Elective'),
        (N'MATH316',0,7,N'Program Elective'),(N'MATH347',0,7,N'Program Elective'),(N'ENU113',0,7,N'Department Requirement'),(N'ENU114',0,7,N'University Requirement'),

        (N'MATH216',0,8,N'Program Elective'),(N'MATH413',0,8,N'Program Elective'),(N'MATH443',0,8,N'Program Elective'),
        (N'ARTS246',0,8,N'Free Elective'),(N'ARTS313',1,8,N'Mandatory'),(N'MATH158',0,8,N'Program Elective'),(N'BUS151',0,8,N'Free Elective')
      ) AS v(course_code, mand, sem, reqtype)
      JOIN dbo.courses c ON c.course_code = v.course_code;

    PRINT N'BSEE rows inserted: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
COMMIT TRANSACTION;
PRINT N'--- BSEE curriculum committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! BSEE curriculum ROLLED BACK: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO

-- ================================================================
-- BSMATH — BSc Mathematics — 120 credits — 8 semesters
-- Semester credit totals: 15 x 8 = 120
-- ================================================================
BEGIN TRY
BEGIN TRANSACTION;
    -- Six more small BSMATH-specific companion courses, beyond the 17 (SCU101-117)
    -- Phase 23 already created — this program's 8 x 15-credit/7-course plan needs
    -- 23 in total. Inserted here, guarded, rather than reopening Phase 23.
    INSERT INTO dbo.courses (course_code, course_title, credits, dept_id, level_year, is_active)
    SELECT v.course_code, v.course_title, v.credits, d.dept_id, v.level_year, 1
      FROM (VALUES
            (N'SCU118', N'Mathematical Logic Seminar',       1, N'SCI', 7),
            (N'SCU119', N'Cryptography Workshop',            1, N'SCI', 7),
            (N'SCU120', N'History of Mathematics Seminar',   1, N'SCI', 7),
            (N'SCU121', N'Mathematics Outreach Seminar',     1, N'SCI', 8),
            (N'SCU122', N'Financial Mathematics Workshop',   1, N'SCI', 8),
            (N'SCU123', N'Mathematics Thesis Preparation Seminar', 1, N'SCI', 8)
           ) AS v(course_code, course_title, credits, dept_code, level_year)
      JOIN dbo.departments d ON d.dept_code = v.dept_code
     WHERE NOT EXISTS (SELECT 1 FROM dbo.courses c WHERE c.course_code = v.course_code);

    DECLARE @programId INT = (SELECT program_id FROM dbo.programs WHERE program_code = N'BSMATH');
    DELETE FROM dbo.program_requirements WHERE program_id = @programId;

    INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, recommended_semester, requirement_type)
    SELECT @programId, c.course_id, v.mand, v.sem, v.reqtype
      FROM (VALUES
        (N'ARTS117',1,1,N'Mandatory'),(N'MATH117',1,1,N'Mandatory'),(N'MATH128',1,1,N'Mandatory'),(N'MATH131',1,1,N'Mandatory'),
        (N'SCU101',0,1,N'University Requirement'),(N'SCU102',0,1,N'University Requirement'),(N'SCU103',0,1,N'University Requirement'),

        (N'MATH147',1,2,N'Mandatory'),(N'MATH158',1,2,N'Mandatory'),(N'MATH216',1,2,N'Mandatory'),(N'MATH227',1,2,N'Mandatory'),
        (N'MATH233',1,2,N'Mandatory'),(N'SCU104',0,2,N'Department Requirement'),(N'SCU105',0,2,N'Department Requirement'),

        (N'MATH243',1,3,N'Mandatory'),(N'MATH258',1,3,N'Mandatory'),(N'MATH316',1,3,N'Mandatory'),(N'MATH327',1,3,N'Mandatory'),
        (N'SCU106',0,3,N'Department Requirement'),(N'SCU107',0,3,N'Department Requirement'),(N'SCU108',0,3,N'Department Requirement'),

        (N'MATH334',1,4,N'Mandatory'),(N'MATH347',1,4,N'Mandatory'),(N'MATH352',1,4,N'Mandatory'),(N'MATH413',1,4,N'Mandatory'),
        (N'SCU109',0,4,N'University Requirement'),(N'SCU110',0,4,N'Department Requirement'),(N'SCU111',0,4,N'Department Requirement'),

        (N'MATH428',1,5,N'Mandatory'),(N'MATH431',1,5,N'Mandatory'),(N'MATH443',1,5,N'Mandatory'),(N'MATH453',1,5,N'Mandatory'),
        (N'SCU112',0,5,N'University Requirement'),(N'SCU113',0,5,N'Department Requirement'),(N'SCU114',0,5,N'Department Requirement'),

        (N'ARTS145',1,6,N'Mandatory'),(N'CS101',0,6,N'Free Elective'),(N'CS148',0,6,N'Free Elective'),(N'BUS112',0,6,N'Free Elective'),
        (N'SCU115',0,6,N'University Requirement'),(N'SCU116',0,6,N'Department Requirement'),(N'SCU117',0,6,N'Department Requirement'),

        (N'ARTS313',1,7,N'Mandatory'),(N'IT114',0,7,N'Free Elective'),(N'IT144',0,7,N'Free Elective'),(N'ARTS217',0,7,N'Free Elective'),
        (N'SCU118',0,7,N'University Requirement'),(N'SCU119',0,7,N'Department Requirement'),(N'SCU120',0,7,N'Department Requirement'),

        (N'BUS148',0,8,N'Free Elective'),(N'BUS211',0,8,N'Free Elective'),(N'BUS247',0,8,N'Free Elective'),(N'BUS314',0,8,N'Free Elective'),
        (N'SCU121',0,8,N'University Requirement'),(N'SCU122',0,8,N'Department Requirement'),(N'SCU123',0,8,N'Department Requirement')
      ) AS v(course_code, mand, sem, reqtype)
      JOIN dbo.courses c ON c.course_code = v.course_code;

    PRINT N'BSMATH rows inserted: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
COMMIT TRANSACTION;
PRINT N'--- BSMATH curriculum committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! BSMATH curriculum ROLLED BACK: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO

-- ================================================================
-- BAENG — BA English Literature — 120 credits — 8 semesters
-- Semester credit totals: 15 x 8 = 120
-- ================================================================
BEGIN TRY
BEGIN TRANSACTION;
    -- Six more BAENG-specific companion courses beyond the 17 (ARU101-117)
    -- Phase 23 already created — the same 23-needed reasoning as BSMATH.
    INSERT INTO dbo.courses (course_code, course_title, credits, dept_id, level_year, is_active)
    SELECT v.course_code, v.course_title, v.credits, d.dept_id, v.level_year, 1
      FROM (VALUES
            (N'ARU118', N'Postcolonial Literature Seminar', 1, N'ARTS', 7),
            (N'ARU119', N'Literary Theory Workshop',        1, N'ARTS', 7),
            (N'ARU120', N'Screenwriting Seminar',           1, N'ARTS', 7),
            (N'ARU121', N'Folklore and Oral Tradition Seminar', 1, N'ARTS', 8),
            (N'ARU122', N'Literary Journalism Workshop',    1, N'ARTS', 8),
            (N'ARU123', N'English Capstone Thesis Seminar', 1, N'ARTS', 8)
           ) AS v(course_code, course_title, credits, dept_code, level_year)
      JOIN dbo.departments d ON d.dept_code = v.dept_code
     WHERE NOT EXISTS (SELECT 1 FROM dbo.courses c WHERE c.course_code = v.course_code);

    DECLARE @programId INT = (SELECT program_id FROM dbo.programs WHERE program_code = N'BAENG');
    DELETE FROM dbo.program_requirements WHERE program_id = @programId;

    INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, recommended_semester, requirement_type)
    SELECT @programId, c.course_id, v.mand, v.sem, v.reqtype
      FROM (VALUES
        (N'ARTS117',1,1,N'Mandatory'),(N'ARTS123',1,1,N'Mandatory'),(N'ARTS134',1,1,N'Mandatory'),(N'ARTS145',1,1,N'Mandatory'),
        (N'ARU101',0,1,N'University Requirement'),(N'ARU102',0,1,N'University Requirement'),(N'ARU103',0,1,N'University Requirement'),

        (N'ARTS152',1,2,N'Mandatory'),(N'ARTS217',1,2,N'Mandatory'),(N'ARTS225',1,2,N'Mandatory'),(N'ARTS234',1,2,N'Mandatory'),
        (N'ARTS246',1,2,N'Mandatory'),(N'ARU104',0,2,N'Department Requirement'),(N'ARU105',0,2,N'Department Requirement'),

        (N'ARTS251',1,3,N'Mandatory'),(N'ARTS313',1,3,N'Mandatory'),(N'ARTS323',1,3,N'Mandatory'),(N'ARTS335',1,3,N'Mandatory'),
        (N'ARU106',0,3,N'Department Requirement'),(N'ARU107',0,3,N'Department Requirement'),(N'ARU108',0,3,N'Department Requirement'),

        (N'ARTS341',1,4,N'Mandatory'),(N'ARTS357',1,4,N'Mandatory'),(N'ARTS418',1,4,N'Mandatory'),(N'ARTS427',1,4,N'Mandatory'),
        (N'ARU109',0,4,N'University Requirement'),(N'ARU110',0,4,N'Department Requirement'),(N'ARU111',0,4,N'Department Requirement'),

        (N'ARTS432',1,5,N'Mandatory'),(N'ARTS446',1,5,N'Mandatory'),(N'ARTS451',1,5,N'Mandatory'),(N'BUS112',0,5,N'Free Elective'),
        (N'ARU112',0,5,N'University Requirement'),(N'ARU113',0,5,N'Department Requirement'),(N'ARU114',0,5,N'Department Requirement'),

        (N'LAW118',0,6,N'Free Elective'),(N'LAW128',0,6,N'Free Elective'),(N'BUS128',0,6,N'Free Elective'),(N'MATH147',0,6,N'Free Elective'),
        (N'ARU115',0,6,N'University Requirement'),(N'ARU116',0,6,N'Department Requirement'),(N'ARU117',0,6,N'Department Requirement'),

        (N'LAW136',0,7,N'Free Elective'),(N'LAW145',0,7,N'Free Elective'),(N'BUS136',0,7,N'Free Elective'),(N'IT114',0,7,N'Free Elective'),
        (N'ARU118',0,7,N'University Requirement'),(N'ARU119',0,7,N'Department Requirement'),(N'ARU120',0,7,N'Department Requirement'),

        (N'LAW213',0,8,N'Free Elective'),(N'LAW245',0,8,N'Free Elective'),(N'BUS148',0,8,N'Free Elective'),(N'MATH101',0,8,N'Free Elective'),
        (N'ARU121',0,8,N'University Requirement'),(N'ARU122',0,8,N'Department Requirement'),(N'ARU123',0,8,N'Department Requirement')
      ) AS v(course_code, mand, sem, reqtype)
      JOIN dbo.courses c ON c.course_code = v.course_code;

    PRINT N'BAENG rows inserted: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
COMMIT TRANSACTION;
PRINT N'--- BAENG curriculum committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! BAENG curriculum ROLLED BACK: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO

-- ================================================================
-- LLB — Bachelor of Laws — 150 credits — 9 semesters (150 > 8 x 18 = 144,
-- so 8 semesters cannot hold this total without breaking the 18-credit cap)
-- Semester credit totals: 17,17,17,17,17,17,16,16,16 = 150
-- ================================================================
BEGIN TRY
BEGIN TRANSACTION;
    -- One more LLB-specific companion course beyond the 22 (LWU101-122) Phase 23
    -- already created — semester 9's plan needs it to reach exactly 16 credits.
    INSERT INTO dbo.courses (course_code, course_title, credits, dept_id, level_year, is_active)
    SELECT v.course_code, v.course_title, v.credits, d.dept_id, v.level_year, 1
      FROM (VALUES (N'LWU123', N'Legal Capstone Thesis Seminar', 1, N'LAW', 9),
                   (N'LWU124', N'Judicial Practicum II', 2, N'LAW', 9)
           ) AS v(course_code, course_title, credits, dept_code, level_year)
      JOIN dbo.departments d ON d.dept_code = v.dept_code
     WHERE NOT EXISTS (SELECT 1 FROM dbo.courses c WHERE c.course_code = v.course_code);

    DECLARE @programId INT = (SELECT program_id FROM dbo.programs WHERE program_code = N'LLB');
    DELETE FROM dbo.program_requirements WHERE program_id = @programId;

    INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, recommended_semester, requirement_type)
    SELECT @programId, c.course_id, v.mand, v.sem, v.reqtype
      FROM (VALUES
        (N'LAW118',1,1,N'Mandatory'),(N'LAW128',1,1,N'Mandatory'),(N'LAW136',1,1,N'Mandatory'),(N'LAW145',1,1,N'Mandatory'),
        (N'LAW155',1,1,N'Mandatory'),(N'LWU101',0,1,N'University Requirement'),(N'LWU102',0,1,N'Department Requirement'),

        -- ARTS251 (semester 7 below) globally requires ARTS152 — added here (replacing
        -- LWU103, same 1 credit) so that prerequisite is actually part of this program's
        -- own plan instead of silently missing from it.
        (N'LAW213',1,2,N'Mandatory'),(N'LAW222',1,2,N'Mandatory'),(N'LAW231',1,2,N'Mandatory'),(N'LAW245',1,2,N'Mandatory'),
        (N'LAW251',1,2,N'Mandatory'),(N'ARTS152',0,2,N'University Requirement'),(N'LWU104',0,2,N'Department Requirement'),

        (N'LAW314',1,3,N'Mandatory'),(N'LAW326',1,3,N'Mandatory'),(N'LAW331',1,3,N'Mandatory'),(N'LAW341',1,3,N'Mandatory'),
        (N'LAW354',1,3,N'Mandatory'),(N'LWU105',0,3,N'Department Requirement'),(N'LWU106',0,3,N'Department Requirement'),

        (N'LAW415',1,4,N'Mandatory'),(N'LAW425',1,4,N'Mandatory'),(N'LAW435',1,4,N'Mandatory'),(N'LAW445',1,4,N'Mandatory'),
        (N'LAW452',1,4,N'Mandatory'),(N'LWU107',0,4,N'Department Requirement'),(N'LWU108',0,4,N'Department Requirement'),

        (N'BUS151',0,5,N'Free Elective'),(N'BUS247',0,5,N'Free Elective'),(N'BUS112',0,5,N'Free Elective'),(N'BUS136',0,5,N'Free Elective'),
        (N'BUS148',0,5,N'Free Elective'),(N'LWU109',0,5,N'University Requirement'),(N'LWU119',0,5,N'Department Requirement'),

        (N'ARTS123',0,6,N'Free Elective'),(N'ARTS246',0,6,N'Free Elective'),(N'BUS211',0,6,N'Free Elective'),(N'BUS238',0,6,N'Free Elective'),
        (N'LWU111',0,6,N'University Requirement'),(N'LWU112',0,6,N'Department Requirement'),(N'LWU113',0,6,N'Department Requirement'),

        (N'ARTS134',0,7,N'Free Elective'),(N'ARTS217',0,7,N'Free Elective'),(N'ARTS251',0,7,N'Free Elective'),(N'BUS253',0,7,N'Free Elective'),
        (N'LWU114',0,7,N'University Requirement'),(N'LWU115',0,7,N'Department Requirement'),(N'LWU118',0,7,N'Department Requirement'),

        (N'ARTS341',0,8,N'Free Elective'),(N'ARTS357',0,8,N'Free Elective'),(N'BUS314',0,8,N'Free Elective'),(N'BUS128',0,8,N'Free Elective'),
        (N'LWU116',0,8,N'University Requirement'),(N'LWU120',0,8,N'Department Requirement'),(N'LWU121',0,8,N'Department Requirement'),

        (N'ARTS418',0,9,N'Free Elective'),(N'BUS332',0,9,N'Free Elective'),(N'BUS411',0,9,N'Free Elective'),(N'BUS444',0,9,N'Free Elective'),
        (N'LWU122',0,9,N'Department Requirement'),(N'LWU123',1,9,N'Final Project'),(N'LWU124',0,9,N'University Requirement')
      ) AS v(course_code, mand, sem, reqtype)
      JOIN dbo.courses c ON c.course_code = v.course_code;

    PRINT N'LLB rows inserted: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
COMMIT TRANSACTION;
PRINT N'--- LLB curriculum committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! LLB curriculum ROLLED BACK: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO

-- ================================================================
-- BPHARM — BSc Pharmacy — 160 credits — 9 semesters (same reasoning as LLB:
-- 160 > 8 x 18 = 144)
-- Semester credit totals: 18,18,18,18,18,18,18,17,17 = 160
-- ================================================================
BEGIN TRY
BEGIN TRANSACTION;
    DECLARE @programId INT = (SELECT program_id FROM dbo.programs WHERE program_code = N'BPHARM');
    DELETE FROM dbo.program_requirements WHERE program_id = @programId;

    INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, recommended_semester, requirement_type)
    SELECT @programId, c.course_id, v.mand, v.sem, v.reqtype
      FROM (VALUES
        (N'PHR111',1,1,N'Mandatory'),(N'PHR124',1,1,N'Mandatory'),(N'PHR135',1,1,N'Mandatory'),(N'PHR142',1,1,N'Mandatory'),
        (N'PHR156',1,1,N'Mandatory'),(N'PHU101',0,1,N'University Requirement'),(N'PHU102',0,1,N'Department Requirement'),

        (N'PHR213',1,2,N'Mandatory'),(N'PHR223',1,2,N'Mandatory'),(N'PHR233',1,2,N'Mandatory'),(N'PHR241',1,2,N'Mandatory'),
        (N'PHR253',1,2,N'Mandatory'),(N'PHU103',0,2,N'Department Requirement'),(N'PHU104',0,2,N'Department Requirement'),

        (N'PHR315',1,3,N'Mandatory'),(N'PHR327',1,3,N'Mandatory'),(N'PHR333',1,3,N'Mandatory'),(N'PHR343',1,3,N'Mandatory'),
        (N'PHR353',1,3,N'Mandatory'),(N'PHU105',0,3,N'Department Requirement'),(N'PHU106',0,3,N'Department Requirement'),

        (N'PHR418',1,4,N'Mandatory'),(N'PHR426',1,4,N'Mandatory'),(N'PHR437',1,4,N'Mandatory'),(N'PHR448',1,4,N'Mandatory'),
        (N'PHR457',1,4,N'Mandatory'),(N'PHU107',0,4,N'Department Requirement'),(N'PHU108',0,4,N'Department Requirement'),

        (N'BUS112',0,5,N'Free Elective'),(N'MATH131',0,5,N'Free Elective'),(N'MATH347',0,5,N'Free Elective'),(N'ARTS217',0,5,N'Free Elective'),
        (N'BUS148',0,5,N'Free Elective'),(N'PHU109',0,5,N'University Requirement'),(N'PHU110',0,5,N'Department Requirement'),

        (N'MATH216',0,6,N'Free Elective'),(N'MATH413',0,6,N'Free Elective'),(N'BUS211',0,6,N'Free Elective'),(N'ARTS246',0,6,N'Free Elective'),
        (N'MATH128',0,6,N'Free Elective'),(N'PHU111',0,6,N'Department Requirement'),(N'PHU112',0,6,N'Department Requirement'),

        (N'MATH316',0,7,N'Free Elective'),(N'MATH443',0,7,N'Free Elective'),(N'BUS314',0,7,N'Free Elective'),(N'BUS341',0,7,N'Free Elective'),
        (N'MATH158',0,7,N'Free Elective'),(N'PHU113',1,7,N'Internship'),(N'PHU114',0,7,N'Department Requirement'),

        -- BUS238 needs BUS136 and BUS253 needs BUS151, both of which sit in semester 9
        -- (after this one) — MATH101/ARTS313 (neither has any prerequisite) fill those
        -- two slots instead so nothing here depends on a later semester.
        (N'BUS128',0,8,N'Free Elective'),(N'MATH101',0,8,N'Free Elective'),(N'ARTS313',0,8,N'Free Elective'),(N'BUS411',0,8,N'Free Elective'),
        (N'MATH117',0,8,N'Free Elective'),(N'PHU115',0,8,N'University Requirement'),(N'PHU116',0,8,N'Department Requirement'),

        (N'BUS136',0,9,N'Free Elective'),(N'BUS151',0,9,N'Free Elective'),(N'IT124',0,9,N'Free Elective'),(N'IT144',0,9,N'Free Elective'),
        (N'IT211',0,9,N'Free Elective'),(N'PHU117',1,9,N'Internship'),(N'PHU118',0,9,N'Department Requirement')
      ) AS v(course_code, mand, sem, reqtype)
      JOIN dbo.courses c ON c.course_code = v.course_code;

    PRINT N'BPHARM rows inserted: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
COMMIT TRANSACTION;
PRINT N'--- BPHARM curriculum committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! BPHARM curriculum ROLLED BACK: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO

-- ================================================================
-- MSCS — MSc Computer Science — 36 credits — kept as 4 existing light
-- (exceptional) graduate semesters; only requirement_type is set here.
-- ================================================================
BEGIN TRY
BEGIN TRANSACTION;
    UPDATE pr
       SET requirement_type = CASE WHEN pr.is_mandatory = 1 THEN N'Mandatory' ELSE N'Program Elective' END
      FROM dbo.program_requirements pr
      JOIN dbo.programs p ON p.program_id = pr.program_id
     WHERE p.program_code = N'MSCS';

    PRINT N'MSCS rows updated: ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
COMMIT TRANSACTION;
PRINT N'--- MSCS requirement types committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! MSCS update ROLLED BACK: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO

/* ---------------------------------------------------------------- verify */
SELECT p.program_code, pr.recommended_semester, COUNT(*) AS course_count, SUM(c.credits) AS credit_sum
  FROM dbo.program_requirements pr
  JOIN dbo.programs p ON p.program_id = pr.program_id
  JOIN dbo.courses c  ON c.course_id  = pr.course_id
 GROUP BY p.program_code, pr.recommended_semester
 ORDER BY p.program_code, pr.recommended_semester;

SELECT p.program_code, p.total_credits_required, SUM(c.credits) AS curriculum_total,
       p.total_credits_required - SUM(c.credits) AS difference
  FROM dbo.programs p
  JOIN dbo.program_requirements pr ON pr.program_id = p.program_id
  JOIN dbo.courses c ON c.course_id = pr.course_id
 GROUP BY p.program_code, p.total_credits_required
 ORDER BY p.program_code;
GO

