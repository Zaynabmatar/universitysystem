-- Partial grade publish: lets an instructor release Coursework/Midterm/Lab to a student before
-- Final is even entered, instead of the previous all-or-nothing "Submit and Lock".
--
-- Each component gets its own publish flag on dbo.grades, independent of is_submitted. A student
-- query reveals a mark once EITHER its own component flag is set OR the whole row is submitted
-- (is_submitted = 1 already implies every component is visible, and always did). The overall
-- Total/Letter/Points stay gated on is_submitted alone, unchanged -- they only ever exist once
-- every required component has been entered (GradeCalculator.totalMark already enforces that),
-- so "finalizes only when all required components exist" needs no new column.

IF COL_LENGTH('dbo.grades', 'coursework_published') IS NULL
BEGIN
    ALTER TABLE dbo.grades ADD coursework_published BIT NOT NULL
        CONSTRAINT DF_grades_coursework_published DEFAULT (0);
END
GO

IF COL_LENGTH('dbo.grades', 'midterm_published') IS NULL
BEGIN
    ALTER TABLE dbo.grades ADD midterm_published BIT NOT NULL
        CONSTRAINT DF_grades_midterm_published DEFAULT (0);
END
GO

IF COL_LENGTH('dbo.grades', 'lab_published') IS NULL
BEGIN
    ALTER TABLE dbo.grades ADD lab_published BIT NOT NULL
        CONSTRAINT DF_grades_lab_published DEFAULT (0);
END
GO

IF COL_LENGTH('dbo.grades', 'final_published') IS NULL
BEGIN
    ALTER TABLE dbo.grades ADD final_published BIT NOT NULL
        CONSTRAINT DF_grades_final_published DEFAULT (0);
END
GO
