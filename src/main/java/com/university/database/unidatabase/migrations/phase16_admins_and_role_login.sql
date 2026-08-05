/* ============================================================================
   PHASE 16 MIGRATION — dbo.admins + role-scoped login
   ----------------------------------------------------------------------------
   Adds dbo.admins, the missing third role-specific id table: students and
   instructors already have their own IDENTITY(1,1) primary key one-to-one
   with dbo.users, but an admin's only identity has been the global
   dbo.users.user_id. This gives admins the same shape, so the new Role
   Selection + Login flow can look every role up by its own id (Admin ID /
   Instructor ID / Student ID) instead of the shared user_id.

   Run once against an existing universitymanagementDB. Safe to re-run: the
   CREATE TABLE is guarded, and the backfill only inserts rows that are not
   already there.

   This migration does NOT touch passwords — BCrypt hashing cannot be done in
   T-SQL. Run RolePasswordMigration.main() (Java) straight after this script
   to rehash every account's password to the new "<role-specific-id>@iuL"
   rule; see that class and the PASSWORD NOTE in universitymanagmentDB.sql.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET NOCOUNT ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

-- ---------------------------------------------------------------- 1. dbo.admins
IF OBJECT_ID('dbo.admins', 'U') IS NULL
BEGIN
    PRINT N'--- Creating dbo.admins ---';

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
END
ELSE
BEGIN
    PRINT N'--- dbo.admins already exists - skipping create ---';
END

-- ---------------------------------------------------------------- 2. backfill
-- One dbo.admins row per existing ADMIN account, in user_id order, so the
-- IDENTITY assigns admin_id 1, 2, 3, ... in the same order those accounts
-- were originally created.
PRINT N'--- Backfilling dbo.admins from dbo.users (role = ADMIN) ---';

INSERT INTO dbo.admins (user_id, is_active)
SELECT u.user_id, u.is_active
FROM dbo.users u
WHERE u.role = N'ADMIN'
  AND NOT EXISTS (SELECT 1 FROM dbo.admins a WHERE a.user_id = u.user_id)
ORDER BY u.user_id;

COMMIT TRANSACTION;
PRINT N'>>> PHASE 16 migration (dbo.admins) completed.';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! PHASE 16 migration FAILED, rolled back: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO
