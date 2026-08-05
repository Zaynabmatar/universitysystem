/* ============================================================================
   UNIVERSITY MANAGEMENT SYSTEM  (UMS)
   FILE    : 00_full_database.sql
   TARGET  : Microsoft SQL Server 2019 or newer
   PURPOSE : Creates the database, all 27 tables, every constraint, every
             index, and a small realistic set of sample data.

   SAFE TO RE-RUN: every table is dropped (in reverse dependency order) and
   rebuilt. Re-running DELETES ALL DATA. That is intentional.

   STRUCTURE
     PART 0 - Create / use database, session settings
     PART 1 - Drop all tables (reverse dependency order)
     PART 2 - Create all tables   ) one transaction,
     PART 3 - Create all indexes  ) one TRY/CATCH
     PART 4 - Sample data         - second transaction, second TRY/CATCH
     PART 5 - Verification summary

   NAMING RULES (unchanged from the original project)
     tables    : snake_case, plural
     PK column : <singular_table>_id
     all text  : NVARCHAR (never VARCHAR) so Arabic text works
     booleans  : BIT
     timestamps: DATETIME2
     money     : DECIMAL(10,2)
============================================================================ */


/* ============================================================================
   PART 0 - DATABASE AND SESSION SETTINGS
============================================================================ */
IF DB_ID(N'universitymanagementDB') IS NULL
BEGIN
    PRINT N'Creating database universitymanagementDB ...';
    EXEC (N'CREATE DATABASE universitymanagementDB');
END
ELSE
BEGIN
    PRINT N'Database universitymanagementDB already exists - reusing it.';
END
GO
USE universitymanagementDB;
GO

/* ----------------------------------------------------------------------------
   REQUIRED SESSION SETTINGS
   QUOTED_IDENTIFIER and ANSI_NULLS must be ON to create a FILTERED INDEX
   (UX_semesters_one_current further down). SSMS sets them ON by default, but
   some drivers and tools do not - and if they are OFF, the index creation
   fails with error 1934 and rolls back the ENTIRE schema. Setting them
   explicitly makes the script safe no matter what runs it.

   XACT_ABORT ON: any run-time error aborts the transaction immediately,
   which is what makes the ROLLBACK in the CATCH blocks reliable.
---------------------------------------------------------------------------- */
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
SET XACT_ABORT ON;
GO


