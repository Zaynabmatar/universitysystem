-- Fixes Save Draft silently discarding an instructor's edit to an already-published component.
--
-- Before this migration, dbo.grades stored exactly one value per component (coursework_mark,
-- midterm_mark, lab_mark, final_mark), used BOTH as the instructor's working value AND as what a
-- student sees once that component's *_published flag is set. GradeDAO.updateDraftPreservingPublished
-- worked around the conflict by refusing to overwrite a published component's mark at all -- so
-- Save Draft looked like it worked, but reloading the sheet silently brought back the old
-- (published) value instead of the instructor's edit.
--
-- The fix: keep coursework_mark/midterm_mark/lab_mark/final_mark as the instructor's current
-- working value (Save Draft always writes it, no exceptions), and add one snapshot column per
-- component that freezes the value actually released to the student. The snapshot only moves when
-- GradeService.publishComponents/submitSection explicitly releases that component -- never on Save
-- Draft -- so a mid-edit value is never leaked early, and the instructor never loses an edit.
--
-- Backfill: for any component already published under the old scheme, the current mark IS the
-- value the student has already seen (that is exactly what the old preserve-on-draft logic
-- guaranteed), so the snapshot starts out equal to it.

IF COL_LENGTH('dbo.grades', 'coursework_published_mark') IS NULL
BEGIN
    ALTER TABLE dbo.grades ADD coursework_published_mark DECIMAL(6,2) NULL;
END
GO

IF COL_LENGTH('dbo.grades', 'midterm_published_mark') IS NULL
BEGIN
    ALTER TABLE dbo.grades ADD midterm_published_mark DECIMAL(6,2) NULL;
END
GO

IF COL_LENGTH('dbo.grades', 'lab_published_mark') IS NULL
BEGIN
    ALTER TABLE dbo.grades ADD lab_published_mark DECIMAL(6,2) NULL;
END
GO

IF COL_LENGTH('dbo.grades', 'final_published_mark') IS NULL
BEGIN
    ALTER TABLE dbo.grades ADD final_published_mark DECIMAL(6,2) NULL;
END
GO

UPDATE dbo.grades SET coursework_published_mark = coursework_mark
    WHERE coursework_published = 1 AND coursework_published_mark IS NULL;
GO
UPDATE dbo.grades SET midterm_published_mark = midterm_mark
    WHERE midterm_published = 1 AND midterm_published_mark IS NULL;
GO
UPDATE dbo.grades SET lab_published_mark = lab_mark
    WHERE lab_published = 1 AND lab_published_mark IS NULL;
GO
UPDATE dbo.grades SET final_published_mark = final_mark
    WHERE final_published = 1 AND final_published_mark IS NULL;
GO

-- A submitted row has always shown its raw mark columns directly (submission implies full
-- visibility -- see GradeDAO.findStudentGradeRows), so backfill the snapshot there too, for the
-- same "already-published, already-warned" comparisons GradeService now makes when a submitted
-- section is later Admin-Unlocked and edited again.
UPDATE dbo.grades SET
    coursework_published_mark = ISNULL(coursework_published_mark, coursework_mark),
    midterm_published_mark    = ISNULL(midterm_published_mark, midterm_mark),
    lab_published_mark        = ISNULL(lab_published_mark, lab_mark),
    final_published_mark      = ISNULL(final_published_mark, final_mark)
WHERE is_submitted = 1;
GO
