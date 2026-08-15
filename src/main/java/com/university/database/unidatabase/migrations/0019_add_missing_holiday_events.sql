-- The Academic Calendar (Admin + Student) paints a HOLIDAY event yellow purely from
-- event_type (enums.CalendarEventType / app.css .cal-holiday) -- no date is ever
-- hardcoded in that mapping. But dbo.calendar_events had no rows at all for
-- 2026-09-04, 2026-09-30 and 2026-10-08, the semester's holidays, so nothing painted
-- yellow on those days. This adds them as HOLIDAY, scoped to the current semester,
-- guarded so re-running (or running on a database where they were added by hand
-- meanwhile) is a no-op.

INSERT INTO dbo.calendar_events (semester_id, title, event_type, start_date, end_date)
SELECT sem.semester_id, N'Holiday', N'HOLIDAY', d.holiday_date, d.holiday_date
FROM dbo.semesters sem
CROSS JOIN (VALUES (CAST('2026-09-04' AS DATE)),
                    (CAST('2026-09-30' AS DATE)),
                    (CAST('2026-10-08' AS DATE))) AS d(holiday_date)
WHERE sem.is_current = 1
  -- Not bounded by sem.start_date/end_date: Fall 2026 starts 2026-09-14, which
  -- is after 2026-09-04, one of the three known holidays that must be seeded.
  AND NOT EXISTS (
      SELECT 1 FROM dbo.calendar_events ce
      WHERE ce.semester_id = sem.semester_id
        AND ce.start_date = d.holiday_date
        AND ce.end_date = d.holiday_date
  );
GO
