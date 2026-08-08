/* ============================================================================
   PHASE 18 MIGRATION — retire dbo.admins as a competing login identity
   ----------------------------------------------------------------------------
   WHY THIS EXISTS

   phase16_admins_and_role_login.sql gave every role its own IDENTITY-backed id
   table (students.student_id, instructors.instructor_id, admins.admin_id) so
   the sign-in screen could look an account up by "the role's own number."
   phase17_user_id_as_the_only_identity.sql documents the decision to undo
   that and make dbo.users.user_id the one login identity again — but it only
   ever touched dbo.users.email; nothing in that script, or in any later one,
   actually removed dbo.admins or changed how sign-in looks an account up.
   AuthService.java was still resolving the number typed on the Admin door
   against admins.admin_id (a second IDENTITY sequence, unrelated to user_id)
   right up until this phase's Java changes fixed that in the same commit as
   this script.

   dbo.admins carries no Admin-specific profile data at all — only admin_id,
   user_id and is_active, and is_active only ever duplicates dbo.users.is_active.
   Checked before writing this script:
     * zero foreign keys anywhere in the database reference dbo.admins;
     * no service class creates an admins row (AdminDAO.insert is dead code —
       nothing calls it outside AdminDAO itself);
     * the only two places that ever read dbo.admins were AuthService.java
       (sign-in) and RolePasswordMigration.java (a one-off dev tool), both
       updated by this phase to use dbo.users directly.
   That makes it exactly the case the brief calls out: an admin identity table
   with nothing in it worth migrating, so it is dropped rather than kept as a
   second, unused login namespace. An ADMIN account is now represented purely
   by dbo.users.role = 'ADMIN' — the same way it is read everywhere else in
   the application already.

   ----------------------------------------------------------------------------
   This script also drops dbo.advisors_directory, for the same underlying
   reason and confirmed the same way. It is a hand-typed list of 9 "advisors"
   with no foreign key to dbo.instructors, dbo.users or dbo.departments at
   all — a second, manually-maintained, already-stale copy of information the
   53-row dbo.instructors table already holds, exactly the kind of duplicate
   directory the brief says not to keep. A full-repo search found zero
   controller, service or FXML reference to it — it backs no live screen —
   so there is nothing to redirect, only a table to remove. Any future
   Instructor Directory screen is expected to read dbo.instructors joined to
   dbo.users and dbo.departments directly, which is already the authoritative,
   automatically-current source: creating an instructor already makes them
   appear there with no second insert anywhere.

   SAFE TO RUN ONCE, AND SAFE TO RE-RUN: guarded by OBJECT_ID, and the whole
   thing runs in one transaction that rolls back on any error.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

    IF OBJECT_ID('dbo.admins', 'U') IS NOT NULL
    BEGIN
        -- Belt-and-braces: refuse to drop it if some later change ever added a
        -- real foreign key pointing at it, instead of silently breaking that
        -- reference. (Verified zero such references before writing this script.)
        IF EXISTS (SELECT 1 FROM sys.foreign_keys
                    WHERE referenced_object_id = OBJECT_ID('dbo.admins'))
        BEGIN
            THROW 51002, N'dbo.admins still has an incoming foreign key - not dropping it. Migrate that reference by hand first.', 1;
        END

        PRINT N'--- Dropping dbo.admins (no profile data, no incoming FKs; '
            + N'ADMIN accounts are dbo.users.role = ''ADMIN'') ---';
        DROP TABLE dbo.admins;
    END
    ELSE
        PRINT N'--- dbo.admins already gone - nothing to drop ---';

    IF OBJECT_ID('dbo.advisors_directory', 'U') IS NOT NULL
    BEGIN
        IF EXISTS (SELECT 1 FROM sys.foreign_keys
                    WHERE referenced_object_id = OBJECT_ID('dbo.advisors_directory'))
        BEGIN
            THROW 51003, N'dbo.advisors_directory still has an incoming foreign key - not dropping it. Migrate that reference by hand first.', 1;
        END

        PRINT N'--- Dropping dbo.advisors_directory (unused duplicate of dbo.instructors) ---';
        DROP TABLE dbo.advisors_directory;
    END
    ELSE
        PRINT N'--- dbo.advisors_directory already gone - nothing to drop ---';

COMMIT TRANSACTION;
PRINT N'--- Phase 18 migration committed. ---';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0
        ROLLBACK TRANSACTION;
    PRINT N'!!! Phase 18 migration ROLLED BACK — nothing was changed. !!!';
    THROW;
END CATCH
GO

/* ---------------------------------------------------------------- verify */
SELECT
    (SELECT COUNT(*) FROM sys.tables WHERE name = 'admins')              AS admins_table_still_present,
    (SELECT COUNT(*) FROM sys.tables WHERE name = 'advisors_directory')  AS advisors_directory_still_present,
    (SELECT COUNT(*) FROM dbo.users WHERE role = 'ADMIN')                AS admin_accounts_in_users,
    (SELECT COUNT(*) FROM dbo.users)                                     AS users_total;
GO
