/* ============================================================================
   0001 — index dbo.users (role)

   The first migration applied by MigrationRunner rather than by hand.

   WHY
     UserDAO.findByRole() runs "SELECT ... FROM dbo.users WHERE role = ?
     ORDER BY username", and dbo.users has no index on role at all: only
     PK_users (user_id) and UQ_users_username. Every one of those calls is a
     full scan of the table. Role Selection and the admin account screens are
     built on exactly that query.

   WHAT IT DOES
     Adds one non-clustered index. It creates no row, changes no row, and
     deletes no row — an index is derived data, so this cannot affect a single
     student, instructor, course or user account.

   SAFE TO RE-RUN
     Guarded on sys.indexes, so a second run is a no-op. (The runner will not
     run it twice in any case; the guard is there so the file can also be run
     by hand against a database that already has the index.)
============================================================================ */

SET NOCOUNT ON;

IF NOT EXISTS (SELECT 1
                 FROM sys.indexes
                WHERE name = N'IX_users_role'
                  AND object_id = OBJECT_ID(N'dbo.users'))
BEGIN
    PRINT N'0001: creating IX_users_role on dbo.users (role).';

    CREATE NONCLUSTERED INDEX IX_users_role
        ON dbo.users (role)
        INCLUDE (username, is_active);
END
ELSE
BEGIN
    PRINT N'0001: IX_users_role already exists - nothing to do.';
END
