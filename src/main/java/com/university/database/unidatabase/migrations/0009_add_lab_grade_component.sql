-- Instructor grading: adds a reusable Lab grade component.
--
-- has_lab on dbo.courses marks which courses carry a lab component (any course, never one
-- hardcoded course). lab_mark on dbo.grades stores that course's lab mark; it stays NULL for
-- every enrollment in a course whose has_lab is 0, so an instructor is never forced to enter it.
-- The weighting itself lives in Java (GradeCalculator), not in the database.

IF COL_LENGTH('dbo.courses', 'has_lab') IS NULL
BEGIN
    ALTER TABLE dbo.courses ADD has_lab BIT NOT NULL CONSTRAINT DF_courses_has_lab DEFAULT (0);
END
GO

IF COL_LENGTH('dbo.grades', 'lab_mark') IS NULL
BEGIN
    ALTER TABLE dbo.grades ADD lab_mark DECIMAL(5,2) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_grades_lab_mark')
BEGIN
    ALTER TABLE dbo.grades ADD CONSTRAINT CK_grades_lab_mark CHECK (lab_mark BETWEEN 0 AND 100);
END
GO
