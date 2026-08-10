/* ============================================================================
   SEED 06 -- university_news
   ONLY INSERT statements below (Section 14 / user requirement).

   dbo.advisors_directory is no longer seeded here. It was a hand-typed list
   of 9 "advisors" completely disconnected from dbo.instructors (no FK to
   instructors, users or departments at all — see its own former Javadoc),
   duplicating information the real dbo.instructors/dbo.users/dbo.departments
   tables already hold for all 53 real instructors. Phase 20 of the database-
   foundation cleanup drops the table; nothing in the application ever read
   it (grep found zero controller/service references), so there is nothing
   left here to seed it for.

   The assignments feature itself (dbo.assignments) has since been retired
   outright (migration 0012) — this file used to also seed that table; only
   the university_news insert remains.
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

INSERT INTO dbo.university_news (title, content, category, publication_date, expiry_date, is_published, created_by)
SELECT v.title, v.content, v.category, v.pubdate, v.expiry, v.published, u.user_id
FROM (VALUES
    (N'Fall 2025 Midterm Schedule Released', N'The midterm examination schedule for Fall 2025 is now available on the student portal. Please check your section times carefully.', N'ACADEMIC', CAST('2025-10-26T00:00:00' AS DATETIME2), NULL, 1, N'admin'),
    (N'New Scholarship Program for Engineering Students', N'The Faculty of Engineering announces a new merit scholarship covering up to 50% of tuition for top-performing students.', N'FINANCE', CAST('2025-12-08T00:00:00' AS DATETIME2), NULL, 1, N'admin'),
    (N'Career Fair 2026', N'Over 40 companies will attend the annual career fair at the Khaldeh Campus main hall on 12 March 2026.', N'EVENT', CAST('2025-11-14T00:00:00' AS DATETIME2), NULL, 1, N'admin'),
    (N'Library Extended Hours During Finals', N'The library will remain open 24 hours during the final exam period, from 10 to 24 January 2026.', N'GENERAL', CAST('2025-10-18T00:00:00' AS DATETIME2), CAST('2025-12-17T00:00:00' AS DATETIME2), 1, N'admin'),
    (N'New Pharmacy Lab Opens', N'The Faculty of Pharmacy has opened a new pharmaceutics laboratory equipped with modern compounding stations.', N'FACILITY', CAST('2025-11-12T00:00:00' AS DATETIME2), CAST('2026-01-11T00:00:00' AS DATETIME2), 1, N'admin'),
    (N'Registration for Spring 2026 Opens Soon', N'Students should meet their advisors before the registration window opens to plan their Spring 2026 course load.', N'ACADEMIC', CAST('2025-11-18T00:00:00' AS DATETIME2), CAST('2026-01-17T00:00:00' AS DATETIME2), 1, N'admin'),
    (N'Guest Lecture: AI in Healthcare', N'The Computer Science department hosts a guest lecture on applications of machine learning in healthcare on 20 February 2026.', N'EVENT', CAST('2025-12-02T00:00:00' AS DATETIME2), CAST('2026-01-31T00:00:00' AS DATETIME2), 1, N'admin'),
    (N'Campus Shuttle Schedule Update', N'A new shuttle route now connects the Khaldeh and Nabatieh campuses every 30 minutes.', N'GENERAL', CAST('2025-12-12T00:00:00' AS DATETIME2), CAST('2026-02-10T00:00:00' AS DATETIME2), 1, N'admin'),
    (N'Law Moot Court Competition Winners', N'Congratulations to the Faculty of Law team for winning the regional moot court competition.', N'ACADEMIC', CAST('2025-10-28T00:00:00' AS DATETIME2), CAST('2025-12-27T00:00:00' AS DATETIME2), 1, N'admin'),
    (N'IT Systems Maintenance Notice', N'The student portal will be unavailable on 5 January 2026 from 02:00 to 05:00 for scheduled maintenance.', N'GENERAL', CAST('2025-10-05T00:00:00' AS DATETIME2), NULL, 1, N'admin')
) AS v(title, content, category, pubdate, expiry, published, username)
JOIN dbo.users u ON u.username = v.username;

COMMIT TRANSACTION;
PRINT N'>>> SEED 06 (news) completed.';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! SEED 06 (news) FAILED, rolled back: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO
