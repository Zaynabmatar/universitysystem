/* ============================================================================
   0008 — contact columns needed by the self-service account settings pages
   (Change Password / My Email / Change Address / List of Instructors)

   WHY
     Student already has students.address and a real email column
     (students.email), so self-service My Email / Change Address already had
     somewhere to write for that role. Instructor had no address column at
     all, and Admin had neither an email nor an address anywhere — dbo.admins
     was retired in phase18 and dbo.users deliberately carries no contact
     columns (see the removed comment on User.java). Both gaps blocked the
     Admin/Instructor self-service pages from working symmetrically with
     Student's.

     users.email / users.address are used only by the ADMIN role. Student and
     Instructor keep using students.email/address and instructors.email
     exactly as before; nothing here changes their existing code paths.

   SAFE TO RE-RUN
     Every ALTER is guarded by COL_LENGTH, so a second run adds nothing.
============================================================================ */

IF COL_LENGTH('dbo.instructors', 'address') IS NULL
BEGIN
    ALTER TABLE dbo.instructors ADD address NVARCHAR(200) NULL;
END

IF COL_LENGTH('dbo.users', 'email') IS NULL
BEGIN
    ALTER TABLE dbo.users ADD email NVARCHAR(100) NULL;
END

IF COL_LENGTH('dbo.users', 'address') IS NULL
BEGIN
    ALTER TABLE dbo.users ADD address NVARCHAR(200) NULL;
END
