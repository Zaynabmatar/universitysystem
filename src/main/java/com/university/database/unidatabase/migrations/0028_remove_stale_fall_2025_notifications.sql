/* ==========================================================================
   0028 — remove two stray notifications that misreport Fall 2025 as current

   WHY
     notification_id 25702 ("Fall 2025 tuition invoices issued", user_id 1)
     and 25703 ("Grade submission deadline reminder" ... "your Fall 2025
     sections", user_id 2) were both written at the same instant,
     2026-08-16 19:44:47.9266667. No code path in this application ever
     produces either message (grep across src/main/java for "tuition
     invoices issued" and "Grade submission deadline reminder" finds
     nothing), no matching dbo.student_invoices rows exist for semester_id 2
     around that time, and dbo.semesters.is_current had already pointed at
     Fall 2026 / the current semester since migration
     phase21_prepare_and_advance_to_fall_2026.sql (applied 2026-08-08,
     before either row was written). They are hand-inserted leftovers from
     testing, not history: on the day they were created the university was
     not in Fall 2025, so — unlike the many genuine "Welcome back for Fall
     2025" notifications sent back when Fall 2025 really was current — these
     two do not describe anything that was ever true. (Compare
     notification_id 25705, "Summer 2026 tuition invoices issued", created
     six minutes later for the same admin: the accurate follow-up that was
     never actually cleaned up alongside its stale predecessor.)

     Genuine historical semester data — real Fall 2025 / Spring 2025
     enrollments, grades, invoices, and the notifications sent while those
     semesters were actually current — is untouched by this migration.

   SAFE TO RE-RUN
     Deletes by primary key; a second run finds nothing left to delete.
============================================================================ */

SET NOCOUNT ON;

DELETE FROM dbo.notifications WHERE notification_id IN (25702, 25703);

PRINT N'0028: stale Fall 2025 notifications removed = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
