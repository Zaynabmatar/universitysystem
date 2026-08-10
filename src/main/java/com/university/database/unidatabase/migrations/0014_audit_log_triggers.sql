/* ==========================================================================
   0014 - upgrade the Admin Audit Log into a full, trigger-only audit system.
   --------------------------------------------------------------------------
   admin_audit_log.fxml tells the administrator: "Every row below was written
   automatically by a database trigger. The application never writes here."
   Until now that was only true for dbo.grades (trg_Grade_Audit,
   phase11_grades_scale.sql) - creating a student/instructor account,
   changing their email, deactivating/reactivating one, and resetting a
   password were all written by AuditLogDAO.record() from Java. This
   migration adds one AFTER INSERT, UPDATE, DELETE trigger per audited table,
   removes nothing (trg_Grade_Audit is untouched), and the matching Java
   change (same commit) deletes AuditLogDAO.record() and every caller of it,
   so the statement on screen becomes fully true.

   TABLES AUDITED, AND WHY:
     dbo.users        - account creation, role, active/inactive, password
                         resets (hash itself is NEVER written to the log).
     dbo.students      - name, email, phone, address, dob, gender, program,
                         admission date, status, academic standing,
                         probation count.
     dbo.instructors   - name, email, phone, address, department, rank,
                         hire date, active/inactive.
     dbo.courses       - code, title, credits, department, level, has_lab,
                         active/inactive.
     dbo.semesters     - every date/deadline column and is_current (the
                         "advance semester" action).
     dbo.enrollments   - status, is_repeat, counts_in_gpa (registration and
                         drop/withdraw/complete transitions).
     dbo.payments      - amount, method, status, reference (money received;
                         actor comes from the row's own recorded_by, the
                         same pattern trg_Grade_Audit already uses).

   TABLES DELIBERATELY NOT AUDITED (Section 7 of the task): audit_log itself
   and schema_migrations (technical/internal - see AuditLogDAO); and every
   purely CACHED/derived column - sections.enrolled_count (SectionDAO),
   students.cumulative_gpa / completed_credits (AcademicService) - because
   auditing arithmetic that reruns after every grade change would bury the
   real administrative changes in noise, not because they are unimportant.
   dbo.student_invoices / dbo.financial_transactions are the money system's
   own internal ledger (already a full derived history of dbo.payments) and
   are treated the same way as enrolled_count.

   HOW THE ACTOR IS CAPTURED: dbo.grades already carries its own
   submitted_by/last_modified_by columns, set by Java, and dbo.payments
   carries recorded_by the same way - those triggers just read the column.
   None of the other audited tables has an actor column, so this migration
   uses SQL Server's SESSION_CONTEXT instead of adding one: DBConnection.
   getConnection() (same commit) stamps every pooled connection it hands out
   with sp_set_session_context(N'app_user_id', <the signed-in user's id or
   NULL>), on every single borrow, since HikariCP can hand the very same
   physical connection to a different signed-in user next time. Nothing here
   is faked or hardcoded: a change made directly in SSMS, or before anybody
   has signed in, reads back as user_id = NULL, exactly as
   AuditLogDAO.mapRow already documents ("Null when ... somebody working
   directly in the database made the change").

   UPDATE triggers only insert a row when a TRACKED column's value actually
   changed (CROSS APPLY ... WHERE LEN(new_value) > 0): every DAO here does a
   full-column UPDATE on every save, so without this check clicking "Save"
   with nothing edited would log a change that never happened. Only the
   fields that changed are written into old_value/new_value, not a full-row
   snapshot, per the "meaningful changed fields" requirement. is_active on
   dbo.users is rendered as ACTIVE/INACTIVE to read the same way the old
   Java-written rows did.

   SET-BASED: every block is a single INSERT ... SELECT ... FROM inserted /
   deleted, so a multi-row UPDATE or a bulk DELETE is captured in one
   statement, not one row at a time. No trigger here writes to a table that
   is itself audited, so there is no recursion risk.

   SAFE TO RE-RUN: every trigger is dropped and recreated if it exists.
   ========================================================================== */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

/* ============================================================================
   1. dbo.users - account creation, role, active/inactive, password resets.
   ============================================================================ */
IF OBJECT_ID('dbo.trg_User_Audit', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_User_Audit;
GO

CREATE TRIGGER dbo.trg_User_Audit
ON dbo.users
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted) AND NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    DECLARE @actor INT = TRY_CONVERT(INT, SESSION_CONTEXT(N'app_user_id'));

    -- UPDATE: only the tracked columns that actually changed, never the
    -- password hash itself - only the fact that it changed.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'UPDATE', N'users', i.user_id, c.old_value, c.new_value,
           CONCAT(N'Account ', i.user_id, N' updated: ', c.new_value)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.user_id = i.user_id
        CROSS APPLY (
            SELECT
                old_value = CONCAT(
                    CASE WHEN i.role <> d.role THEN CONCAT(N'role=', d.role, N'; ') ELSE N'' END,
                    CASE WHEN i.is_active <> d.is_active
                         THEN CONCAT(N'is_active=', CASE WHEN d.is_active = 1 THEN N'ACTIVE' ELSE N'INACTIVE' END, N'; ')
                         ELSE N'' END,
                    CASE WHEN i.username <> d.username THEN CONCAT(N'username=', d.username, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.email, NCHAR(1)) <> ISNULL(d.email, NCHAR(1))
                         THEN CONCAT(N'email=', ISNULL(d.email, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.address, NCHAR(1)) <> ISNULL(d.address, NCHAR(1))
                         THEN CONCAT(N'address=', ISNULL(d.address, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.password_hash <> d.password_hash THEN N'password=(reset, hash redacted); ' ELSE N'' END
                ),
                new_value = CONCAT(
                    CASE WHEN i.role <> d.role THEN CONCAT(N'role=', i.role, N'; ') ELSE N'' END,
                    CASE WHEN i.is_active <> d.is_active
                         THEN CONCAT(N'is_active=', CASE WHEN i.is_active = 1 THEN N'ACTIVE' ELSE N'INACTIVE' END, N'; ')
                         ELSE N'' END,
                    CASE WHEN i.username <> d.username THEN CONCAT(N'username=', i.username, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.email, NCHAR(1)) <> ISNULL(d.email, NCHAR(1))
                         THEN CONCAT(N'email=', ISNULL(i.email, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.address, NCHAR(1)) <> ISNULL(d.address, NCHAR(1))
                         THEN CONCAT(N'address=', ISNULL(i.address, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.password_hash <> d.password_hash THEN N'password=(reset, hash redacted); ' ELSE N'' END
                )
        ) AS c
    WHERE LEN(c.new_value) > 0;

    -- INSERT: a new login account (student, instructor or admin).
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'INSERT', N'users', i.user_id, NULL,
           CONCAT(N'username=', i.username, N'; role=', i.role, N'; is_active=',
                  CASE WHEN i.is_active = 1 THEN N'ACTIVE' ELSE N'INACTIVE' END),
           CONCAT(N'Created ', i.role, N' login account, user_id ', i.user_id, N'.')
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.user_id = i.user_id);

    -- DELETE: never used by the application (accounts are deactivated, not
    -- removed), kept so a direct SSMS delete is not invisible.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'DELETE', N'users', d.user_id,
           CONCAT(N'username=', d.username, N'; role=', d.role, N'; is_active=',
                  CASE WHEN d.is_active = 1 THEN N'ACTIVE' ELSE N'INACTIVE' END),
           NULL,
           CONCAT(N'Deleted login account, user_id ', d.user_id, N'.')
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.user_id = d.user_id);
END;
GO

/* ============================================================================
   2. dbo.students
   ============================================================================ */
IF OBJECT_ID('dbo.trg_Student_Audit', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_Student_Audit;
GO

CREATE TRIGGER dbo.trg_Student_Audit
ON dbo.students
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted) AND NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    DECLARE @actor INT = TRY_CONVERT(INT, SESSION_CONTEXT(N'app_user_id'));

    -- UPDATE: record_id is user_id (the "Student ID" every screen shows),
    -- not student_id, matching how account-administration rows were logged
    -- before this migration.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'UPDATE', N'students', i.user_id, c.old_value, c.new_value,
           CONCAT(N'Student ID ', i.user_id, N' updated: ', c.new_value)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.student_id = i.student_id
        CROSS APPLY (
            SELECT
                old_value = CONCAT(
                    CASE WHEN i.first_name <> d.first_name THEN CONCAT(N'first_name=', d.first_name, N'; ') ELSE N'' END,
                    CASE WHEN i.last_name <> d.last_name THEN CONCAT(N'last_name=', d.last_name, N'; ') ELSE N'' END,
                    CASE WHEN i.email <> d.email THEN CONCAT(N'email=', d.email, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.phone, NCHAR(1)) <> ISNULL(d.phone, NCHAR(1))
                         THEN CONCAT(N'phone=', ISNULL(d.phone, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.address, NCHAR(1)) <> ISNULL(d.address, NCHAR(1))
                         THEN CONCAT(N'address=', ISNULL(d.address, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.date_of_birth, '0001-01-01') <> ISNULL(d.date_of_birth, '0001-01-01')
                         THEN CONCAT(N'date_of_birth=', ISNULL(CONVERT(NVARCHAR(10), d.date_of_birth, 23), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.gender, NCHAR(1)) <> ISNULL(d.gender, NCHAR(1))
                         THEN CONCAT(N'gender=', ISNULL(d.gender, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.program_id <> d.program_id THEN CONCAT(N'program_id=', d.program_id, N'; ') ELSE N'' END,
                    CASE WHEN i.admission_date <> d.admission_date
                         THEN CONCAT(N'admission_date=', CONVERT(NVARCHAR(10), d.admission_date, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.status <> d.status THEN CONCAT(N'status=', d.status, N'; ') ELSE N'' END,
                    CASE WHEN i.academic_standing <> d.academic_standing
                         THEN CONCAT(N'academic_standing=', d.academic_standing, N'; ') ELSE N'' END,
                    CASE WHEN i.probation_count <> d.probation_count
                         THEN CONCAT(N'probation_count=', d.probation_count, N'; ') ELSE N'' END
                ),
                new_value = CONCAT(
                    CASE WHEN i.first_name <> d.first_name THEN CONCAT(N'first_name=', i.first_name, N'; ') ELSE N'' END,
                    CASE WHEN i.last_name <> d.last_name THEN CONCAT(N'last_name=', i.last_name, N'; ') ELSE N'' END,
                    CASE WHEN i.email <> d.email THEN CONCAT(N'email=', i.email, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.phone, NCHAR(1)) <> ISNULL(d.phone, NCHAR(1))
                         THEN CONCAT(N'phone=', ISNULL(i.phone, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.address, NCHAR(1)) <> ISNULL(d.address, NCHAR(1))
                         THEN CONCAT(N'address=', ISNULL(i.address, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.date_of_birth, '0001-01-01') <> ISNULL(d.date_of_birth, '0001-01-01')
                         THEN CONCAT(N'date_of_birth=', ISNULL(CONVERT(NVARCHAR(10), i.date_of_birth, 23), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.gender, NCHAR(1)) <> ISNULL(d.gender, NCHAR(1))
                         THEN CONCAT(N'gender=', ISNULL(i.gender, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.program_id <> d.program_id THEN CONCAT(N'program_id=', i.program_id, N'; ') ELSE N'' END,
                    CASE WHEN i.admission_date <> d.admission_date
                         THEN CONCAT(N'admission_date=', CONVERT(NVARCHAR(10), i.admission_date, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.status <> d.status THEN CONCAT(N'status=', i.status, N'; ') ELSE N'' END,
                    CASE WHEN i.academic_standing <> d.academic_standing
                         THEN CONCAT(N'academic_standing=', i.academic_standing, N'; ') ELSE N'' END,
                    CASE WHEN i.probation_count <> d.probation_count
                         THEN CONCAT(N'probation_count=', i.probation_count, N'; ') ELSE N'' END
                )
        ) AS c
    WHERE LEN(c.new_value) > 0;

    -- INSERT: a new student record.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'INSERT', N'students', i.user_id, NULL,
           CONCAT(N'first_name=', i.first_name, N'; last_name=', i.last_name, N'; email=', i.email,
                  N'; program_id=', i.program_id, N'; status=', i.status),
           CONCAT(N'Created student record for Student ID ', i.user_id, N': ',
                  i.first_name, N' ', i.last_name, N'.')
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.student_id = i.student_id);

    -- DELETE: nothing is ever deleted by the application (status -> WITHDRAWN
    -- instead), kept so a direct SSMS delete is not invisible.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'DELETE', N'students', d.user_id,
           CONCAT(N'first_name=', d.first_name, N'; last_name=', d.last_name, N'; email=', d.email,
                  N'; program_id=', d.program_id, N'; status=', d.status),
           NULL,
           CONCAT(N'Deleted student record for Student ID ', d.user_id, N': ', d.first_name, N' ', d.last_name, N'.')
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.student_id = d.student_id);
END;
GO

/* ============================================================================
   3. dbo.instructors
   ============================================================================ */
IF OBJECT_ID('dbo.trg_Instructor_Audit', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_Instructor_Audit;
GO

CREATE TRIGGER dbo.trg_Instructor_Audit
ON dbo.instructors
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted) AND NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    DECLARE @actor INT = TRY_CONVERT(INT, SESSION_CONTEXT(N'app_user_id'));

    -- UPDATE: record_id is user_id (the "Instructor ID" every screen shows).
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'UPDATE', N'instructors', i.user_id, c.old_value, c.new_value,
           CONCAT(N'Instructor ID ', i.user_id, N' updated: ', c.new_value)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.instructor_id = i.instructor_id
        CROSS APPLY (
            SELECT
                old_value = CONCAT(
                    CASE WHEN i.first_name <> d.first_name THEN CONCAT(N'first_name=', d.first_name, N'; ') ELSE N'' END,
                    CASE WHEN i.last_name <> d.last_name THEN CONCAT(N'last_name=', d.last_name, N'; ') ELSE N'' END,
                    CASE WHEN i.email <> d.email THEN CONCAT(N'email=', d.email, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.phone, NCHAR(1)) <> ISNULL(d.phone, NCHAR(1))
                         THEN CONCAT(N'phone=', ISNULL(d.phone, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.address, NCHAR(1)) <> ISNULL(d.address, NCHAR(1))
                         THEN CONCAT(N'address=', ISNULL(d.address, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.dept_id <> d.dept_id THEN CONCAT(N'dept_id=', d.dept_id, N'; ') ELSE N'' END,
                    CASE WHEN i.academic_rank <> d.academic_rank THEN CONCAT(N'academic_rank=', d.academic_rank, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.hire_date, '0001-01-01') <> ISNULL(d.hire_date, '0001-01-01')
                         THEN CONCAT(N'hire_date=', ISNULL(CONVERT(NVARCHAR(10), d.hire_date, 23), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.is_active <> d.is_active
                         THEN CONCAT(N'is_active=', CASE WHEN d.is_active = 1 THEN N'ACTIVE' ELSE N'INACTIVE' END, N'; ')
                         ELSE N'' END
                ),
                new_value = CONCAT(
                    CASE WHEN i.first_name <> d.first_name THEN CONCAT(N'first_name=', i.first_name, N'; ') ELSE N'' END,
                    CASE WHEN i.last_name <> d.last_name THEN CONCAT(N'last_name=', i.last_name, N'; ') ELSE N'' END,
                    CASE WHEN i.email <> d.email THEN CONCAT(N'email=', i.email, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.phone, NCHAR(1)) <> ISNULL(d.phone, NCHAR(1))
                         THEN CONCAT(N'phone=', ISNULL(i.phone, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.address, NCHAR(1)) <> ISNULL(d.address, NCHAR(1))
                         THEN CONCAT(N'address=', ISNULL(i.address, N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.dept_id <> d.dept_id THEN CONCAT(N'dept_id=', i.dept_id, N'; ') ELSE N'' END,
                    CASE WHEN i.academic_rank <> d.academic_rank THEN CONCAT(N'academic_rank=', i.academic_rank, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.hire_date, '0001-01-01') <> ISNULL(d.hire_date, '0001-01-01')
                         THEN CONCAT(N'hire_date=', ISNULL(CONVERT(NVARCHAR(10), i.hire_date, 23), N'(none)'), N'; ') ELSE N'' END,
                    CASE WHEN i.is_active <> d.is_active
                         THEN CONCAT(N'is_active=', CASE WHEN i.is_active = 1 THEN N'ACTIVE' ELSE N'INACTIVE' END, N'; ')
                         ELSE N'' END
                )
        ) AS c
    WHERE LEN(c.new_value) > 0;

    -- INSERT: a new instructor record.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'INSERT', N'instructors', i.user_id, NULL,
           CONCAT(N'first_name=', i.first_name, N'; last_name=', i.last_name, N'; email=', i.email,
                  N'; dept_id=', i.dept_id, N'; academic_rank=', i.academic_rank),
           CONCAT(N'Created instructor record for Instructor ID ', i.user_id, N': ',
                  i.first_name, N' ', i.last_name, N'.')
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.instructor_id = i.instructor_id);

    -- DELETE: nothing is ever deleted by the application (is_active -> 0
    -- instead), kept so a direct SSMS delete is not invisible.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'DELETE', N'instructors', d.user_id,
           CONCAT(N'first_name=', d.first_name, N'; last_name=', d.last_name, N'; email=', d.email,
                  N'; dept_id=', d.dept_id, N'; academic_rank=', d.academic_rank),
           NULL,
           CONCAT(N'Deleted instructor record for Instructor ID ', d.user_id, N': ', d.first_name, N' ', d.last_name, N'.')
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.instructor_id = d.instructor_id);
END;
GO

/* ============================================================================
   4. dbo.courses
   ============================================================================ */
IF OBJECT_ID('dbo.trg_Course_Audit', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_Course_Audit;
GO

CREATE TRIGGER dbo.trg_Course_Audit
ON dbo.courses
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted) AND NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    DECLARE @actor INT = TRY_CONVERT(INT, SESSION_CONTEXT(N'app_user_id'));

    -- UPDATE
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'UPDATE', N'courses', i.course_id, c.old_value, c.new_value,
           CONCAT(N'Course ', i.course_code, N' (course_id ', i.course_id, N') updated: ', c.new_value)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.course_id = i.course_id
        CROSS APPLY (
            SELECT
                old_value = CONCAT(
                    CASE WHEN i.course_code <> d.course_code THEN CONCAT(N'course_code=', d.course_code, N'; ') ELSE N'' END,
                    CASE WHEN i.course_title <> d.course_title THEN CONCAT(N'course_title=', d.course_title, N'; ') ELSE N'' END,
                    CASE WHEN i.credits <> d.credits THEN CONCAT(N'credits=', d.credits, N'; ') ELSE N'' END,
                    CASE WHEN i.dept_id <> d.dept_id THEN CONCAT(N'dept_id=', d.dept_id, N'; ') ELSE N'' END,
                    CASE WHEN i.level_year <> d.level_year THEN CONCAT(N'level_year=', d.level_year, N'; ') ELSE N'' END,
                    CASE WHEN i.has_lab <> d.has_lab THEN CONCAT(N'has_lab=', d.has_lab, N'; ') ELSE N'' END,
                    CASE WHEN i.is_active <> d.is_active THEN CONCAT(N'is_active=', d.is_active, N'; ') ELSE N'' END
                ),
                new_value = CONCAT(
                    CASE WHEN i.course_code <> d.course_code THEN CONCAT(N'course_code=', i.course_code, N'; ') ELSE N'' END,
                    CASE WHEN i.course_title <> d.course_title THEN CONCAT(N'course_title=', i.course_title, N'; ') ELSE N'' END,
                    CASE WHEN i.credits <> d.credits THEN CONCAT(N'credits=', i.credits, N'; ') ELSE N'' END,
                    CASE WHEN i.dept_id <> d.dept_id THEN CONCAT(N'dept_id=', i.dept_id, N'; ') ELSE N'' END,
                    CASE WHEN i.level_year <> d.level_year THEN CONCAT(N'level_year=', i.level_year, N'; ') ELSE N'' END,
                    CASE WHEN i.has_lab <> d.has_lab THEN CONCAT(N'has_lab=', i.has_lab, N'; ') ELSE N'' END,
                    CASE WHEN i.is_active <> d.is_active THEN CONCAT(N'is_active=', i.is_active, N'; ') ELSE N'' END
                )
        ) AS c
    WHERE LEN(c.new_value) > 0;

    -- INSERT
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'INSERT', N'courses', i.course_id, NULL,
           CONCAT(N'course_code=', i.course_code, N'; course_title=', i.course_title,
                  N'; credits=', i.credits, N'; dept_id=', i.dept_id),
           CONCAT(N'Created course ', i.course_code, N' - ', i.course_title, N' (', i.credits, N' credits).')
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.course_id = i.course_id);

    -- DELETE
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'DELETE', N'courses', d.course_id,
           CONCAT(N'course_code=', d.course_code, N'; course_title=', d.course_title,
                  N'; credits=', d.credits, N'; dept_id=', d.dept_id),
           NULL,
           CONCAT(N'Deleted course ', d.course_code, N' - ', d.course_title, N'.')
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.course_id = d.course_id);
END;
GO

/* ============================================================================
   5. dbo.semesters
   ============================================================================ */
IF OBJECT_ID('dbo.trg_Semester_Audit', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_Semester_Audit;
GO

CREATE TRIGGER dbo.trg_Semester_Audit
ON dbo.semesters
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted) AND NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    DECLARE @actor INT = TRY_CONVERT(INT, SESSION_CONTEXT(N'app_user_id'));

    -- UPDATE: covers both the ordinary edit screen and SemesterDAO.makeCurrent /
    -- closeCurrent, which only ever touch is_current - the diff below reacts to
    -- the row's final values, not to which statement produced them.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'UPDATE', N'semesters', i.semester_id, c.old_value, c.new_value,
           CONCAT(N'Semester ', i.semester_name, N' (semester_id ', i.semester_id, N') updated: ', c.new_value)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.semester_id = i.semester_id
        CROSS APPLY (
            SELECT
                old_value = CONCAT(
                    CASE WHEN i.semester_name <> d.semester_name THEN CONCAT(N'semester_name=', d.semester_name, N'; ') ELSE N'' END,
                    CASE WHEN i.academic_year <> d.academic_year THEN CONCAT(N'academic_year=', d.academic_year, N'; ') ELSE N'' END,
                    CASE WHEN i.term <> d.term THEN CONCAT(N'term=', d.term, N'; ') ELSE N'' END,
                    CASE WHEN i.start_date <> d.start_date THEN CONCAT(N'start_date=', CONVERT(NVARCHAR(10), d.start_date, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.end_date <> d.end_date THEN CONCAT(N'end_date=', CONVERT(NVARCHAR(10), d.end_date, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.registration_start <> d.registration_start
                         THEN CONCAT(N'registration_start=', CONVERT(NVARCHAR(19), d.registration_start, 120), N'; ') ELSE N'' END,
                    CASE WHEN i.registration_end <> d.registration_end
                         THEN CONCAT(N'registration_end=', CONVERT(NVARCHAR(19), d.registration_end, 120), N'; ') ELSE N'' END,
                    CASE WHEN i.drop_deadline <> d.drop_deadline THEN CONCAT(N'drop_deadline=', CONVERT(NVARCHAR(10), d.drop_deadline, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.withdraw_deadline <> d.withdraw_deadline
                         THEN CONCAT(N'withdraw_deadline=', CONVERT(NVARCHAR(10), d.withdraw_deadline, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.grade_entry_start <> d.grade_entry_start
                         THEN CONCAT(N'grade_entry_start=', CONVERT(NVARCHAR(10), d.grade_entry_start, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.grade_entry_end <> d.grade_entry_end
                         THEN CONCAT(N'grade_entry_end=', CONVERT(NVARCHAR(10), d.grade_entry_end, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.is_current <> d.is_current THEN CONCAT(N'is_current=', d.is_current, N'; ') ELSE N'' END
                ),
                new_value = CONCAT(
                    CASE WHEN i.semester_name <> d.semester_name THEN CONCAT(N'semester_name=', i.semester_name, N'; ') ELSE N'' END,
                    CASE WHEN i.academic_year <> d.academic_year THEN CONCAT(N'academic_year=', i.academic_year, N'; ') ELSE N'' END,
                    CASE WHEN i.term <> d.term THEN CONCAT(N'term=', i.term, N'; ') ELSE N'' END,
                    CASE WHEN i.start_date <> d.start_date THEN CONCAT(N'start_date=', CONVERT(NVARCHAR(10), i.start_date, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.end_date <> d.end_date THEN CONCAT(N'end_date=', CONVERT(NVARCHAR(10), i.end_date, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.registration_start <> d.registration_start
                         THEN CONCAT(N'registration_start=', CONVERT(NVARCHAR(19), i.registration_start, 120), N'; ') ELSE N'' END,
                    CASE WHEN i.registration_end <> d.registration_end
                         THEN CONCAT(N'registration_end=', CONVERT(NVARCHAR(19), i.registration_end, 120), N'; ') ELSE N'' END,
                    CASE WHEN i.drop_deadline <> d.drop_deadline THEN CONCAT(N'drop_deadline=', CONVERT(NVARCHAR(10), i.drop_deadline, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.withdraw_deadline <> d.withdraw_deadline
                         THEN CONCAT(N'withdraw_deadline=', CONVERT(NVARCHAR(10), i.withdraw_deadline, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.grade_entry_start <> d.grade_entry_start
                         THEN CONCAT(N'grade_entry_start=', CONVERT(NVARCHAR(10), i.grade_entry_start, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.grade_entry_end <> d.grade_entry_end
                         THEN CONCAT(N'grade_entry_end=', CONVERT(NVARCHAR(10), i.grade_entry_end, 23), N'; ') ELSE N'' END,
                    CASE WHEN i.is_current <> d.is_current THEN CONCAT(N'is_current=', i.is_current, N'; ') ELSE N'' END
                )
        ) AS c
    WHERE LEN(c.new_value) > 0;

    -- INSERT
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'INSERT', N'semesters', i.semester_id, NULL,
           CONCAT(N'semester_name=', i.semester_name, N'; term=', i.term, N'; academic_year=', i.academic_year,
                  N'; start_date=', CONVERT(NVARCHAR(10), i.start_date, 23),
                  N'; end_date=', CONVERT(NVARCHAR(10), i.end_date, 23)),
           CONCAT(N'Created semester ', i.semester_name, N'.')
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.semester_id = i.semester_id);

    -- DELETE
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'DELETE', N'semesters', d.semester_id,
           CONCAT(N'semester_name=', d.semester_name, N'; term=', d.term, N'; academic_year=', d.academic_year),
           NULL,
           CONCAT(N'Deleted semester ', d.semester_name, N'.')
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.semester_id = d.semester_id);
END;
GO

/* ============================================================================
   6. dbo.enrollments - registration and drop/withdraw/complete transitions.
   ============================================================================ */
IF OBJECT_ID('dbo.trg_Enrollment_Audit', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_Enrollment_Audit;
GO

CREATE TRIGGER dbo.trg_Enrollment_Audit
ON dbo.enrollments
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted) AND NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    DECLARE @actor INT = TRY_CONVERT(INT, SESSION_CONTEXT(N'app_user_id'));

    -- UPDATE
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'UPDATE', N'enrollments', i.enrollment_id, c.old_value, c.new_value,
           CONCAT(N'Enrollment ', i.enrollment_id, N' (student_id ', i.student_id, N') updated: ', c.new_value)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.enrollment_id = i.enrollment_id
        CROSS APPLY (
            SELECT
                old_value = CONCAT(
                    CASE WHEN i.status <> d.status THEN CONCAT(N'status=', d.status, N'; ') ELSE N'' END,
                    CASE WHEN i.is_repeat <> d.is_repeat THEN CONCAT(N'is_repeat=', d.is_repeat, N'; ') ELSE N'' END,
                    CASE WHEN i.counts_in_gpa <> d.counts_in_gpa THEN CONCAT(N'counts_in_gpa=', d.counts_in_gpa, N'; ') ELSE N'' END
                ),
                new_value = CONCAT(
                    CASE WHEN i.status <> d.status THEN CONCAT(N'status=', i.status, N'; ') ELSE N'' END,
                    CASE WHEN i.is_repeat <> d.is_repeat THEN CONCAT(N'is_repeat=', i.is_repeat, N'; ') ELSE N'' END,
                    CASE WHEN i.counts_in_gpa <> d.counts_in_gpa THEN CONCAT(N'counts_in_gpa=', i.counts_in_gpa, N'; ') ELSE N'' END
                )
        ) AS c
    WHERE LEN(c.new_value) > 0;

    -- INSERT: a student registers for a section.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'INSERT', N'enrollments', i.enrollment_id, NULL,
           CONCAT(N'student_id=', i.student_id, N'; section_id=', i.section_id, N'; status=', i.status),
           CONCAT(N'Student ', i.student_id, N' registered for section ', i.section_id, N'.')
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.enrollment_id = i.enrollment_id);

    -- DELETE
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT @actor, N'DELETE', N'enrollments', d.enrollment_id,
           CONCAT(N'student_id=', d.student_id, N'; section_id=', d.section_id, N'; status=', d.status),
           NULL,
           CONCAT(N'Enrollment ', d.enrollment_id, N' for student ', d.student_id, N' deleted.')
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.enrollment_id = d.enrollment_id);
END;
GO

/* ============================================================================
   7. dbo.payments - money received. recorded_by is written by FinanceService
      the same way grades.submitted_by is written by GradeService, so the
      actor comes straight from the row, the same pattern trg_Grade_Audit uses,
      not from SESSION_CONTEXT.
   ============================================================================ */
IF OBJECT_ID('dbo.trg_Payment_Audit', 'TR') IS NOT NULL
    DROP TRIGGER dbo.trg_Payment_Audit;
GO

CREATE TRIGGER dbo.trg_Payment_Audit
ON dbo.payments
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM inserted) AND NOT EXISTS (SELECT 1 FROM deleted)
        RETURN;

    -- UPDATE: e.g. marking a payment FAILED / REFUNDED, or correcting the
    -- reference number. amount/payment_method/payment_status/reference_number
    -- are the only columns PaymentDAO.update ever changes.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT COALESCE(i.recorded_by, d.recorded_by), N'UPDATE', N'payments', i.payment_id, c.old_value, c.new_value,
           CONCAT(N'Payment ', i.payment_id, N' (receipt ', i.receipt_number, N') updated: ', c.new_value)
    FROM inserted AS i
        INNER JOIN deleted AS d ON d.payment_id = i.payment_id
        CROSS APPLY (
            SELECT
                old_value = CONCAT(
                    CASE WHEN i.amount <> d.amount THEN CONCAT(N'amount=', CONVERT(NVARCHAR(20), d.amount), N'; ') ELSE N'' END,
                    CASE WHEN i.payment_method <> d.payment_method THEN CONCAT(N'payment_method=', d.payment_method, N'; ') ELSE N'' END,
                    CASE WHEN i.payment_status <> d.payment_status THEN CONCAT(N'payment_status=', d.payment_status, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.reference_number, NCHAR(1)) <> ISNULL(d.reference_number, NCHAR(1))
                         THEN CONCAT(N'reference_number=', ISNULL(d.reference_number, N'(none)'), N'; ') ELSE N'' END
                ),
                new_value = CONCAT(
                    CASE WHEN i.amount <> d.amount THEN CONCAT(N'amount=', CONVERT(NVARCHAR(20), i.amount), N'; ') ELSE N'' END,
                    CASE WHEN i.payment_method <> d.payment_method THEN CONCAT(N'payment_method=', i.payment_method, N'; ') ELSE N'' END,
                    CASE WHEN i.payment_status <> d.payment_status THEN CONCAT(N'payment_status=', i.payment_status, N'; ') ELSE N'' END,
                    CASE WHEN ISNULL(i.reference_number, NCHAR(1)) <> ISNULL(d.reference_number, NCHAR(1))
                         THEN CONCAT(N'reference_number=', ISNULL(i.reference_number, N'(none)'), N'; ') ELSE N'' END
                )
        ) AS c
    WHERE LEN(c.new_value) > 0;

    -- INSERT: money received against an invoice.
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT i.recorded_by, N'INSERT', N'payments', i.payment_id, NULL,
           CONCAT(N'invoice_id=', i.invoice_id, N'; student_id=', i.student_id, N'; amount=',
                  CONVERT(NVARCHAR(20), i.amount), N'; payment_method=', i.payment_method,
                  N'; payment_status=', i.payment_status),
           CONCAT(N'Payment of ', CONVERT(NVARCHAR(20), i.amount), N' recorded for student ', i.student_id,
                  N', receipt ', i.receipt_number, N', against invoice ', i.invoice_id, N'.')
    FROM inserted AS i
    WHERE NOT EXISTS (SELECT 1 FROM deleted AS d WHERE d.payment_id = i.payment_id);

    -- DELETE
    INSERT INTO dbo.audit_log (user_id, action_type, table_name, record_id, old_value, new_value, description)
    SELECT d.recorded_by, N'DELETE', N'payments', d.payment_id,
           CONCAT(N'invoice_id=', d.invoice_id, N'; student_id=', d.student_id, N'; amount=',
                  CONVERT(NVARCHAR(20), d.amount), N'; receipt_number=', d.receipt_number),
           NULL,
           CONCAT(N'Payment ', d.payment_id, N' (receipt ', d.receipt_number, N') deleted.')
    FROM deleted AS d
    WHERE NOT EXISTS (SELECT 1 FROM inserted AS i WHERE i.payment_id = d.payment_id);
END;
GO

/* ---------------------------------------------------------------- verify */
SELECT name, OBJECT_NAME(parent_id) AS audited_table
FROM sys.triggers
WHERE name IN (N'trg_User_Audit', N'trg_Student_Audit', N'trg_Instructor_Audit',
               N'trg_Course_Audit', N'trg_Semester_Audit', N'trg_Enrollment_Audit',
               N'trg_Payment_Audit')
ORDER BY audited_table;
GO