/* ============================================================================
   PART 1 + 2 + 3 - DROP, CREATE TABLES, CREATE INDEXES
   ----------------------------------------------------------------------------
   All of this runs inside ONE transaction. SQL Server supports DDL inside
   transactions, so if any single CREATE TABLE fails, the whole schema is
   rolled back and the database is left exactly as it was.
   There is deliberately no GO inside this block - a GO would split it into
   separate batches and break the TRY/CATCH.
============================================================================ */
BEGIN TRY
    BEGIN TRANSACTION;

    PRINT N'--- Dropping existing tables (children first) ---';

    DROP TABLE IF EXISTS dbo.audit_log;
    DROP TABLE IF EXISTS dbo.notifications;
    DROP TABLE IF EXISTS dbo.financial_transactions;
    DROP TABLE IF EXISTS dbo.payments;
    DROP TABLE IF EXISTS dbo.invoice_items;
    DROP TABLE IF EXISTS dbo.student_invoices;
    DROP TABLE IF EXISTS dbo.fee_types;
    DROP TABLE IF EXISTS dbo.chat_messages;
    DROP TABLE IF EXISTS dbo.chat_conversations;
    DROP TABLE IF EXISTS dbo.advisors_directory;
    DROP TABLE IF EXISTS dbo.university_news;
    DROP TABLE IF EXISTS dbo.assignments;
    DROP TABLE IF EXISTS dbo.waitlist;
    DROP TABLE IF EXISTS dbo.grades;
    DROP TABLE IF EXISTS dbo.enrollments;
    DROP TABLE IF EXISTS dbo.section_schedules;
    DROP TABLE IF EXISTS dbo.sections;
    DROP TABLE IF EXISTS dbo.semesters;
    DROP TABLE IF EXISTS dbo.program_requirements;
    DROP TABLE IF EXISTS dbo.course_prerequisites;
    DROP TABLE IF EXISTS dbo.courses;
    DROP TABLE IF EXISTS dbo.instructors;
    DROP TABLE IF EXISTS dbo.students;
    DROP TABLE IF EXISTS dbo.admins;
    DROP TABLE IF EXISTS dbo.users;
    DROP TABLE IF EXISTS dbo.programs;
    DROP TABLE IF EXISTS dbo.campuses;
    DROP TABLE IF EXISTS dbo.departments;


    /* ========================================================================
       MODULE A - ACADEMIC STRUCTURE
       ======================================================================== */
    PRINT N'--- MODULE A: academic structure ---';

    /* ---- A1. departments : the root table -------------------------------- */
    CREATE TABLE dbo.departments
    (
        dept_id   INT           IDENTITY(1,1) NOT NULL,
        dept_code NVARCHAR(10)  NOT NULL,
        dept_name NVARCHAR(100) NOT NULL,
        is_active BIT           NOT NULL
                  CONSTRAINT DF_departments_is_active DEFAULT (1),

        CONSTRAINT PK_departments      PRIMARY KEY (dept_id),
        CONSTRAINT UQ_departments_code UNIQUE      (dept_code)
    );

    /* ---- A2. campuses : used ONLY to display the class location ----------- */
    CREATE TABLE dbo.campuses
    (
        campus_id   INT           IDENTITY(1,1) NOT NULL,
        campus_name NVARCHAR(100) NOT NULL,
        location    NVARCHAR(200) NULL,
        is_active   BIT           NOT NULL
                    CONSTRAINT DF_campuses_is_active DEFAULT (1),

        CONSTRAINT PK_campuses      PRIMARY KEY (campus_id),
        CONSTRAINT UQ_campuses_name UNIQUE      (campus_name)
    );

    /* ---- A3. programs : the degree plan ----------------------------------- */
    CREATE TABLE dbo.programs
    (
        program_id             INT           IDENTITY(1,1) NOT NULL,
        dept_id                INT           NOT NULL,
        program_code           NVARCHAR(10)  NOT NULL,
        program_name           NVARCHAR(100) NOT NULL,
        degree_type            NVARCHAR(20)  NOT NULL,
        total_credits_required INT           NOT NULL,
        is_active              BIT           NOT NULL
                               CONSTRAINT DF_programs_is_active DEFAULT (1),

        CONSTRAINT PK_programs            PRIMARY KEY (program_id),
        CONSTRAINT UQ_programs_code       UNIQUE      (program_code),
        CONSTRAINT FK_programs_department FOREIGN KEY (dept_id)
            REFERENCES dbo.departments (dept_id) ON DELETE NO ACTION,

        CONSTRAINT CK_programs_degree_type CHECK (degree_type IN (N'BACHELOR', N'MASTER')),
        CONSTRAINT CK_programs_credits     CHECK (total_credits_required > 0)
    );


    /* ========================================================================
       MODULE B - IDENTITY AND ACCESS
       ======================================================================== */
    PRINT N'--- MODULE B: identity and access ---';

    /* ---- B1. users : ONE login row per person, three roles ---------------- */
    CREATE TABLE dbo.users
    (
        user_id       INT           IDENTITY(1,1) NOT NULL,
        username      NVARCHAR(50)  NOT NULL,
        password_hash NVARCHAR(255) NOT NULL,   -- BCrypt hash, NEVER plain text
        role          NVARCHAR(20)  NOT NULL,
        is_active     BIT           NOT NULL
                      CONSTRAINT DF_users_is_active DEFAULT (1),
        last_login    DATETIME2     NULL,
        created_at    DATETIME2     NOT NULL
                      CONSTRAINT DF_users_created_at DEFAULT (GETDATE()),

        CONSTRAINT PK_users          PRIMARY KEY (user_id),
        CONSTRAINT UQ_users_username UNIQUE      (username),
        CONSTRAINT CK_users_role     CHECK (role IN (N'ADMIN', N'INSTRUCTOR', N'STUDENT'))
    );

    /* ---- B2. students -----------------------------------------------------
       cumulative_gpa / completed_credits are CACHED values
       (controlled denormalization) refreshed by sp_RecalculateStudentGPA.
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.students
    (
        student_id        INT           IDENTITY(1,1) NOT NULL,
        user_id           INT           NOT NULL,
        student_number    NVARCHAR(20)  NOT NULL,
        first_name        NVARCHAR(50)  NOT NULL,
        last_name         NVARCHAR(50)  NOT NULL,
        email             NVARCHAR(100) NOT NULL,
        phone             NVARCHAR(20)  NULL,
        date_of_birth     DATE          NULL,
        gender            NVARCHAR(10)  NULL,
        address           NVARCHAR(200) NULL,
        program_id        INT           NOT NULL,
        admission_date    DATE          NOT NULL,
        status            NVARCHAR(20)  NOT NULL
                          CONSTRAINT DF_students_status DEFAULT (N'ACTIVE'),
        academic_standing NVARCHAR(20)  NOT NULL
                          CONSTRAINT DF_students_standing DEFAULT (N'NEW'),
        cumulative_gpa    DECIMAL(3,2)  NOT NULL
                          CONSTRAINT DF_students_gpa DEFAULT (0.00),
        completed_credits INT           NOT NULL
                          CONSTRAINT DF_students_credits DEFAULT (0),
        probation_count   INT           NOT NULL
                          CONSTRAINT DF_students_probation DEFAULT (0),

        CONSTRAINT PK_students                PRIMARY KEY (student_id),
        CONSTRAINT UQ_students_user           UNIQUE      (user_id),
        CONSTRAINT UQ_students_student_number UNIQUE      (student_number),
        CONSTRAINT UQ_students_email          UNIQUE      (email),

        CONSTRAINT FK_students_user    FOREIGN KEY (user_id)
            REFERENCES dbo.users (user_id)       ON DELETE NO ACTION,
        CONSTRAINT FK_students_program FOREIGN KEY (program_id)
            REFERENCES dbo.programs (program_id) ON DELETE NO ACTION,

        CONSTRAINT CK_students_gender    CHECK (gender IN (N'MALE', N'FEMALE')),
        CONSTRAINT CK_students_status    CHECK (status IN (N'ACTIVE', N'SUSPENDED', N'GRADUATED', N'WITHDRAWN')),
        CONSTRAINT CK_students_standing  CHECK (academic_standing IN (N'NEW', N'GOOD', N'DEANS_LIST', N'PROBATION', N'SUSPENDED')),
        CONSTRAINT CK_students_gpa       CHECK (cumulative_gpa    BETWEEN 0.00 AND 4.00),
        CONSTRAINT CK_students_credits   CHECK (completed_credits >= 0),
        CONSTRAINT CK_students_probation CHECK (probation_count   >= 0)
    );

    /* ---- B3. instructors --------------------------------------------------- */
    CREATE TABLE dbo.instructors
    (
        instructor_id   INT           IDENTITY(1,1) NOT NULL,
        user_id         INT           NOT NULL,
        employee_number NVARCHAR(20)  NOT NULL,
        first_name      NVARCHAR(50)  NOT NULL,
        last_name       NVARCHAR(50)  NOT NULL,
        email           NVARCHAR(100) NOT NULL,
        phone           NVARCHAR(20)  NULL,
        dept_id         INT           NOT NULL,
        academic_rank   NVARCHAR(30)  NOT NULL,
        hire_date       DATE          NULL,
        is_active       BIT           NOT NULL
                        CONSTRAINT DF_instructors_is_active DEFAULT (1),

        CONSTRAINT PK_instructors             PRIMARY KEY (instructor_id),
        CONSTRAINT UQ_instructors_user        UNIQUE      (user_id),
        CONSTRAINT UQ_instructors_employee_no UNIQUE      (employee_number),
        CONSTRAINT UQ_instructors_email       UNIQUE      (email),

        CONSTRAINT FK_instructors_user       FOREIGN KEY (user_id)
            REFERENCES dbo.users (user_id)       ON DELETE NO ACTION,
        CONSTRAINT FK_instructors_department FOREIGN KEY (dept_id)
            REFERENCES dbo.departments (dept_id) ON DELETE NO ACTION,

        CONSTRAINT CK_instructors_rank CHECK (academic_rank IN (N'PROFESSOR', N'ASSOCIATE', N'ASSISTANT', N'LECTURER'))
    );

    /* ---- B4. admins ---------------------------------------------------------
       Mirrors students/instructors: its own IDENTITY(1,1) so admins get their
       own visible ID sequence (1, 2, 3, ...) independent from students and
       instructors, one-to-one with dbo.users via a UNIQUE user_id FK.
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.admins
    (
        admin_id  INT IDENTITY(1,1) NOT NULL,
        user_id   INT               NOT NULL,
        is_active BIT               NOT NULL
                  CONSTRAINT DF_admins_is_active DEFAULT (1),

        CONSTRAINT PK_admins      PRIMARY KEY (admin_id),
        CONSTRAINT UQ_admins_user UNIQUE      (user_id),

        CONSTRAINT FK_admins_user FOREIGN KEY (user_id)
            REFERENCES dbo.users (user_id) ON DELETE NO ACTION
    );


    /* ========================================================================
       MODULE C - COURSE CATALOGUE
       ======================================================================== */
    PRINT N'--- MODULE C: course catalogue ---';

    /* ---- C1. courses : permanent catalogue entry -------------------------- */
    CREATE TABLE dbo.courses
    (
        course_id    INT           IDENTITY(1,1) NOT NULL,
        course_code  NVARCHAR(10)  NOT NULL,
        course_title NVARCHAR(100) NOT NULL,
        description  NVARCHAR(500) NULL,
        credits      INT           NOT NULL,
        dept_id      INT           NOT NULL,
        level_year   INT           NOT NULL,
        is_active    BIT           NOT NULL
                     CONSTRAINT DF_courses_is_active DEFAULT (1),

        CONSTRAINT PK_courses            PRIMARY KEY (course_id),
        CONSTRAINT UQ_courses_code       UNIQUE      (course_code),
        CONSTRAINT FK_courses_department FOREIGN KEY (dept_id)
            REFERENCES dbo.departments (dept_id) ON DELETE NO ACTION,

        CONSTRAINT CK_courses_credits    CHECK (credits    BETWEEN 1 AND 6),
        CONSTRAINT CK_courses_level_year CHECK (level_year BETWEEN 1 AND 4)
    );

    /* ---- C2. course_prerequisites : SELF-REFERENCING many-to-many ---------
       Both FKs point at dbo.courses. If either were ON DELETE CASCADE,
       SQL Server would raise error 1785 (multiple cascade paths).
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.course_prerequisites
    (
        prereq_id              INT          IDENTITY(1,1) NOT NULL,
        course_id              INT          NOT NULL,
        prerequisite_course_id INT          NOT NULL,
        min_grade_points       DECIMAL(3,2) NOT NULL
                               CONSTRAINT DF_course_prerequisites_min_gp DEFAULT (1.00),

        CONSTRAINT PK_course_prerequisites      PRIMARY KEY (prereq_id),
        CONSTRAINT UQ_course_prerequisites_pair UNIQUE      (course_id, prerequisite_course_id),

        CONSTRAINT FK_course_prerequisites_course FOREIGN KEY (course_id)
            REFERENCES dbo.courses (course_id) ON DELETE NO ACTION,
        CONSTRAINT FK_course_prerequisites_prereq FOREIGN KEY (prerequisite_course_id)
            REFERENCES dbo.courses (course_id) ON DELETE NO ACTION,

        CONSTRAINT CK_course_prerequisites_not_self CHECK (course_id <> prerequisite_course_id),
        CONSTRAINT CK_course_prerequisites_min_gp   CHECK (min_grade_points BETWEEN 0.00 AND 4.00)
    );

    /* ---- C3. program_requirements : the degree plan in table form --------- */
    CREATE TABLE dbo.program_requirements
    (
        requirement_id       INT IDENTITY(1,1) NOT NULL,
        program_id           INT NOT NULL,
        course_id            INT NOT NULL,
        is_mandatory         BIT NOT NULL
                             CONSTRAINT DF_program_requirements_mandatory DEFAULT (1),
        recommended_semester INT NOT NULL,

        CONSTRAINT PK_program_requirements      PRIMARY KEY (requirement_id),
        CONSTRAINT UQ_program_requirements_pair UNIQUE      (program_id, course_id),

        CONSTRAINT FK_program_requirements_program FOREIGN KEY (program_id)
            REFERENCES dbo.programs (program_id) ON DELETE NO ACTION,
        CONSTRAINT FK_program_requirements_course  FOREIGN KEY (course_id)
            REFERENCES dbo.courses (course_id)   ON DELETE NO ACTION,

        CONSTRAINT CK_program_requirements_semester CHECK (recommended_semester BETWEEN 1 AND 8)
    );


    /* ========================================================================
       MODULE D - SEMESTERS, SECTIONS, SCHEDULES
       ======================================================================== */
    PRINT N'--- MODULE D: semesters, sections, schedules ---';

    /* ---- D1. semesters : the CLOCK of the whole system -------------------- */
    CREATE TABLE dbo.semesters
    (
        semester_id        INT          IDENTITY(1,1) NOT NULL,
        semester_name      NVARCHAR(50) NOT NULL,
        academic_year      NVARCHAR(9)  NOT NULL,
        term               NVARCHAR(10) NOT NULL,
        start_date         DATE         NOT NULL,
        end_date           DATE         NOT NULL,
        registration_start DATETIME2    NOT NULL,
        registration_end   DATETIME2    NOT NULL,
        drop_deadline      DATE         NOT NULL,
        withdraw_deadline  DATE         NOT NULL,
        grade_entry_start  DATE         NOT NULL,
        grade_entry_end    DATE         NOT NULL,
        is_current         BIT          NOT NULL
                           CONSTRAINT DF_semesters_is_current DEFAULT (0),

        CONSTRAINT PK_semesters      PRIMARY KEY (semester_id),
        CONSTRAINT UQ_semesters_name UNIQUE      (semester_name),

        CONSTRAINT CK_semesters_dates         CHECK (end_date > start_date),
        CONSTRAINT CK_semesters_term          CHECK (term IN (N'FALL', N'SPRING', N'SUMMER')),
        CONSTRAINT CK_semesters_registration  CHECK (registration_end  >  registration_start),
        CONSTRAINT CK_semesters_deadlines     CHECK (withdraw_deadline >= drop_deadline),
        CONSTRAINT CK_semesters_grade_window  CHECK (grade_entry_end   >= grade_entry_start),
        CONSTRAINT CK_semesters_academic_year CHECK (academic_year LIKE N'[0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]')
    );

    /* ---- D2. sections : ONE offering of ONE course in ONE semester --------
       campus_id + room give the display line "Khaldeh Campus / B204".
       enrolled_count is a CACHED counter (a trigger will maintain it later).
       instructor_id is NULL when the section is published as "TBA".
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.sections
    (
        section_id     INT          IDENTITY(1,1) NOT NULL,
        course_id      INT          NOT NULL,
        semester_id    INT          NOT NULL,
        instructor_id  INT          NULL,
        campus_id      INT          NOT NULL,
        section_number NVARCHAR(10) NOT NULL,
        capacity       INT          NOT NULL,
        enrolled_count INT          NOT NULL
                       CONSTRAINT DF_sections_enrolled DEFAULT (0),
        room           NVARCHAR(20) NULL,
        status         NVARCHAR(20) NOT NULL
                       CONSTRAINT DF_sections_status DEFAULT (N'OPEN'),

        CONSTRAINT PK_sections PRIMARY KEY (section_id),
        CONSTRAINT UQ_sections_course_semester_number UNIQUE (course_id, semester_id, section_number),

        CONSTRAINT FK_sections_course     FOREIGN KEY (course_id)
            REFERENCES dbo.courses (course_id)         ON DELETE NO ACTION,
        CONSTRAINT FK_sections_semester   FOREIGN KEY (semester_id)
            REFERENCES dbo.semesters (semester_id)     ON DELETE NO ACTION,
        CONSTRAINT FK_sections_instructor FOREIGN KEY (instructor_id)
            REFERENCES dbo.instructors (instructor_id) ON DELETE NO ACTION,
        CONSTRAINT FK_sections_campus     FOREIGN KEY (campus_id)
            REFERENCES dbo.campuses (campus_id)        ON DELETE NO ACTION,

        CONSTRAINT CK_sections_capacity CHECK (capacity > 0),
        CONSTRAINT CK_sections_enrolled CHECK (enrolled_count >= 0),
        CONSTRAINT CK_sections_status   CHECK (status IN (N'OPEN', N'CLOSED', N'CANCELLED'))
    );

    /* ---- D3. section_schedules : weekly meeting times ---------------------
       CASCADE IS SAFE HERE: a schedule row is meaningless without its section,
       and there is exactly ONE path from sections down to this table.
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.section_schedules
    (
        schedule_id INT         IDENTITY(1,1) NOT NULL,
        section_id  INT         NOT NULL,
        day_of_week NVARCHAR(3) NOT NULL,
        start_time  TIME        NOT NULL,
        end_time    TIME        NOT NULL,

        CONSTRAINT PK_section_schedules PRIMARY KEY (schedule_id),
        CONSTRAINT FK_section_schedules_section FOREIGN KEY (section_id)
            REFERENCES dbo.sections (section_id) ON DELETE CASCADE,

        CONSTRAINT CK_section_schedules_day   CHECK (day_of_week IN (N'SUN', N'MON', N'TUE', N'WED', N'THU', N'FRI', N'SAT')),
        CONSTRAINT CK_section_schedules_times CHECK (end_time > start_time)
    );


    /* ========================================================================
       MODULE E - REGISTRATION AND GRADES
       ======================================================================== */
    PRINT N'--- MODULE E: registration and grades ---';

    /* ---- E1. enrollments : many-to-many bridge students <-> sections ------ */
    CREATE TABLE dbo.enrollments
    (
        enrollment_id   INT          IDENTITY(1,1) NOT NULL,
        student_id      INT          NOT NULL,
        section_id      INT          NOT NULL,
        enrollment_date DATETIME2    NOT NULL
                        CONSTRAINT DF_enrollments_date DEFAULT (GETDATE()),
        status          NVARCHAR(20) NOT NULL
                        CONSTRAINT DF_enrollments_status DEFAULT (N'ENROLLED'),
        is_repeat       BIT          NOT NULL
                        CONSTRAINT DF_enrollments_repeat DEFAULT (0),
        counts_in_gpa   BIT          NOT NULL
                        CONSTRAINT DF_enrollments_counts DEFAULT (1),

        CONSTRAINT PK_enrollments PRIMARY KEY (enrollment_id),
        CONSTRAINT UQ_enrollments_student_section UNIQUE (student_id, section_id),

        CONSTRAINT FK_enrollments_student FOREIGN KEY (student_id)
            REFERENCES dbo.students (student_id) ON DELETE NO ACTION,
        CONSTRAINT FK_enrollments_section FOREIGN KEY (section_id)
            REFERENCES dbo.sections (section_id) ON DELETE NO ACTION,

        CONSTRAINT CK_enrollments_status CHECK (status IN (N'ENROLLED', N'DROPPED', N'WITHDRAWN', N'COMPLETED'))
    );

    /* ---- E2. grades : 1-to-1 with enrollments -----------------------------
       Components: coursework_mark (30%), midterm_mark (30%), final_mark (40%) -
       project_details.md Section 5.1. total_mark is written explicitly by the
       application. result_status is what the student sees next to the numeric
       total. grade_points is KEPT - GPA, prerequisites and reports all depend
       on it. letter_grade also allows W (withdrawn) and I (incomplete), Section
       5.2 - neither counts in the GPA.
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.grades
    (
        grade_id         INT          IDENTITY(1,1) NOT NULL,
        enrollment_id    INT          NOT NULL,
        coursework_mark  DECIMAL(5,2) NULL,   -- weight 30%
        midterm_mark     DECIMAL(5,2) NULL,   -- weight 30%
        final_mark       DECIMAL(5,2) NULL,   -- weight 40%
        total_mark       DECIMAL(5,2) NULL,
        letter_grade     NVARCHAR(2)  NULL,
        grade_points     DECIMAL(3,2) NULL,
        result_status    NVARCHAR(10) NULL,
        is_submitted     BIT          NOT NULL
                         CONSTRAINT DF_grades_is_submitted DEFAULT (0),
        submitted_by     INT          NULL,
        submitted_at     DATETIME2    NULL,
        last_modified_by INT          NULL,
        last_modified_at DATETIME2    NULL,

        CONSTRAINT PK_grades            PRIMARY KEY (grade_id),
        CONSTRAINT UQ_grades_enrollment UNIQUE      (enrollment_id),

        CONSTRAINT FK_grades_enrollment   FOREIGN KEY (enrollment_id)
            REFERENCES dbo.enrollments (enrollment_id) ON DELETE NO ACTION,
        CONSTRAINT FK_grades_submitted_by FOREIGN KEY (submitted_by)
            REFERENCES dbo.users (user_id)             ON DELETE NO ACTION,
        CONSTRAINT FK_grades_modified_by  FOREIGN KEY (last_modified_by)
            REFERENCES dbo.users (user_id)             ON DELETE NO ACTION,

        CONSTRAINT CK_grades_coursework CHECK (coursework_mark BETWEEN 0 AND 100),
        CONSTRAINT CK_grades_midterm    CHECK (midterm_mark    BETWEEN 0 AND 100),
        CONSTRAINT CK_grades_final      CHECK (final_mark      BETWEEN 0 AND 100),
        CONSTRAINT CK_grades_total      CHECK (total_mark      BETWEEN 0 AND 100),
        CONSTRAINT CK_grades_points     CHECK (grade_points    BETWEEN 0.00 AND 4.00),
        CONSTRAINT CK_grades_letter     CHECK (letter_grade IN
            (N'A', N'A-', N'B+', N'B', N'B-', N'C+', N'C', N'C-', N'D+', N'D', N'F', N'W', N'I')),
        CONSTRAINT CK_grades_result     CHECK (result_status IN (N'PASSED', N'FAILED'))
    );

    /* ---- E3. waitlist : the queue for a FULL section ---------------------- */
    CREATE TABLE dbo.waitlist
    (
        waitlist_id INT          IDENTITY(1,1) NOT NULL,
        section_id  INT          NOT NULL,
        student_id  INT          NOT NULL,
        position    INT          NOT NULL,
        status      NVARCHAR(20) NOT NULL
                    CONSTRAINT DF_waitlist_status DEFAULT (N'WAITING'),
        created_at  DATETIME2    NOT NULL
                    CONSTRAINT DF_waitlist_created_at DEFAULT (GETDATE()),

        CONSTRAINT PK_waitlist                 PRIMARY KEY (waitlist_id),
        CONSTRAINT UQ_waitlist_section_student UNIQUE      (section_id, student_id),

        CONSTRAINT FK_waitlist_section FOREIGN KEY (section_id)
            REFERENCES dbo.sections (section_id) ON DELETE NO ACTION,
        CONSTRAINT FK_waitlist_student FOREIGN KEY (student_id)
            REFERENCES dbo.students (student_id) ON DELETE NO ACTION,

        CONSTRAINT CK_waitlist_position CHECK (position > 0),
        CONSTRAINT CK_waitlist_status   CHECK (status IN (N'WAITING', N'PROMOTED', N'CANCELLED'))
    );


    /* ========================================================================
       MODULE F - ASSIGNMENTS
       ======================================================================== */
    PRINT N'--- MODULE F: assignments ---';

    /* An assignment belongs to a SECTION (one offering), not to a course,
       so different sections of CS201 can have different assignments.       */
    CREATE TABLE dbo.assignments
    (
        assignment_id INT            IDENTITY(1,1) NOT NULL,
        section_id    INT            NOT NULL,
        title         NVARCHAR(150)  NOT NULL,
        description   NVARCHAR(1000) NULL,
        assigned_date DATETIME2      NOT NULL
                      CONSTRAINT DF_assignments_assigned DEFAULT (GETDATE()),
        due_date      DATETIME2      NOT NULL,
        maximum_mark  DECIMAL(5,2)   NULL,
        created_by    INT            NOT NULL,
        status        NVARCHAR(20)   NOT NULL
                      CONSTRAINT DF_assignments_status DEFAULT (N'ACTIVE'),
        created_at    DATETIME2      NOT NULL
                      CONSTRAINT DF_assignments_created_at DEFAULT (GETDATE()),

        CONSTRAINT PK_assignments PRIMARY KEY (assignment_id),

        CONSTRAINT FK_assignments_section FOREIGN KEY (section_id)
            REFERENCES dbo.sections (section_id) ON DELETE NO ACTION,
        CONSTRAINT FK_assignments_creator FOREIGN KEY (created_by)
            REFERENCES dbo.users (user_id)       ON DELETE NO ACTION,

        CONSTRAINT CK_assignments_status  CHECK (status IN (N'ACTIVE', N'CLOSED', N'CANCELLED')),
        CONSTRAINT CK_assignments_dates   CHECK (due_date >= assigned_date),
        CONSTRAINT CK_assignments_max_mrk CHECK (maximum_mark IS NULL OR maximum_mark > 0)
    );


    /* ========================================================================
       MODULE G - UNIVERSITY NEWS
       ======================================================================== */
    PRINT N'--- MODULE G: university news ---';

    /* General public news. NOT the same as notifications, which are personal
       and addressed to exactly one user.                                    */
    CREATE TABLE dbo.university_news
    (
        news_id          INT            IDENTITY(1,1) NOT NULL,
        title            NVARCHAR(150)  NOT NULL,
        content          NVARCHAR(MAX)  NOT NULL,
        image_path       NVARCHAR(500)  NULL,
        category         NVARCHAR(50)   NULL,
        publication_date DATETIME2      NOT NULL
                         CONSTRAINT DF_university_news_pubdate DEFAULT (GETDATE()),
        expiry_date      DATETIME2      NULL,
        is_published     BIT            NOT NULL
                         CONSTRAINT DF_university_news_published DEFAULT (1),
        created_by       INT            NOT NULL,
        created_at       DATETIME2      NOT NULL
                         CONSTRAINT DF_university_news_created_at DEFAULT (GETDATE()),
        updated_at       DATETIME2      NULL,

        CONSTRAINT PK_university_news PRIMARY KEY (news_id),

        CONSTRAINT FK_university_news_creator FOREIGN KEY (created_by)
            REFERENCES dbo.users (user_id) ON DELETE NO ACTION,

        CONSTRAINT CK_university_news_expiry CHECK (expiry_date IS NULL OR expiry_date > publication_date)
    );


    /* ========================================================================
       MODULE H - ADVISORS DIRECTORY
       ======================================================================== */
    PRINT N'--- MODULE H: advisors directory ---';

    /* A read-only contact list shown inside My Account.
       Deliberately standalone: no student_advisors table, no FK to students. */
    CREATE TABLE dbo.advisors_directory
    (
        advisor_id       INT           IDENTITY(1,1) NOT NULL,
        full_name        NVARCHAR(100) NOT NULL,
        department       NVARCHAR(100) NULL,
        university_email NVARCHAR(150) NOT NULL,
        office           NVARCHAR(100) NULL,
        phone            NVARCHAR(30)  NULL,
        is_active        BIT           NOT NULL
                         CONSTRAINT DF_advisors_directory_is_active DEFAULT (1),
        created_at       DATETIME2     NOT NULL
                         CONSTRAINT DF_advisors_directory_created_at DEFAULT (GETDATE()),
        updated_at       DATETIME2     NULL,

        CONSTRAINT PK_advisors_directory       PRIMARY KEY (advisor_id),
        CONSTRAINT UQ_advisors_directory_email UNIQUE      (university_email)
    );


    /* ========================================================================
       MODULE I - CHATBOX (student <-> administration)
       ======================================================================== */
    PRINT N'--- MODULE I: chatbox ---';

    /* ---- I1. chat_conversations : the ticket header ----------------------- */
    CREATE TABLE dbo.chat_conversations
    (
        conversation_id        INT           IDENTITY(1,1) NOT NULL,
        student_id             INT           NOT NULL,
        assigned_admin_user_id INT           NULL,   -- NULL = not yet picked up
        subject                NVARCHAR(150) NOT NULL,
        category               NVARCHAR(30)  NOT NULL,
        status                 NVARCHAR(20)  NOT NULL
                               CONSTRAINT DF_chat_conversations_status DEFAULT (N'OPEN'),
        created_at             DATETIME2     NOT NULL
                               CONSTRAINT DF_chat_conversations_created_at DEFAULT (GETDATE()),
        updated_at             DATETIME2     NULL,

        CONSTRAINT PK_chat_conversations PRIMARY KEY (conversation_id),

        CONSTRAINT FK_chat_conversations_student FOREIGN KEY (student_id)
            REFERENCES dbo.students (student_id) ON DELETE NO ACTION,
        CONSTRAINT FK_chat_conversations_admin   FOREIGN KEY (assigned_admin_user_id)
            REFERENCES dbo.users (user_id)       ON DELETE NO ACTION,

        CONSTRAINT CK_chat_conversations_category CHECK (category IN (N'ACADEMIC', N'REGISTRATION', N'FINANCE', N'TECHNICAL', N'OTHER')),
        CONSTRAINT CK_chat_conversations_status   CHECK (status   IN (N'OPEN', N'IN_PROGRESS', N'RESOLVED', N'CLOSED'))
    );

    /* ---- I2. chat_messages -----------------------------------------------
       CASCADE IS SAFE: a message cannot exist without its conversation, and
       there is exactly one path from chat_conversations down to here.
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.chat_messages
    (
        message_id      INT           IDENTITY(1,1) NOT NULL,
        conversation_id INT           NOT NULL,
        sender_user_id  INT           NOT NULL,
        message_text    NVARCHAR(MAX) NOT NULL,
        sent_at         DATETIME2     NOT NULL
                        CONSTRAINT DF_chat_messages_sent_at DEFAULT (GETDATE()),
        is_read         BIT           NOT NULL
                        CONSTRAINT DF_chat_messages_is_read DEFAULT (0),

        CONSTRAINT PK_chat_messages PRIMARY KEY (message_id),

        CONSTRAINT FK_chat_messages_conversation FOREIGN KEY (conversation_id)
            REFERENCES dbo.chat_conversations (conversation_id) ON DELETE CASCADE,
        CONSTRAINT FK_chat_messages_sender       FOREIGN KEY (sender_user_id)
            REFERENCES dbo.users (user_id)                     ON DELETE NO ACTION
    );


    /* ========================================================================
       MODULE J - FINANCE AND PAYMENTS
       ======================================================================== */
    PRINT N'--- MODULE J: finance and payments ---';

    /* ---- J1. fee_types : Tuition, Registration, Laboratory, Library, Late - */
    CREATE TABLE dbo.fee_types
    (
        fee_type_id INT           IDENTITY(1,1) NOT NULL,
        fee_name    NVARCHAR(100) NOT NULL,
        description NVARCHAR(500) NULL,
        is_active   BIT           NOT NULL
                    CONSTRAINT DF_fee_types_is_active DEFAULT (1),

        CONSTRAINT PK_fee_types      PRIMARY KEY (fee_type_id),
        CONSTRAINT UQ_fee_types_name UNIQUE      (fee_name)
    );

    /* ---- J2. student_invoices : one bill, one student, one semester -------
       paid_amount and remaining_amount are CACHED totals, kept in step with
       the payments table by the application (same idea as enrolled_count).
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.student_invoices
    (
        invoice_id         INT            IDENTITY(1,1) NOT NULL,
        student_id         INT            NOT NULL,
        semester_id        INT            NOT NULL,
        invoice_number     NVARCHAR(50)   NOT NULL,
        issue_date         DATE           NOT NULL,
        due_date           DATE           NOT NULL,
        original_amount    DECIMAL(10,2)  NOT NULL,
        discount_amount    DECIMAL(10,2)  NOT NULL
                           CONSTRAINT DF_student_invoices_discount DEFAULT (0),
        scholarship_amount DECIMAL(10,2)  NOT NULL
                           CONSTRAINT DF_student_invoices_scholarship DEFAULT (0),
        late_fee_amount    DECIMAL(10,2)  NOT NULL
                           CONSTRAINT DF_student_invoices_latefee DEFAULT (0),
        total_amount       DECIMAL(10,2)  NOT NULL,
        paid_amount        DECIMAL(10,2)  NOT NULL
                           CONSTRAINT DF_student_invoices_paid DEFAULT (0),
        remaining_amount   DECIMAL(10,2)  NOT NULL,
        status             NVARCHAR(20)   NOT NULL
                           CONSTRAINT DF_student_invoices_status DEFAULT (N'UNPAID'),
        created_at         DATETIME2      NOT NULL
                           CONSTRAINT DF_student_invoices_created_at DEFAULT (GETDATE()),
        updated_at         DATETIME2      NULL,

        CONSTRAINT PK_student_invoices          PRIMARY KEY (invoice_id),
        CONSTRAINT UQ_student_invoices_number   UNIQUE      (invoice_number),
        CONSTRAINT UQ_student_invoices_semester UNIQUE      (student_id, semester_id),

        CONSTRAINT FK_student_invoices_student  FOREIGN KEY (student_id)
            REFERENCES dbo.students (student_id)   ON DELETE NO ACTION,
        CONSTRAINT FK_student_invoices_semester FOREIGN KEY (semester_id)
            REFERENCES dbo.semesters (semester_id) ON DELETE NO ACTION,

        CONSTRAINT CK_student_invoices_dates       CHECK (due_date >= issue_date),
        CONSTRAINT CK_student_invoices_original    CHECK (original_amount    >= 0),
        CONSTRAINT CK_student_invoices_discount    CHECK (discount_amount    >= 0),
        CONSTRAINT CK_student_invoices_scholarship CHECK (scholarship_amount >= 0),
        CONSTRAINT CK_student_invoices_latefee     CHECK (late_fee_amount    >= 0),
        CONSTRAINT CK_student_invoices_total       CHECK (total_amount       >= 0),
        CONSTRAINT CK_student_invoices_paid        CHECK (paid_amount        >= 0),
        CONSTRAINT CK_student_invoices_remaining   CHECK (remaining_amount   >= 0),
        CONSTRAINT CK_student_invoices_status      CHECK (status IN (N'UNPAID', N'PARTIALLY_PAID', N'PAID', N'OVERDUE'))
    );

    /* ---- J3. invoice_items : the line items of one invoice ----------------
       CASCADE IS SAFE: a line item is meaningless without its invoice, and
       there is exactly one path from student_invoices down to here.
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.invoice_items
    (
        invoice_item_id INT            IDENTITY(1,1) NOT NULL,
        invoice_id      INT            NOT NULL,
        fee_type_id     INT            NOT NULL,
        description     NVARCHAR(200)  NULL,
        amount          DECIMAL(10,2)  NOT NULL,

        CONSTRAINT PK_invoice_items PRIMARY KEY (invoice_item_id),

        CONSTRAINT FK_invoice_items_invoice  FOREIGN KEY (invoice_id)
            REFERENCES dbo.student_invoices (invoice_id) ON DELETE CASCADE,
        CONSTRAINT FK_invoice_items_fee_type FOREIGN KEY (fee_type_id)
            REFERENCES dbo.fee_types (fee_type_id)       ON DELETE NO ACTION,

        CONSTRAINT CK_invoice_items_amount CHECK (amount >= 0)
    );

    /* ---- J4. payments : money actually received --------------------------- */
    CREATE TABLE dbo.payments
    (
        payment_id       INT            IDENTITY(1,1) NOT NULL,
        invoice_id       INT            NOT NULL,
        student_id       INT            NOT NULL,
        payment_date     DATETIME2      NOT NULL
                         CONSTRAINT DF_payments_date DEFAULT (GETDATE()),
        amount           DECIMAL(10,2)  NOT NULL,
        payment_method   NVARCHAR(30)   NOT NULL,
        receipt_number   NVARCHAR(50)   NOT NULL,
        reference_number NVARCHAR(100)  NULL,
        payment_status   NVARCHAR(20)   NOT NULL
                         CONSTRAINT DF_payments_status DEFAULT (N'PENDING'),
        notes            NVARCHAR(500)  NULL,
        recorded_by      INT            NULL,
        created_at       DATETIME2      NOT NULL
                         CONSTRAINT DF_payments_created_at DEFAULT (GETDATE()),

        CONSTRAINT PK_payments         PRIMARY KEY (payment_id),
        CONSTRAINT UQ_payments_receipt UNIQUE      (receipt_number),

        CONSTRAINT FK_payments_invoice  FOREIGN KEY (invoice_id)
            REFERENCES dbo.student_invoices (invoice_id) ON DELETE NO ACTION,
        CONSTRAINT FK_payments_student  FOREIGN KEY (student_id)
            REFERENCES dbo.students (student_id)         ON DELETE NO ACTION,
        CONSTRAINT FK_payments_recorder FOREIGN KEY (recorded_by)
            REFERENCES dbo.users (user_id)               ON DELETE NO ACTION,

        CONSTRAINT CK_payments_amount CHECK (amount > 0),
        CONSTRAINT CK_payments_method CHECK (payment_method IN (N'CASH', N'CARD', N'BANK_TRANSFER', N'ONLINE')),
        CONSTRAINT CK_payments_status CHECK (payment_status IN (N'PENDING', N'COMPLETED', N'FAILED', N'REFUNDED'))
    );

    /* ---- J5. financial_transactions : the full money history --------------
       amount is SIGNED on purpose: CHARGE and LATE_FEE are positive,
       PAYMENT / DISCOUNT / SCHOLARSHIP / REFUND are negative, ADJUSTMENT can
       be either. The student balance is simply SUM(amount).
       All FKs are NO ACTION - three of them reach students by different
       routes, which is exactly the shape SQL Server rejects with error 1785.
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.financial_transactions
    (
        transaction_id   INT            IDENTITY(1,1) NOT NULL,
        student_id       INT            NOT NULL,
        invoice_id       INT            NULL,
        payment_id       INT            NULL,
        transaction_type NVARCHAR(30)   NOT NULL,
        amount           DECIMAL(10,2)  NOT NULL,
        transaction_date DATETIME2      NOT NULL
                         CONSTRAINT DF_financial_transactions_date DEFAULT (GETDATE()),
        description      NVARCHAR(500)  NULL,
        created_by       INT            NULL,
        created_at       DATETIME2      NOT NULL
                         CONSTRAINT DF_financial_transactions_created_at DEFAULT (GETDATE()),

        CONSTRAINT PK_financial_transactions PRIMARY KEY (transaction_id),

        CONSTRAINT FK_financial_transactions_student FOREIGN KEY (student_id)
            REFERENCES dbo.students (student_id)         ON DELETE NO ACTION,
        CONSTRAINT FK_financial_transactions_invoice FOREIGN KEY (invoice_id)
            REFERENCES dbo.student_invoices (invoice_id) ON DELETE NO ACTION,
        CONSTRAINT FK_financial_transactions_payment FOREIGN KEY (payment_id)
            REFERENCES dbo.payments (payment_id)         ON DELETE NO ACTION,
        CONSTRAINT FK_financial_transactions_creator FOREIGN KEY (created_by)
            REFERENCES dbo.users (user_id)               ON DELETE NO ACTION,

        CONSTRAINT CK_financial_transactions_type CHECK (transaction_type IN
            (N'CHARGE', N'PAYMENT', N'DISCOUNT', N'SCHOLARSHIP', N'LATE_FEE', N'REFUND', N'ADJUSTMENT'))
    );


    /* ========================================================================
       MODULE K - NOTIFICATIONS AND AUDIT
       ======================================================================== */
    PRINT N'--- MODULE K: notifications and audit ---';

    /* ---- K1. notifications : personal in-app inbox ------------------------
       related_entity_type / related_entity_id form a SOFT reference (no FK,
       because the target table varies). They let the bell icon open the exact
       assignment, invoice, grade, conversation or news item.
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.notifications
    (
        notification_id     INT           IDENTITY(1,1) NOT NULL,
        user_id             INT           NOT NULL,
        title               NVARCHAR(100) NOT NULL,
        message             NVARCHAR(500) NOT NULL,
        type                NVARCHAR(30)  NOT NULL,
        is_read             BIT           NOT NULL
                            CONSTRAINT DF_notifications_is_read DEFAULT (0),
        related_entity_type NVARCHAR(50)  NULL,
        related_entity_id   INT           NULL,
        created_at          DATETIME2     NOT NULL
                            CONSTRAINT DF_notifications_created_at DEFAULT (GETDATE()),

        CONSTRAINT PK_notifications      PRIMARY KEY (notification_id),
        CONSTRAINT FK_notifications_user FOREIGN KEY (user_id)
            REFERENCES dbo.users (user_id) ON DELETE NO ACTION,

        CONSTRAINT CK_notifications_type CHECK (type IN
            (N'INFO', N'SUCCESS', N'WARNING', N'GRADE', N'WAITLIST', N'ASSIGNMENT',
             N'PAYMENT', N'REGISTRATION', N'CHAT_REPLY', N'NEWS', N'GENERAL'))
    );

    /* ---- K2. audit_log : written by TRIGGERS, never by Java ---------------
       user_id is nullable: a change can come from a background job or from
       somebody working directly in SSMS, where there is no application user.
    ------------------------------------------------------------------------ */
    CREATE TABLE dbo.audit_log
    (
        log_id      INT           IDENTITY(1,1) NOT NULL,
        user_id     INT           NULL,
        action_type NVARCHAR(20)  NOT NULL,
        table_name  NVARCHAR(50)  NOT NULL,
        record_id   INT           NULL,
        old_value   NVARCHAR(MAX) NULL,
        new_value   NVARCHAR(MAX) NULL,
        description NVARCHAR(500) NULL,
        created_at  DATETIME2     NOT NULL
                    CONSTRAINT DF_audit_log_created_at DEFAULT (GETDATE()),

        CONSTRAINT PK_audit_log      PRIMARY KEY (log_id),
        CONSTRAINT FK_audit_log_user FOREIGN KEY (user_id)
            REFERENCES dbo.users (user_id) ON DELETE NO ACTION,

        CONSTRAINT CK_audit_log_action CHECK (action_type IN (N'INSERT', N'UPDATE', N'DELETE'))
    );


    /* ========================================================================
       INDEXES
       ------------------------------------------------------------------------
       SQL Server indexes PRIMARY KEY and UNIQUE automatically, but NOT the
       child side of a foreign key. Without these, every join would scan.

       Already covered by UNIQUE constraints (no second index needed):
         students.student_number, courses.course_code, campuses.campus_name,
         enrollments(student_id, section_id), grades.enrollment_id,
         student_invoices.invoice_number, payments.receipt_number
       ======================================================================== */
    PRINT N'--- Creating indexes ---';

    CREATE INDEX IX_programs_dept                  ON dbo.programs (dept_id);

    CREATE INDEX IX_students_program               ON dbo.students (program_id);
    CREATE INDEX IX_students_status                ON dbo.students (status);
    CREATE INDEX IX_students_standing              ON dbo.students (academic_standing);

    CREATE INDEX IX_instructors_dept               ON dbo.instructors (dept_id);

    CREATE INDEX IX_courses_dept                   ON dbo.courses (dept_id);
    CREATE INDEX IX_courses_level_year             ON dbo.courses (level_year);

    CREATE INDEX IX_course_prerequisites_course    ON dbo.course_prerequisites (course_id);
    CREATE INDEX IX_course_prerequisites_prereq    ON dbo.course_prerequisites (prerequisite_course_id);

    CREATE INDEX IX_program_requirements_program   ON dbo.program_requirements (program_id);
    CREATE INDEX IX_program_requirements_course    ON dbo.program_requirements (course_id);

    CREATE INDEX IX_sections_course                ON dbo.sections (course_id);
    CREATE INDEX IX_sections_semester              ON dbo.sections (semester_id);
    CREATE INDEX IX_sections_instructor            ON dbo.sections (instructor_id);
    CREATE INDEX IX_sections_campus                ON dbo.sections (campus_id);

    CREATE INDEX IX_section_schedules_section      ON dbo.section_schedules (section_id);
    CREATE INDEX IX_section_schedules_day          ON dbo.section_schedules (day_of_week, start_time, end_time);

    CREATE INDEX IX_enrollments_student            ON dbo.enrollments (student_id);
    CREATE INDEX IX_enrollments_section            ON dbo.enrollments (section_id);
    CREATE INDEX IX_enrollments_status             ON dbo.enrollments (status);

    CREATE INDEX IX_grades_submitted_by            ON dbo.grades (submitted_by);
    CREATE INDEX IX_grades_modified_by             ON dbo.grades (last_modified_by);

    CREATE INDEX IX_waitlist_section               ON dbo.waitlist (section_id, position);
    CREATE INDEX IX_waitlist_student               ON dbo.waitlist (student_id);

    CREATE INDEX IX_assignments_section            ON dbo.assignments (section_id, status);
    CREATE INDEX IX_assignments_creator            ON dbo.assignments (created_by);
    CREATE INDEX IX_assignments_due                ON dbo.assignments (due_date);

    CREATE INDEX IX_university_news_creator        ON dbo.university_news (created_by);
    CREATE INDEX IX_university_news_published      ON dbo.university_news (is_published, publication_date);

    CREATE INDEX IX_advisors_directory_active      ON dbo.advisors_directory (is_active);

    CREATE INDEX IX_chat_conversations_student     ON dbo.chat_conversations (student_id, status);
    CREATE INDEX IX_chat_conversations_admin       ON dbo.chat_conversations (assigned_admin_user_id);

    CREATE INDEX IX_chat_messages_conversation     ON dbo.chat_messages (conversation_id, sent_at);
    CREATE INDEX IX_chat_messages_sender           ON dbo.chat_messages (sender_user_id);

    CREATE INDEX IX_student_invoices_student       ON dbo.student_invoices (student_id, status);
    CREATE INDEX IX_student_invoices_semester      ON dbo.student_invoices (semester_id);

    CREATE INDEX IX_invoice_items_invoice          ON dbo.invoice_items (invoice_id);
    CREATE INDEX IX_invoice_items_fee_type         ON dbo.invoice_items (fee_type_id);

    CREATE INDEX IX_payments_invoice               ON dbo.payments (invoice_id);
    CREATE INDEX IX_payments_student               ON dbo.payments (student_id, payment_date);
    CREATE INDEX IX_payments_recorder              ON dbo.payments (recorded_by);

    CREATE INDEX IX_financial_transactions_student ON dbo.financial_transactions (student_id, transaction_date);
    CREATE INDEX IX_financial_transactions_invoice ON dbo.financial_transactions (invoice_id);
    CREATE INDEX IX_financial_transactions_payment ON dbo.financial_transactions (payment_id);
    CREATE INDEX IX_financial_transactions_creator ON dbo.financial_transactions (created_by);

    CREATE INDEX IX_notifications_user             ON dbo.notifications (user_id, is_read);
    CREATE INDEX IX_notifications_entity           ON dbo.notifications (related_entity_type, related_entity_id);

    CREATE INDEX IX_audit_log_user                 ON dbo.audit_log (user_id);
    CREATE INDEX IX_audit_log_table                ON dbo.audit_log (table_name, record_id);

    /* THE "EXACTLY ONE CURRENT SEMESTER" RULE
       A CHECK constraint can only see ONE row, so it cannot express
       "only one row in the whole table may have is_current = 1".
       A FILTERED UNIQUE INDEX can: is_current is unique, but only among the
       rows where is_current = 1. Requires QUOTED_IDENTIFIER ON (set in PART 0).
    ------------------------------------------------------------------------ */
    CREATE UNIQUE INDEX UX_semesters_one_current
        ON dbo.semesters (is_current)
        WHERE is_current = 1;

    COMMIT TRANSACTION;
    PRINT N'>>> SCHEMA CREATED SUCCESSFULLY (27 tables).';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! SCHEMA CREATION FAILED - everything was rolled back.';
    THROW;
