/* ============================================================================
   SEED 11 -- Enrollment trend backfill (Fall 2021 -> Fall 2026)
   One-time data backfill so dbo.enrollments reflects realistic per-semester
   totals. The admin dashboard's "Enrollment trend" chart reads these numbers
   live (ReportDAO.enrollmentTrend()) -- nothing here is duplicated in Java.

   What this does, in order:
     1. Adds ~270 new students (admitted 2018-2021) so Fall 2021 onward has
        enough eligible students to support realistic course loads.
     2. Adds sections to Fall 2021 / Spring 2022 (the two semesters whose
        existing capacity is below the target enrollment count).
     3. Inserts enrollment rows per semester, via random capacity-respecting
        passes, until each semester's live enrollment count hits its target.
     4. Generates dbo.grades rows (existing 30/30/40 scale) for every new
        COMPLETED enrollment in a past semester.
     5. Recomputes dbo.sections.enrolled_count for every section to match the
        schema's own documented invariant (COUNT of ENROLLED+COMPLETED rows).
     6. Prints verification totals.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

SET NOCOUNT ON;

/* ============================================================================
   STEP 1 -- ~270 new students, admitted 2018-01-15 .. 2021-08-20, so they are
   eligible (admission_date <= start_date) for every semester Fall 2021
   onward. status = ACTIVE for all of them (no graduation lifecycle modeled
   for this synthetic cohort -- out of scope for an enrollment-count fix).
============================================================================ */

IF OBJECT_ID('tempdb..#FirstNames') IS NOT NULL DROP TABLE #FirstNames;
IF OBJECT_ID('tempdb..#LastNames')  IS NOT NULL DROP TABLE #LastNames;
IF OBJECT_ID('tempdb..#NewPeople')  IS NOT NULL DROP TABLE #NewPeople;
IF OBJECT_ID('tempdb..#Programs')   IS NOT NULL DROP TABLE #Programs;
IF OBJECT_ID('tempdb..#Cities')     IS NOT NULL DROP TABLE #Cities;
IF OBJECT_ID('tempdb..#Rooms')      IS NOT NULL DROP TABLE #Rooms;

CREATE TABLE #FirstNames (name NVARCHAR(50), gender NVARCHAR(10));
INSERT INTO #FirstNames (name, gender) VALUES
(N'Karim',N'MALE'),(N'Rami',N'MALE'),(N'Elie',N'MALE'),(N'Tarek',N'MALE'),(N'Wassim',N'MALE'),
(N'Georges',N'MALE'),(N'Fadi',N'MALE'),(N'Marwan',N'MALE'),(N'Bassam',N'MALE'),(N'Adnan',N'MALE'),
(N'Charbel',N'MALE'),(N'Ziad',N'MALE'),(N'Elias',N'MALE'),(N'Samer',N'MALE'),(N'Rabih2',N'MALE'),
(N'Joseph',N'MALE'),(N'Antoun',N'MALE'),(N'Nabil2',N'MALE'),(N'Walid',N'MALE'),(N'Hicham',N'MALE'),
(N'Nour',N'FEMALE'),(N'Layal',N'FEMALE'),(N'Maya',N'FEMALE'),(N'Rana',N'FEMALE'),(N'Dina2',N'FEMALE'),
(N'Lea',N'FEMALE'),(N'Sara',N'FEMALE'),(N'Christelle',N'FEMALE'),(N'Joanna',N'FEMALE'),(N'Rita',N'FEMALE'),
(N'Yasmine',N'FEMALE'),(N'Carla',N'FEMALE'),(N'Nadine',N'FEMALE'),(N'Rania',N'FEMALE'),(N'Grace',N'FEMALE'),
(N'Mirna',N'FEMALE'),(N'Joelle',N'FEMALE'),(N'Sandra',N'FEMALE'),(N'Tania',N'FEMALE'),(N'Celine',N'FEMALE');

CREATE TABLE #LastNames (name NVARCHAR(50));
INSERT INTO #LastNames (name) VALUES
(N'Nasr2'),(N'Matta3'),(N'Ghosn3'),(N'Younes3'),(N'Abourjeily3'),(N'Zoghbi3'),(N'Naccache3'),(N'Haddad2'),
(N'Khoury2'),(N'Nakhle'),(N'Saliba'),(N'Fares2'),(N'Rizk'),(N'Sarkis'),(N'Chamoun'),(N'Assaf'),
(N'Boukhalil'),(N'Aoun'),(N'Daher'),(N'Farah'),(N'Semaan'),(N'Aziz'),(N'Hobeika'),(N'Karam2'),
(N'Sabbagh2'),(N'Frangieh2'),(N'Doumit2'),(N'Khalife2');

