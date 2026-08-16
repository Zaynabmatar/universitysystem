/* ==========================================================================
   0026 - Student/Instructor account lockout after repeated failed sign-ins.

   WHY
     Requirement: after 5 consecutive failed sign-in attempts, a STUDENT or
     INSTRUCTOR account is locked and can no longer sign in, even with the
     correct password, until an administrator unlocks it. ADMIN accounts are
     deliberately out of scope (project_details.md never asked for this on the
     one role that already gates everything else) - AuthService.login only
     ever touches these columns for STUDENT/INSTRUCTOR.

   COLUMNS ADDED TO dbo.users
     failed_login_attempts INT  - consecutive wrong-password count, reset to
                                  0 on a successful sign-in.
     is_locked              BIT - 1 once the counter reaches 5; blocks
                                  sign-in outright (AuthService.login checks
                                  this before verifying the password).
     locked_at          DATETIME2 - when the lock was set; NULL while
                                  unlocked. Not shown anywhere yet, kept for
                                  the day the audit screen wants it.
     All three default to values that leave every EXISTING account unlocked
     (0, 0, NULL) - nobody already in the table is affected.

   AUDIT
     No new Java-side writer: trg_User_Audit (migration 0014) already logs
     every dbo.users UPDATE whose TRACKED columns changed, actor included via
     SESSION_CONTEXT. This migration only adds is_locked to that trigger's
     list of tracked columns, so both the automatic lock (actor NULL - nobody
     is signed in yet when a sign-in fails) and an administrator's "Unlock
     Account" (actor = the admin's user_id) are written to dbo.audit_log by
     the same existing mechanism, the same way is_active already is.
     failed_login_attempts is deliberately NOT tracked: it changes on every
     single failed keystroke-guess and would bury real administrative
     changes in noise, exactly why enrolled_count/cumulative_gpa are excluded
     (see 0014's own notes).

   SAFE TO RE-RUN
     Every ALTER is guarded by COL_LENGTH, and the trigger is dropped and
     recreated if it exists, same as 0014.
   ========================================================================== */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

IF COL_LENGTH('dbo.users', 'failed_login_attempts') IS NULL
BEGIN
    ALTER TABLE dbo.users ADD failed_login_attempts INT NOT NULL
        CONSTRAINT DF_users_failed_login_attempts DEFAULT (0);
END
GO

IF COL_LENGTH('dbo.users', 'is_locked') IS NULL
BEGIN
    ALTER TABLE dbo.users ADD is_locked BIT NOT NULL
        CONSTRAINT DF_users_is_locked DEFAULT (0);
END
GO

IF COL_LENGTH('dbo.users', 'locked_at') IS NULL
BEGIN
    ALTER TABLE dbo.users ADD locked_at DATETIME2 NULL;
END
GO

/* ============================================================================
   trg_User_Audit - add is_locked to the set of tracked columns, everything
   else identical to migration 0014's version.
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
                    CASE WHEN i.is_locked <> d.is_locked
                         THEN CONCAT(N'is_locked=', CASE WHEN d.is_locked = 1 THEN N'LOCKED' ELSE N'UNLOCKED' END, N'; ')
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
                    CASE WHEN i.is_locked <> d.is_locked
                         THEN CONCAT(N'is_locked=', CASE WHEN i.is_locked = 1 THEN N'LOCKED' ELSE N'UNLOCKED' END, N'; ')
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