END CATCH
GO


/* ============================================================================
   PART 4 - SAMPLE DATA
   ----------------------------------------------------------------------------
   The tables were just created, so every IDENTITY starts at 1 and the ids are
   predictable in insert order. Child rows therefore refer to parent ids
   literally (1, 2, 3 ...). That is safe ONLY because this script always builds
   the database from scratch.

   THE DATA TELLS ONE COHERENT STORY, so it also satisfies the APPLICATION
   rules (prerequisites, waitlist, GPA cache), not just the table constraints:

     Spring 2025 (past)     : both students took CS101.
                              Zaynab passed with A. Omar failed with F.
     Fall 2025 (CURRENT)    : Zaynab takes CS102 (allowed - she passed CS101)
                              and MATH101 (no prerequisite).
                              MATH101 has capacity 1 and is FULL, so Omar is
                              on its waiting list - which is the only situation
                              where a waitlist row is legitimate.
     Spring 2026 (future)   : exists so registration-window logic has a target.

   *** PASSWORD NOTE - READ THIS ***
   Login signs in with the role-specific ID (Admin ID / Instructor ID /
   Student ID - dbo.admins.admin_id, dbo.instructors.instructor_id,
   dbo.students.student_id), never the global dbo.users.user_id. The Login
   screen has no role selector; it tries each role's table in turn. Usernames
   below are for display only and cannot sign in.

   Every account's initial password is "<role-specific-id>@iuL" - e.g. Admin
   ID 1, Instructor ID 1 and Student ID 1 all sign in with "1@iuL", even
   though they are three different dbo.users rows.

   The five password_hash values inserted below are still BCrypt hashes of
   the OLD "<user_id>@iuL" rule (1-5), because they were computed before this
   table existed. For user_id 1 that is harmless - admin_id 1 also happens to
   be user_id 1 - but user_id 2-5 no longer match their role-specific id
   (instructor_id 1-2, student_id 1-2). Run
   RolePasswordMigration.main() once after building this schema (fresh
   install or existing database) to rehash every account, sample data
   included, to the new rule. StudentDAO/InstructorDAO-backed account
   creation applies the new rule automatically from then on
   (UserDAO.finalizePassword, called by StudentService/InstructorService
   once the role-specific id exists).
============================================================================ */
BEGIN TRY
    BEGIN TRANSACTION;

    PRINT N'--- Inserting sample data ---';

    /* ---- departments (1=CS, 2=IT, 3=BUS) --------------------------------- */
    INSERT INTO dbo.departments (dept_code, dept_name) VALUES
        (N'CS',  N'Computer Science'),
        (N'IT',  N'Information Technology'),
        (N'BUS', N'Business Administration');

    /* ---- campuses (1=Khaldeh, 2=Nabatieh) -------------------------------- */
    INSERT INTO dbo.campuses (campus_name, location) VALUES
        (N'Khaldeh Campus',  N'Khaldeh, Mount Lebanon'),
        (N'Nabatieh Campus', N'Nabatieh, South Lebanon');

    /* ---- programs (1=BSCS, 2=BSIT) --------------------------------------- */
    INSERT INTO dbo.programs (dept_id, program_code, program_name, degree_type, total_credits_required) VALUES
        (1, N'BSCS', N'BSc Computer Science',       N'BACHELOR', 132),
        (2, N'BSIT', N'BSc Information Technology', N'BACHELOR', 126);

    /* ---- users -----------------------------------------------------------
       1 = admin     (ADMIN)       login User ID 1, password 1@iuL
       2 = a.khoury  (INSTRUCTOR)  login User ID 2, password 2@iuL
       3 = s.haddad  (INSTRUCTOR)  login User ID 3, password 3@iuL
       4 = z.matar   (STUDENT)     login User ID 4, password 4@iuL
       5 = o.saleh   (STUDENT)     login User ID 5, password 5@iuL
       Real BCrypt hashes (cost 12) - see the password note above.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.users (username, password_hash, role) VALUES
        (N'admin',    N'$2a$12$qzkxkx5uLhTF.WCug9QZbuXOkd7K0RfJt/aOsacfXesZiVQZd1UzO', N'ADMIN'),
        (N'a.khoury', N'$2a$12$iavXLZMGTQ5HcfJpzdaae.7m60PEq.osd8FM5yJjVq5Lg0ebclosW', N'INSTRUCTOR'),
        (N's.haddad', N'$2a$12$NUbHWmzUAyeMJDBGTFgueeGfZe199rkns2XEhi.u3qk/XVvvdqk.C', N'INSTRUCTOR'),
        (N'z.matar',  N'$2a$12$hswCE1bYn2pYxyUA9ordN.2P.AarIv2u0CvTAtpcWJXiMlqK3fe2K', N'STUDENT'),
        (N'o.saleh',  N'$2a$12$5UhcsfS05DJ8wBHMlrkW3eBrURRU0lH19d5Y58MqMloTB2.2ihqEq', N'STUDENT');

    /* ---- admins (1 -> user 1) ---------------------------------------------
       Every ADMIN row in dbo.users needs a matching dbo.admins row so login
       can resolve "Admin ID" -> admin_id -> user_id.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.admins (user_id) VALUES (1);

    /* ---- instructors (1 -> user 2, 2 -> user 3) -------------------------- */
    INSERT INTO dbo.instructors (user_id, employee_number, first_name, last_name, email, phone, dept_id, academic_rank, hire_date) VALUES
        (2, N'EMP1001', N'Ahmad', N'Khoury', N'a.khoury@university.edu.lb', N'+96170111222', 1, N'PROFESSOR', '2015-09-01'),
        (3, N'EMP1002', N'Sara',  N'Haddad', N's.haddad@university.edu.lb', N'+96170333444', 1, N'ASSISTANT', '2019-02-15');

    /* ---- students (1 -> user 4, 2 -> user 5) -----------------------------
       The cached columns MATCH the grades inserted further down:
         Zaynab : CS101 = A (4.00), 3 credits passed -> GPA 4.00, DEANS_LIST
         Omar   : CS101 = F (0.00), 0 credits passed -> GPA 0.00, PROBATION
       If these did not match, sp_RecalculateStudentGPA would silently
       overwrite them and the demo would look broken.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.students
        (user_id, student_number, first_name, last_name, email, phone, date_of_birth, gender, address,
         program_id, admission_date, status, academic_standing, cumulative_gpa, completed_credits, probation_count) VALUES
        (4, N'2025001234', N'Zaynab', N'Matar', N'z.matar@student.university.edu.lb', N'+96176555666', '2004-04-12', N'FEMALE', N'Nabatieh', 1, '2025-02-01', N'ACTIVE', N'DEANS_LIST', 4.00, 3, 0),
        (5, N'2025001235', N'Omar',   N'Saleh', N'o.saleh@student.university.edu.lb', N'+96176777888', '2003-11-03', N'MALE',   N'Beirut',   1, '2025-02-01', N'ACTIVE', N'PROBATION',  0.00, 0, 1);

    /* ---- courses (1=CS101, 2=CS102, 3=CS201, 4=CS202, 5=MATH101) --------- */
    INSERT INTO dbo.courses (course_code, course_title, description, credits, dept_id, level_year) VALUES
        (N'CS101',   N'Introduction to Programming', N'Fundamentals of programming using Java.', 3, 1, 1),
        (N'CS102',   N'Object Oriented Programming', N'Classes, inheritance, polymorphism.',      3, 1, 1),
        (N'CS201',   N'Data Structures',             N'Lists, trees, graphs and complexity.',     3, 1, 2),
        (N'CS202',   N'Database Systems',            N'Relational design, SQL, transactions.',    3, 1, 2),
        (N'MATH101', N'Calculus I',                  N'Limits, derivatives and integrals.',       3, 1, 1);

    /* ---- course_prerequisites : CS101 -> CS102 -> CS201 -> CS202 ---------- */
    INSERT INTO dbo.course_prerequisites (course_id, prerequisite_course_id, min_grade_points) VALUES
        (2, 1, 1.00),
        (3, 2, 1.00),
        (4, 3, 1.00);

    /* ---- program_requirements (BSCS) ------------------------------------- */
    INSERT INTO dbo.program_requirements (program_id, course_id, is_mandatory, recommended_semester) VALUES
        (1, 1, 1, 1),
        (1, 5, 1, 1),
        (1, 2, 1, 2),
        (1, 3, 1, 3),
        (1, 4, 1, 4);

    /* ---- semesters -------------------------------------------------------
       1 = Spring 2025 (past)    - where CS101 was taken and graded
       2 = Fall 2025   (CURRENT) - the active semester
       3 = Spring 2026 (future)  - registration not open yet
       Only ONE row has is_current = 1, as the filtered index requires.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.semesters
        (semester_name, academic_year, term, start_date, end_date,
         registration_start, registration_end, drop_deadline, withdraw_deadline,
         grade_entry_start, grade_entry_end, is_current) VALUES
        (N'Spring 2025', N'2024-2025', N'SPRING', '2025-02-10', '2025-06-15',
         '2025-01-10T08:00:00', '2025-02-15T23:59:00', '2025-03-01', '2025-04-10',
         '2025-05-25', '2025-06-20', 0),
        (N'Fall 2025',   N'2025-2026', N'FALL',   '2025-09-15', '2026-01-20',
         '2025-08-15T08:00:00', '2025-09-20T23:59:00', '2025-10-05', '2025-11-15',
         '2026-01-05', '2026-01-30', 1),
        (N'Spring 2026', N'2025-2026', N'SPRING', '2026-02-09', '2026-06-15',
         '2026-01-10T08:00:00', '2026-02-14T23:59:00', '2026-03-01', '2026-04-10',
         '2026-06-01', '2026-06-25', 0);

    /* ---- sections (1..4) --------------------------------------------------
       Section 4 has capacity 1 ON PURPOSE: it is the only way to demonstrate
       a genuinely FULL section, which is what a waitlist row requires.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.sections (course_id, semester_id, instructor_id, campus_id, section_number, capacity, enrolled_count, room, status) VALUES
        (1, 1, 1,    1, N'01', 30, 2, N'B204', N'OPEN'),   -- s1: CS101,   Spring 2025, Khaldeh
        (2, 2, 2,    1, N'01', 25, 1, N'B310', N'OPEN'),   -- s2: CS102,   Fall 2025,   Khaldeh
        (3, 2, 1,    2, N'01', 20, 0, N'A105', N'OPEN'),   -- s3: CS201,   Fall 2025,   Nabatieh (no one yet)
        (5, 2, NULL, 1, N'01',  1, 1, N'C101', N'OPEN');   -- s4: MATH101, Fall 2025,   instructor TBA, FULL

    /* ---- section_schedules ------------------------------------------------
       Zaynab's Fall 2025 timetable (s2 + s4) has no overlap, so it satisfies
       the conflict rule R6.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.section_schedules (section_id, day_of_week, start_time, end_time) VALUES
        (1, N'MON', '08:00', '09:30'),
        (1, N'WED', '08:00', '09:30'),
        (2, N'TUE', '10:00', '11:30'),
        (2, N'THU', '10:00', '11:30'),
        (3, N'MON', '12:00', '13:30'),
        (4, N'FRI', '09:00', '10:30');

    /* ---- enrollments (1..4) ----------------------------------------------
       1 = Zaynab, CS101   (Spring 2025) COMPLETED
       2 = Omar,   CS101   (Spring 2025) COMPLETED
       3 = Zaynab, CS102   (Fall 2025)   ENROLLED  - prerequisite CS101 passed
       4 = Zaynab, MATH101 (Fall 2025)   ENROLLED  - no prerequisite
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.enrollments (student_id, section_id, status, enrollment_date) VALUES
        (1, 1, N'COMPLETED', '2025-02-12T09:00:00'),
        (2, 1, N'COMPLETED', '2025-02-12T09:30:00'),
        (1, 2, N'ENROLLED',  '2025-09-16T10:00:00'),
        (1, 4, N'ENROLLED',  '2025-09-16T10:05:00');

    /* ---- grades ----------------------------------------------------------
       total_mark = 0.30*coursework + 0.30*midterm + 0.40*final (Section 5.1).
       Row 1: 0.30*85 + 0.30*90 + 0.40*88 = 87.70 -> B+ (3.30, Section 5.2).
       Row 2: 0.30*40 + 0.30*45 + 0.40*30 = 37.50 -> F  (0.00).
       submitted_at falls inside the Spring 2025 grade-entry window
       (2025-05-25 .. 2025-06-20), so rule G2 is satisfied.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.grades
        (enrollment_id, coursework_mark, midterm_mark, final_mark, total_mark,
         letter_grade, grade_points, result_status, is_submitted, submitted_by, submitted_at) VALUES
        (1, 85.00, 90.00, 88.00, 87.70, N'B+', 3.30, N'PASSED', 1, 2, '2025-06-05T14:00:00'),
        (2, 40.00, 45.00, 30.00, 37.50, N'F', 0.00, N'FAILED', 1, 2, '2025-06-05T14:05:00');

    /* ---- waitlist ---------------------------------------------------------
       Omar queues for MATH101 (section 4), which is genuinely full
       (capacity 1, enrolled 1). MATH101 has no prerequisite, so Omar is
       eligible even though he failed CS101.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.waitlist (section_id, student_id, position, status) VALUES
        (4, 2, 1, N'WAITING');

    /* ---- advisors_directory ---------------------------------------------- */
    INSERT INTO dbo.advisors_directory (full_name, department, university_email, office, phone) VALUES
        (N'Dr. Rami Nasr',    N'Computer Science',       N'r.nasr@university.edu.lb',    N'Block B, Office 214', N'+9611000111'),
        (N'Dr. Lina Aoun',    N'Information Technology', N'l.aoun@university.edu.lb',    N'Block A, Office 118', N'+9611000222'),
        (N'Mr. Hadi Chidiac', N'Student Affairs',        N'h.chidiac@university.edu.lb', N'Block C, Office 005', N'+9611000333');

    /* ---- assignments ------------------------------------------------------
       Each one is attached to a section that actually has students in it.
       Assignment 2 is created by the ADMIN because section 4 has no
       instructor assigned yet (TBA).
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.assignments (section_id, title, description, assigned_date, due_date, maximum_mark, created_by, status) VALUES
        (2, N'Lab 3 - Inheritance',      N'Implement a class hierarchy for a small library system.', '2025-10-01T09:00:00', '2025-10-10T23:59:00', 20.00, 3, N'ACTIVE'),
        (4, N'Problem Set 1 - Limits',   N'Solve the limit exercises from chapter 2.',                '2025-10-03T09:00:00', '2025-10-12T23:59:00', 10.00, 1, N'ACTIVE'),
        (1, N'Final Project - CS101',    N'Console application using loops, arrays and methods.',     '2025-03-10T09:00:00', '2025-05-20T23:59:00', 30.00, 2, N'CLOSED');

    /* ---- university_news --------------------------------------------------- */
    INSERT INTO dbo.university_news (title, content, category, publication_date, expiry_date, is_published, created_by) VALUES
        (N'Fall 2025 Graduation Ceremony', N'The graduation ceremony will be held at the Khaldeh Campus main hall on 5 July 2026. Families are welcome.', N'GRADUATION', '2026-05-01T10:00:00', '2026-07-06T00:00:00', 1, 1),
        (N'New Robotics Laboratory',       N'A new robotics laboratory has opened in Block B, equipped for embedded systems and automation projects.',      N'FACILITY',   '2026-03-15T09:00:00', NULL,                  1, 1),
        (N'Library Opening Hours Update',  N'From 1 April the library will stay open until 20:00 on weekdays.',                                             N'GENERAL',    '2026-03-25T08:00:00', '2026-09-01T00:00:00', 1, 1);

    /* ---- fee_types (1..5) -------------------------------------------------- */
    INSERT INTO dbo.fee_types (fee_name, description) VALUES
        (N'Tuition',          N'Per-semester tuition charge.'),
        (N'Registration Fee', N'Fixed administrative registration charge.'),
        (N'Laboratory Fee',   N'Charged for courses with a laboratory component.'),
        (N'Library Fee',      N'Annual library access and services charge.'),
        (N'Late Fee',         N'Penalty applied to overdue invoices.');

    /* ---- student_invoices (both for Fall 2025 = semester 2) ---------------
       Invoice 1 (Zaynab): 3000 - 200 discount - 500 scholarship = 2300 total,
                           1000 paid -> 1300 remaining -> PARTIALLY_PAID
       Invoice 2 (Omar)  : 3000 total, fully paid          -> PAID
       In both cases SUM(invoice_items.amount) = original_amount.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.student_invoices
        (student_id, semester_id, invoice_number, issue_date, due_date,
         original_amount, discount_amount, scholarship_amount, late_fee_amount,
         total_amount, paid_amount, remaining_amount, status) VALUES
        (1, 2, N'INV-2025-0001', '2025-09-20', '2025-10-20', 3000.00, 200.00, 500.00, 0.00, 2300.00, 1000.00, 1300.00, N'PARTIALLY_PAID'),
        (2, 2, N'INV-2025-0002', '2025-09-20', '2025-10-20', 3000.00,   0.00,   0.00, 0.00, 3000.00, 3000.00,    0.00, N'PAID');

    /* ---- invoice_items ------------------------------------------------------ */
    INSERT INTO dbo.invoice_items (invoice_id, fee_type_id, description, amount) VALUES
        (1, 1, N'Tuition - Fall 2025 (6 credits)', 2500.00),
        (1, 2, N'Registration fee - Fall 2025',     300.00),
        (1, 3, N'Laboratory fee - CS102',           200.00),
        (2, 1, N'Tuition - Fall 2025',             2500.00),
        (2, 2, N'Registration fee - Fall 2025',     300.00),
        (2, 4, N'Library fee - 2025/2026',          200.00);

    /* ---- payments (1, 2) ---------------------------------------------------- */
    INSERT INTO dbo.payments
        (invoice_id, student_id, payment_date, amount, payment_method,
         receipt_number, reference_number, payment_status, notes, recorded_by) VALUES
        (1, 1, '2025-09-25T11:30:00', 1000.00, N'CASH',          N'RCPT-2025-0001', NULL,            N'COMPLETED', N'First instalment.', 1),
        (2, 2, '2025-09-26T09:10:00', 3000.00, N'BANK_TRANSFER', N'RCPT-2025-0002', N'TRX-889-2025', N'COMPLETED', N'Paid in full.',     1);

    /* ---- financial_transactions --------------------------------------------
       SUM(amount) per student must equal that student's remaining_amount:
         Zaynab: 3000 - 200 - 500 - 1000 = 1300  (matches invoice 1)
         Omar  : 3000 - 3000            =    0  (matches invoice 2)
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.financial_transactions
        (student_id, invoice_id, payment_id, transaction_type, amount, transaction_date, description, created_by) VALUES
        (1, 1, NULL, N'CHARGE',      3000.00, '2025-09-20T10:00:00', N'Fall 2025 charges',           1),
        (1, 1, NULL, N'DISCOUNT',    -200.00, '2025-09-20T10:05:00', N'Early payment discount',      1),
        (1, 1, NULL, N'SCHOLARSHIP', -500.00, '2025-09-20T10:06:00', N'Merit scholarship 2025/2026', 1),
        (1, 1, 1,    N'PAYMENT',    -1000.00, '2025-09-25T11:30:00', N'Cash payment, receipt 0001',  1),
        (2, 2, NULL, N'CHARGE',      3000.00, '2025-09-20T10:00:00', N'Fall 2025 charges',           1),
        (2, 2, 2,    N'PAYMENT',    -3000.00, '2025-09-26T09:10:00', N'Bank transfer, receipt 0002', 1);

    /* ---- chat_conversations (1, 2) ------------------------------------------
       Each conversation matches that student's actual situation:
         Zaynab has an unpaid balance  -> FINANCE question, admin replied
         Omar is on a waiting list     -> REGISTRATION question, still OPEN
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.chat_conversations (student_id, assigned_admin_user_id, subject, category, status, created_at, updated_at) VALUES
        (1, 1,    N'Remaining balance on my invoice', N'FINANCE',      N'IN_PROGRESS', '2025-09-28T12:40:00', '2025-09-28T13:05:00'),
        (2, NULL, N'Waiting list for MATH101',        N'REGISTRATION', N'OPEN',        '2025-09-29T08:15:00', NULL);

    /* ---- chat_messages ------------------------------------------------------ */
    INSERT INTO dbo.chat_messages (conversation_id, sender_user_id, message_text, sent_at, is_read) VALUES
        (1, 4, N'Hello, my invoice still shows a remaining balance. Could you confirm the amount and the deadline?', '2025-09-28T12:40:00', 1),
        (1, 1, N'Hello Zaynab, the remaining balance is 1300.00 and it is due on 20 October 2025. You can pay in instalments at the finance office.', '2025-09-28T13:05:00', 0),
        (2, 5, N'Good morning, I am on the waiting list for MATH101. When will I know if a seat becomes available?', '2025-09-29T08:15:00', 0);

    /* ---- notifications ------------------------------------------------------
       related_entity_type / related_entity_id let the bell icon open the exact
       record the notification is about. Every id below really exists.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.notifications (user_id, title, message, type, is_read, related_entity_type, related_entity_id) VALUES
        (4, N'Grade published',           N'Your grade for CS101 has been published.',                            N'GRADE',      0, N'GRADE',        1),
        (4, N'New assignment',            N'Lab 3 - Inheritance is due on 10 October.',                           N'ASSIGNMENT', 0, N'ASSIGNMENT',   1),
        (4, N'Payment received',          N'A payment of 1000.00 was recorded on invoice INV-2025-0001.',         N'PAYMENT',    1, N'INVOICE',      1),
        (4, N'Reply from administration', N'The administration replied to your question about your balance.',     N'CHAT_REPLY', 0, N'CONVERSATION', 1),
        (5, N'Grade published',           N'Your grade for CS101 has been published.',                            N'GRADE',      0, N'GRADE',        2),
        (5, N'Waitlist update',           N'You are position 1 on the waiting list for MATH101.',                 N'WAITLIST',   0, N'SECTION',      4),
        (5, N'University news',           N'A new robotics laboratory has opened in Block B.',                    N'NEWS',       0, N'NEWS',         2),
        (1, N'System ready',              N'The university management database has been initialised.',            N'INFO',       1, NULL,            NULL);

    /* ---- audit_log ----------------------------------------------------------
       Normally written by triggers. One manual row so the table is not empty
       during the demo.
    ---------------------------------------------------------------------- */
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, new_value, description) VALUES
        (2, N'INSERT', N'grades', 1, N'total_mark=87.70; letter=B+; result=PASSED', N'Initial grade entry for CS101.');

    COMMIT TRANSACTION;
    PRINT N'>>> SAMPLE DATA INSERTED SUCCESSFULLY.';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! SAMPLE DATA FAILED - all inserts were rolled back.';
    THROW;
END CATCH
GO


/* ============================================================================
   PART 4B - PROGRAMMABILITY
   project_details.md Section 4.16: audit_log is filled automatically by
   database triggers, never by Java. trg_Grade_Audit is the one trigger this
   project ships with (Phase 11, rule G5 - the registrar's correction of a
   submitted grade must be auditable even though Java never inserts into
   audit_log itself).
============================================================================ */
IF OBJECT_ID('dbo.trg_Grade_Audit', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_Grade_Audit;
GO

CREATE TRIGGER dbo.trg_Grade_Audit
ON dbo.grades
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted) AND NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    -- UPDATE: a row exists in both inserted and deleted.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT
        COALESCE(i.last_modified_by, i.submitted_by),
        N'UPDATE', N'grades', i.grade_id,
        CONCAT(N'total=', CONVERT(NVARCHAR(20), d.total_mark), N'; letter=', d.letter_grade,
               N'; points=', CONVERT(NVARCHAR(20), d.grade_points), N'; submitted=', CONVERT(NVARCHAR(2), d.is_submitted)),
        CONCAT(N'total=', CONVERT(NVARCHAR(20), i.total_mark), N'; letter=', i.letter_grade,
               N'; points=', CONVERT(NVARCHAR(20), i.grade_points), N'; submitted=', CONVERT(NVARCHAR(2), i.is_submitted)),
        CONCAT(N'Grade changed for enrollment ', i.enrollment_id)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.grade_id = i.grade_id;

    -- INSERT: the row is in inserted but not in deleted.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT
        COALESCE(i.last_modified_by, i.submitted_by),
        N'INSERT', N'grades', i.grade_id,
        NULL,
        CONCAT(N'total=', CONVERT(NVARCHAR(20), i.total_mark), N'; letter=', i.letter_grade,
               N'; points=', CONVERT(NVARCHAR(20), i.grade_points), N'; submitted=', CONVERT(NVARCHAR(2), i.is_submitted)),
        CONCAT(N'Grade created for enrollment ', i.enrollment_id)
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.grade_id = i.grade_id);

    -- DELETE: the row is in deleted but not in inserted.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT
        COALESCE(d.last_modified_by, d.submitted_by),
        N'DELETE', N'grades', d.grade_id,
        CONCAT(N'total=', CONVERT(NVARCHAR(20), d.total_mark), N'; letter=', d.letter_grade,
               N'; points=', CONVERT(NVARCHAR(20), d.grade_points), N'; submitted=', CONVERT(NVARCHAR(2), d.is_submitted)),
        NULL,
        CONCAT(N'Grade deleted for enrollment ', d.enrollment_id)
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.grade_id = d.grade_id);
END;
GO


