/* ==========================================================================
   0032 — stop marking ordinary absence notices as red WARNINGs

   WHY
     AttendanceService.saveAttendance sent every "You were marked absent..."
     notice, and the 4-absence notice, as NotificationType.WARNING, so the
     UI (NotificationsController) painted every single absence red via the
     notif-high-absence style -- not just the one that actually matters, the
     5-absence threshold. The source fix (same change set) now sends
     absences 1-4 as NotificationType.INFO and only fires a WARNING once an
     enrollment reaches exactly 5 absences in a course/section.

     This migration repairs the rows that fix already wrote under the old
     logic, for Farah Lahoud (user_id 163, dbo.students.first_name =
     'Farah'), the only student in this database with pre-existing
     attendance notifications: notification_id 54266-54269 ("Attendance
     Notice" / "You were marked absent...") and 54270 ("Attendance Warning"
     / "You have reached 4 absences..."). None of her attendance
     notifications have reached the real 5-absence threshold yet, so all
     five flip from WARNING to INFO. No "reached 5 absences" notification
     exists for her to preserve.

   SAFE TO RE-RUN
     The WHERE clause only ever matches rows still marked WARNING that are
     not the 5-absence threshold message, so a second run finds nothing
     left to update.
============================================================================ */

SET NOCOUNT ON;

UPDATE n
   SET n.type = 'INFO'
  FROM dbo.notifications n
  JOIN dbo.students s ON s.user_id = n.user_id
 WHERE s.first_name = 'Farah'
   AND n.related_entity_type = 'attendance_records'
   AND n.type = 'WARNING'
   AND n.message NOT LIKE '%reached 5 absences%';

PRINT N'0032: Farah attendance notifications corrected = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