CREATE TABLE #Cities (name NVARCHAR(50));
INSERT INTO #Cities (name) VALUES
(N'Khaldeh'),(N'Aley'),(N'Marjeyoun'),(N'Chouf'),(N'Saida'),(N'Beirut'),(N'Baalbek'),(N'Jbeil'),
(N'Byblos'),(N'Tyre'),(N'Zahle'),(N'Tripoli'),(N'Jounieh'),(N'Baabda'),(N'Rachaya'),(N'Hasbaya'),(N'Zgharta');

CREATE TABLE #Programs (program_id INT);
INSERT INTO #Programs (program_id)
SELECT program_id FROM dbo.programs WHERE program_code <> N'MSCS';

/* 270 distinct (first,last) combinations, numbered 1..270 */
WITH combos AS (
    SELECT f.name AS first_name, f.gender, l.name AS last_name,
           ROW_NUMBER() OVER (ORDER BY NEWID()) AS seq
    FROM #FirstNames f
    CROSS JOIN #LastNames l
)
SELECT TOP (270) seq, first_name, gender, last_name,
       CONCAT(N'bf', RIGHT(N'00000' + CAST(seq AS NVARCHAR(5)), 5)) AS username,
       CONCAT(N'BF', RIGHT(N'00000' + CAST(seq AS NVARCHAR(5)), 5)) AS student_number,
       /* admission dates spread 2018-01-15 .. 2021-08-20, weighted toward more recent years */
       DATEADD(DAY,
               ABS(CHECKSUM(NEWID())) % 1313,   -- 1313 days between the two bounds
               CAST('2018-01-15' AS DATE)) AS admission_date
INTO #NewPeople
FROM combos
ORDER BY seq;

INSERT INTO dbo.users (username, password_hash, role)
SELECT username,
       N'$2a$12$KzQ8yV0vXJgqU2N1s6cQhOQ9nO8m1lJb6nOe5Q0T0Yg6c5c1Hh1Sa',  -- shared placeholder hash; these are backfill-only accounts, never used to log in
       N'STUDENT'
FROM #NewPeople;

INSERT INTO dbo.students
    (user_id, student_number, first_name, last_name, email, phone, date_of_birth, gender, address,
     program_id, admission_date, status, academic_standing, cumulative_gpa, completed_credits, probation_count)