/* ============================================================================
   PART 5 - VERIFICATION
============================================================================ */
PRINT N'';
PRINT N'============================================================';
PRINT N'DATABASE BUILD FINISHED.';
PRINT N'============================================================';

SELECT COUNT(*) AS tables_created FROM sys.tables WHERE schema_id = SCHEMA_ID(N'dbo');   -- expect 27
SELECT COUNT(*) AS foreign_keys   FROM sys.foreign_keys;                                 -- expect 42
SELECT COUNT(*) AS check_cons     FROM sys.check_constraints;

/* Consistency self-checks - every one of these should return ZERO rows. */
SELECT N'enrolled_count mismatch' AS problem, s.section_id
FROM dbo.sections s
WHERE s.enrolled_count <> (SELECT COUNT(*) FROM dbo.enrollments e
                           WHERE e.section_id = s.section_id
                             AND e.status IN (N'ENROLLED', N'COMPLETED'));

SELECT N'invoice items do not sum to original_amount' AS problem, i.invoice_id
FROM dbo.student_invoices i
WHERE i.original_amount <> (SELECT SUM(ii.amount) FROM dbo.invoice_items ii
                            WHERE ii.invoice_id = i.invoice_id);

SELECT N'transaction balance does not match invoice' AS problem, t.student_id
FROM dbo.financial_transactions t
GROUP BY t.student_id
HAVING SUM(t.amount) <> (SELECT SUM(i.remaining_amount) FROM dbo.student_invoices i
                         WHERE i.student_id = t.student_id);

SELECT N'waitlist on a section that is not full' AS problem, w.waitlist_id
FROM dbo.waitlist w
JOIN dbo.sections s ON s.section_id = w.section_id
WHERE w.status = N'WAITING' AND s.enrolled_count < s.capacity;

/* Row counts per table */
SELECT t.name AS table_name, SUM(p.rows) AS row_count
FROM sys.tables t
JOIN sys.partitions p ON p.object_id = t.object_id AND p.index_id IN (0,1)
GROUP BY t.name
ORDER BY t.name;
GO