/* ============================================================================
   SEED 05 -- Waitlist (60 new)
   ONLY INSERT statements below (Section 14 / user requirement).
============================================================================ */

USE universitymanagementDB;
GO
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

BEGIN TRY
BEGIN TRANSACTION;

INSERT INTO dbo.waitlist (section_id, student_id, position, status)
SELECT sec.section_id, st.student_id, v.pos, v.status
FROM (VALUES
    (N's.sleiman', N'MATH101', N'Fall 2025', N'01', 1, N'WAITING'),
    (N'l.feghali', N'MATH101', N'Fall 2025', N'01', 2, N'WAITING'),
    (N'l.azzi', N'MATH101', N'Fall 2025', N'01', 3, N'WAITING'),
    (N'o.bejjani2', N'MATH101', N'Fall 2025', N'01', 4, N'WAITING'),
    (N'e.chidiac', N'MATH101', N'Fall 2025', N'01', 5, N'WAITING'),
    (N'h.doumit', N'MATH101', N'Fall 2025', N'01', 6, N'WAITING'),
    (N'i.diab2', N'MATH101', N'Fall 2025', N'01', 7, N'WAITING'),
    (N'k.doumit', N'MATH101', N'Fall 2025', N'01', 8, N'WAITING'),
    (N'f.farah', N'MATH101', N'Fall 2025', N'01', 9, N'WAITING'),
    (N'a.tabet', N'MATH101', N'Fall 2025', N'01', 10, N'WAITING'),
    (N'r.zoghbi3', N'MATH101', N'Fall 2025', N'01', 11, N'WAITING'),
    (N'n.fares', N'MATH101', N'Fall 2025', N'01', 12, N'WAITING'),
    (N'g.choueiri', N'MATH101', N'Fall 2025', N'01', 13, N'WAITING'),
    (N'z.rizk', N'MATH101', N'Fall 2025', N'01', 14, N'WAITING'),
    (N'm.habib2', N'MATH101', N'Fall 2025', N'01', 15, N'WAITING'),
    (N'f.davis', N'MATH101', N'Fall 2025', N'01', 16, N'WAITING'),
    (N'j.doumit2', N'MATH101', N'Fall 2025', N'01', 17, N'WAITING'),
    (N'e.brown', N'MATH101', N'Fall 2025', N'01', 18, N'WAITING'),
    (N'j.farah', N'MATH101', N'Fall 2025', N'01', 19, N'WAITING'),
    (N'h.issa', N'MATH101', N'Fall 2025', N'01', 20, N'WAITING'),
    (N'j.mansour', N'MATH101', N'Fall 2025', N'01', 21, N'WAITING'),
    (N'r.saad', N'MATH101', N'Fall 2025', N'01', 22, N'WAITING'),
    (N'k.younes', N'MATH101', N'Fall 2025', N'01', 23, N'WAITING'),
    (N'e.salloum', N'MATH101', N'Fall 2025', N'01', 24, N'WAITING'),
    (N't.kassem', N'MATH101', N'Fall 2025', N'01', 25, N'WAITING'),
    (N'f.abourjeily', N'MATH101', N'Fall 2025', N'01', 26, N'WAITING'),
    (N'n.tabet', N'MATH101', N'Fall 2025', N'01', 27, N'WAITING'),
    (N'w.chalhoub', N'MATH101', N'Fall 2025', N'01', 28, N'WAITING'),
    (N's.jaber', N'MATH101', N'Fall 2025', N'01', 29, N'WAITING'),
    (N'm.chami', N'MATH101', N'Fall 2025', N'01', 30, N'WAITING'),
    (N'd.fahed', N'MATH101', N'Fall 2025', N'01', 31, N'WAITING'),
    (N'l.jureidini', N'MATH101', N'Fall 2025', N'01', 32, N'WAITING'),
    (N'f.baaklini', N'MATH101', N'Fall 2025', N'01', 33, N'WAITING'),
    (N'r.jaber', N'MATH101', N'Fall 2025', N'01', 34, N'WAITING'),
    (N'r.chalhoub2', N'MATH101', N'Fall 2025', N'01', 35, N'WAITING'),
    (N'h.chalhoub', N'MATH101', N'Fall 2025', N'01', 36, N'WAITING'),
    (N'y.naccache', N'MATH101', N'Fall 2025', N'01', 37, N'WAITING'),
    (N'w.khalife', N'MATH101', N'Fall 2025', N'01', 38, N'WAITING'),
    (N'd.rahme', N'MATH101', N'Fall 2025', N'01', 39, N'WAITING'),
    (N'f.smith2', N'MATH101', N'Fall 2025', N'01', 40, N'WAITING'),
    (N'r.wehbe', N'MATH101', N'Fall 2025', N'01', 41, N'WAITING'),
    (N'i.zoghbi', N'MATH101', N'Fall 2025', N'01', 42, N'WAITING'),
    (N'j.eid2', N'MATH101', N'Fall 2025', N'01', 43, N'WAITING'),
    (N'f.chehab', N'MATH101', N'Fall 2025', N'01', 44, N'WAITING'),
    (N'j.eid3', N'MATH101', N'Fall 2025', N'01', 45, N'WAITING'),
    (N'd.hamdan', N'MATH101', N'Fall 2025', N'01', 46, N'WAITING'),
    (N't.jureidini', N'MATH101', N'Fall 2025', N'01', 47, N'WAITING'),
    (N'j.barakat2', N'MATH101', N'Fall 2025', N'01', 48, N'WAITING'),
    (N'k.zoghbi', N'MATH101', N'Fall 2025', N'01', 49, N'WAITING'),
    (N'o.aoun', N'MATH101', N'Fall 2025', N'01', 50, N'WAITING'),
    (N'h.assaf', N'MATH101', N'Fall 2025', N'01', 51, N'WAITING'),
    (N'b.rahme', N'MATH101', N'Fall 2025', N'01', 52, N'WAITING'),
    (N'g.younes', N'MATH101', N'Fall 2025', N'01', 53, N'WAITING'),
    (N'e.chehab', N'MATH101', N'Fall 2025', N'01', 54, N'WAITING'),
    (N's.choueiri', N'MATH101', N'Fall 2025', N'01', 55, N'WAITING'),
    (N'r.lahoud', N'MATH101', N'Fall 2025', N'01', 56, N'WAITING'),
    (N'c.kanaan', N'MATH101', N'Fall 2025', N'01', 57, N'WAITING'),
    (N'p.habib', N'MATH101', N'Fall 2025', N'01', 58, N'WAITING'),
    (N'j.kassem', N'MATH101', N'Fall 2025', N'01', 59, N'WAITING'),
    (N'l.jaber', N'MATH101', N'Fall 2025', N'01', 60, N'WAITING')
) AS v(username, coursecode, semname, secnum, pos, status)
JOIN dbo.users u ON u.username = v.username
JOIN dbo.students st ON st.user_id = u.user_id
JOIN dbo.courses c ON c.course_code = v.coursecode
JOIN dbo.semesters sem ON sem.semester_name = v.semname
JOIN dbo.sections sec ON sec.course_id = c.course_id AND sec.semester_id = sem.semester_id AND sec.section_number = v.secnum;

COMMIT TRANSACTION;
PRINT N'>>> SEED 05 (waitlist) completed.';
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    PRINT N'!!! SEED 05 (waitlist) FAILED, rolled back: ' + ERROR_MESSAGE();
    THROW;
END CATCH
GO