SELECT
    u.user_id,
    np.student_number,
    np.first_name,
    np.last_name,
    CONCAT(np.username, N'@student.university.edu.lb'),
    CONCAT(N'+9617', RIGHT(N'0000000' + CAST(ABS(CHECKSUM(NEWID())) % 10000000 AS NVARCHAR(10)), 7)),
    DATEADD(YEAR, -(19 + ABS(CHECKSUM(NEWID())) % 6), np.admission_date),  -- 19-24 years old at admission
    np.gender,
    (SELECT TOP 1 name FROM #Cities ORDER BY NEWID()),
    (SELECT TOP 1 program_id FROM #Programs ORDER BY NEWID()),
    np.admission_date,
    N'ACTIVE',
    CASE WHEN ABS(CHECKSUM(NEWID())) % 5 = 0 THEN N'DEANS_LIST' ELSE N'GOOD' END,
    0.00, 0, 0
FROM #NewPeople np
JOIN dbo.users u ON u.username = np.username;

DECLARE @new_people_count INT = (SELECT COUNT(*) FROM #NewPeople);
PRINT CONCAT(N'Step 1 done: ', @new_people_count, N' new students added.');

/* ============================================================================
   STEP 2 -- New sections for Fall 2021 (+35) and Spring 2022 (+25), the only
   two semesters whose existing capacity is below the target enrollment.
============================================================================ */

CREATE TABLE #Rooms (room NVARCHAR(20));
INSERT INTO #Rooms (room) VALUES
(N'A101'),(N'A102'),(N'A105'),(N'A110'),(N'B204'),(N'B208'),(N'B301'),(N'B310'),
(N'C101'),(N'C105'),(N'C202'),(N'D110'),(N'D115'),(N'E201'),(N'E210');

DECLARE @sem_fall2021 INT = (SELECT semester_id FROM dbo.semesters WHERE semester_name = N'Fall 2021');
DECLARE @sem_spring2022 INT = (SELECT semester_id FROM dbo.semesters WHERE semester_name = N'Spring 2022');

;WITH candidate_courses AS (
    SELECT c.course_id,
           ROW_NUMBER() OVER (ORDER BY NEWID()) AS rn
    FROM dbo.courses c
    WHERE c.is_active = 1
      AND NOT EXISTS (SELECT 1 FROM dbo.sections sec WHERE sec.semester_id = @sem_fall2021 AND sec.course_id = c.course_id)
)
INSERT INTO dbo.sections (course_id, semester_id, instructor_id, campus_id, section_number, capacity, enrolled_count, room, status)
SELECT
    cc.course_id,
    @sem_fall2021,
    (SELECT TOP 1 instructor_id FROM dbo.instructors WHERE is_active = 1 ORDER BY NEWID()),
    1 + ABS(CHECKSUM(NEWID())) % 2,
    N'01',
    20 + ABS(CHECKSUM(NEWID())) % 16,   -- capacity 20-35
    0,
    (SELECT TOP 1 room FROM #Rooms ORDER BY NEWID()),
    N'OPEN'
FROM candidate_courses cc
WHERE cc.rn <= 35;

;WITH candidate_courses AS (
    SELECT c.course_id,
           ROW_NUMBER() OVER (ORDER BY NEWID()) AS rn
    FROM dbo.courses c
    WHERE c.is_active = 1
      AND NOT EXISTS (SELECT 1 FROM dbo.sections sec WHERE sec.semester_id = @sem_spring2022 AND sec.course_id = c.course_id)
)
INSERT INTO dbo.sections (course_id, semester_id, instructor_id, campus_id, section_number, capacity, enrolled_count, room, status)
SELECT
    cc.course_id,
    @sem_spring2022,
    (SELECT TOP 1 instructor_id FROM dbo.instructors WHERE is_active = 1 ORDER BY NEWID()),
    1 + ABS(CHECKSUM(NEWID())) % 2,
    N'01',
    20 + ABS(CHECKSUM(NEWID())) % 16,
    0,
    (SELECT TOP 1 room FROM #Rooms ORDER BY NEWID()),
    N'OPEN'
FROM candidate_courses cc
WHERE cc.rn <= 25;

PRINT N'Step 2 done: sections added to Fall 2021 and Spring 2022.';

/* ============================================================================
   STEP 3 -- Enrollment targets per semester, then random capacity-respecting
   passes until each semester's live (non-DROPPED) count hits its target.
============================================================================ */

IF OBJECT_ID('tempdb..#Targets') IS NOT NULL DROP TABLE #Targets;
CREATE TABLE #Targets (
    semester_id INT, semester_name NVARCHAR(50), start_date DATE,
    target_count INT, enroll_status NVARCHAR(20), generate_grades BIT
);

INSERT INTO #Targets (semester_id, semester_name, start_date, target_count, enroll_status, generate_grades)
SELECT sem.semester_id, sem.semester_name, sem.start_date, v.target_count, v.enroll_status, v.generate_grades
FROM (VALUES
    (N'Fall 2021',   1400, N'COMPLETED', 1),
    (N'Spring 2022', 1500, N'COMPLETED', 1),
    (N'Fall 2022',   1650, N'COMPLETED', 1),
    (N'Spring 2023', 1750, N'COMPLETED', 1),
    (N'Fall 2023',   1850, N'COMPLETED', 1),
    (N'Spring 2024', 1950, N'COMPLETED', 1),
    (N'Fall 2024',   2100, N'COMPLETED', 1),
    (N'Spring 2025', 2200, N'COMPLETED', 1),
    (N'Fall 2025',   2350, N'ENROLLED',  0),
    (N'Fall 2026',   2600, N'ENROLLED',  0)
) AS v(semester_name, target_count, enroll_status, generate_grades)
JOIN dbo.semesters sem ON sem.semester_name = v.semester_name;

DECLARE @sem_id INT, @sem_name NVARCHAR(50), @sem_start DATE, @target INT, @enroll_status NVARCHAR(20);
DECLARE @current INT, @needed INT, @pass INT, @inserted INT;

DECLARE sem_cursor CURSOR LOCAL FAST_FORWARD FOR
    SELECT semester_id, semester_name, start_date, target_count, enroll_status
    FROM #Targets ORDER BY start_date;

OPEN sem_cursor;
FETCH NEXT FROM sem_cursor INTO @sem_id, @sem_name, @sem_start, @target, @enroll_status;

WHILE @@FETCH_STATUS = 0
BEGIN
    SET @current = (SELECT COUNT(*) FROM dbo.enrollments e
                     JOIN dbo.sections sec ON sec.section_id = e.section_id
                     WHERE sec.semester_id = @sem_id AND e.status <> N'DROPPED');
    SET @needed = @target - @current;
    SET @pass = 0;

    WHILE @needed > 0 AND @pass < 12
    BEGIN
        SET @pass += 1;

        ;WITH sect_remaining AS (
            SELECT sec.section_id, sec.course_id,
                   sec.capacity - (SELECT COUNT(*) FROM dbo.enrollments e
                                    WHERE e.section_id = sec.section_id AND e.status <> N'DROPPED') AS remaining
            FROM dbo.sections sec
            WHERE sec.semester_id = @sem_id
        ),
        eligible_students AS (
            SELECT st.student_id
            FROM dbo.students st
            WHERE st.admission_date <= @sem_start
              AND st.status IN (N'ACTIVE', N'GRADUATED')
              AND (SELECT COUNT(*) FROM dbo.enrollments e2
                   JOIN dbo.sections sec2 ON sec2.section_id = e2.section_id
                   WHERE e2.student_id = st.student_id AND sec2.semester_id = @sem_id
                     AND e2.status <> N'DROPPED') < 7
        ),
        candidates AS (
            SELECT es.student_id, sr.section_id, sr.course_id,
                   ROW_NUMBER() OVER (PARTITION BY es.student_id ORDER BY NEWID()) AS rn_student
            FROM eligible_students es
            CROSS JOIN sect_remaining sr
            WHERE sr.remaining > 0
              AND NOT EXISTS (SELECT 1 FROM dbo.enrollments e3
                               WHERE e3.student_id = es.student_id AND e3.section_id = sr.section_id)
              AND NOT EXISTS (SELECT 1 FROM dbo.enrollments e4
                               JOIN dbo.sections sec4 ON sec4.section_id = e4.section_id
                               WHERE e4.student_id = es.student_id AND sec4.semester_id = @sem_id
                                 AND sec4.course_id = sr.course_id AND e4.status <> N'DROPPED')
        ),
        picked_one_per_student AS (
            SELECT student_id, section_id
            FROM candidates WHERE rn_student = 1
        ),
        picked AS (
            SELECT p.student_id, p.section_id,
                   ROW_NUMBER() OVER (PARTITION BY p.section_id ORDER BY NEWID()) AS rn_section
            FROM picked_one_per_student p
        ),
        final_picked AS (
            SELECT p.student_id, p.section_id
            FROM picked p
            JOIN sect_remaining sr ON sr.section_id = p.section_id
            WHERE p.rn_section <= sr.remaining
        )
        INSERT INTO dbo.enrollments (student_id, section_id, enrollment_date, status, is_repeat, counts_in_gpa)
        SELECT TOP (@needed)
               student_id, section_id,
               DATEADD(DAY, -(ABS(CHECKSUM(NEWID())) % 14), CAST(@sem_start AS DATETIME2)),
               @enroll_status, 0, 1
        FROM final_picked
        ORDER BY NEWID();

        SET @inserted = @@ROWCOUNT;
        SET @needed -= @inserted;

        IF @inserted = 0 BREAK;  -- no more eligible (student, section) pairs this semester
    END

    IF @needed > 0
        PRINT CONCAT(N'WARNING: ', @sem_name, N' short by ', @needed, N' enrollments (ran out of eligible pairs).');
    ELSE
        PRINT CONCAT(N'Step 3: ', @sem_name, N' reached its target.');

    FETCH NEXT FROM sem_cursor INTO @sem_id, @sem_name, @sem_start, @target, @enroll_status;
END

CLOSE sem_cursor;
DEALLOCATE sem_cursor;

/* ============================================================================
   STEP 4 -- Grades for every new COMPLETED enrollment in a past semester,
   using the existing 30/30/40 scale from phase11_grades_scale.sql.
============================================================================ */

INSERT INTO dbo.grades
    (enrollment_id, coursework_mark, midterm_mark, final_mark, total_mark,
     letter_grade, grade_points, result_status, is_submitted, submitted_by, submitted_at)
SELECT
    e.enrollment_id,
    v.cw, v.mt, v.fn, v.total,
    CASE WHEN v.total >= 95 THEN N'A'  WHEN v.total >= 90 THEN N'A-' WHEN v.total >= 85 THEN N'B+'
         WHEN v.total >= 80 THEN N'B'  WHEN v.total >= 75 THEN N'B-' WHEN v.total >= 70 THEN N'C+'
         WHEN v.total >= 65 THEN N'C'  WHEN v.total >= 60 THEN N'C-' WHEN v.total >= 55 THEN N'D+'
         WHEN v.total >= 50 THEN N'D'  ELSE N'F' END,
    CASE WHEN v.total >= 95 THEN 4.00 WHEN v.total >= 90 THEN 3.70 WHEN v.total >= 85 THEN 3.30
         WHEN v.total >= 80 THEN 3.00 WHEN v.total >= 75 THEN 2.70 WHEN v.total >= 70 THEN 2.30
         WHEN v.total >= 65 THEN 2.00 WHEN v.total >= 60 THEN 1.70 WHEN v.total >= 55 THEN 1.30
         WHEN v.total >= 50 THEN 1.00 ELSE 0.00 END,
    CASE WHEN v.total >= 50 THEN N'PASSED' ELSE N'FAILED' END,
    1,
    i.user_id,
    DATEADD(DAY, ABS(CHECKSUM(NEWID())) % NULLIF(DATEDIFF(DAY, sem.grade_entry_start, sem.grade_entry_end), 0), CAST(sem.grade_entry_start AS DATETIME2))
FROM dbo.enrollments e
JOIN dbo.sections sec ON sec.section_id = e.section_id
JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id
JOIN #Targets t ON t.semester_id = sem.semester_id AND t.generate_grades = 1
LEFT JOIN dbo.instructors i ON i.instructor_id = sec.instructor_id
LEFT JOIN dbo.grades g ON g.enrollment_id = e.enrollment_id
CROSS APPLY (
    SELECT
        CAST(55 + ABS(CHECKSUM(NEWID())) % 41 AS DECIMAL(5,2)) AS cw,
        CAST(55 + ABS(CHECKSUM(NEWID())) % 41 AS DECIMAL(5,2)) AS mt,
        CAST(50 + ABS(CHECKSUM(NEWID())) % 46 AS DECIMAL(5,2)) AS fn
) marks
CROSS APPLY (
    SELECT CAST(marks.cw * 0.30 + marks.mt * 0.30 + marks.fn * 0.40 AS DECIMAL(5,2)) AS total
) v2
CROSS APPLY (SELECT marks.cw AS cw, marks.mt AS mt, marks.fn AS fn, v2.total AS total) v
WHERE e.status = N'COMPLETED'
  AND g.grade_id IS NULL;

PRINT CONCAT(N'Step 4 done: ', @@ROWCOUNT, N' grade rows generated.');

/* ============================================================================
   STEP 5 -- Recompute sections.enrolled_count for every section to match the
   schema's own documented invariant (universitymanagmentDB.sql lines 1413-1418).
============================================================================ */

UPDATE sec
SET enrolled_count = ISNULL((SELECT COUNT(*) FROM dbo.enrollments e
                              WHERE e.section_id = sec.section_id
                                AND e.status IN (N'ENROLLED', N'COMPLETED')), 0)
FROM dbo.sections sec;

PRINT N'Step 5 done: enrolled_count recomputed for all sections.';

COMMIT TRANSACTION;
PRINT N'============================================================';
PRINT N'Enrollment trend backfill committed successfully.';
PRINT N'============================================================';

END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    PRINT N'BACKFILL FAILED - transaction rolled back.';
    THROW;
END CATCH
GO

/* ============================================================================
   STEP 6 -- Verification (run after commit)
============================================================================ */

PRINT N'--- Final enrollment trend (chart query) ---';
SELECT sem.semester_name, COUNT(e.enrollment_id) AS enrollment_count
FROM dbo.semesters sem
LEFT JOIN dbo.sections    sec ON sec.semester_id = sem.semester_id
LEFT JOIN dbo.enrollments e   ON e.section_id    = sec.section_id AND e.status <> N'DROPPED'
GROUP BY sem.semester_id, sem.semester_name, sem.start_date
ORDER BY sem.start_date;

PRINT N'--- enrolled_count mismatches (expect 0 rows) ---';
SELECT s.section_id
FROM dbo.sections s
WHERE s.enrolled_count <> (SELECT COUNT(*) FROM dbo.enrollments e
                            WHERE e.section_id = s.section_id
                              AND e.status IN (N'ENROLLED', N'COMPLETED'));

PRINT N'--- sections over capacity (expect 0 rows) ---';
SELECT section_id, capacity, enrolled_count FROM dbo.sections WHERE enrolled_count > capacity;

PRINT N'--- duplicate student-section enrollments (expect 0 rows) ---';
SELECT student_id, section_id, COUNT(*) FROM dbo.enrollments GROUP BY student_id, section_id HAVING COUNT(*) > 1;
GO
