-- Admin-configurable grading weights and max marks per course (Coursework/Lab, Midterm, Final).
--
-- Percent weights, always summing to 100, plus a max mark per component, stored on dbo.courses
-- instead of living as the hardcoded constants GradeCalculator used to carry. coursework_weight/
-- coursework_max_mark apply to the Coursework mark on a course with no lab, or to the Lab mark on
-- a course with has_lab = 1 (0009_add_lab_grade_component.sql) -- one set serves both, matching
-- how GradeCalculator already treated the two marks as interchangeable. Existing courses default
-- to 20/30/50 weights and 100/100/100 max marks, the values GradeCalculator used to hardcode, so
-- behaviour is unchanged until an admin edits a course's grading config. Total = mark / max_mark *
-- weight, summed across the three components.

IF COL_LENGTH('dbo.courses', 'coursework_weight') IS NULL
BEGIN
    ALTER TABLE dbo.courses ADD coursework_weight DECIMAL(5,2) NOT NULL
        CONSTRAINT DF_courses_coursework_weight DEFAULT (20.00);
END
GO

IF COL_LENGTH('dbo.courses', 'midterm_weight') IS NULL
BEGIN
    ALTER TABLE dbo.courses ADD midterm_weight DECIMAL(5,2) NOT NULL
        CONSTRAINT DF_courses_midterm_weight DEFAULT (30.00);
END
GO

IF COL_LENGTH('dbo.courses', 'final_weight') IS NULL
BEGIN
    ALTER TABLE dbo.courses ADD final_weight DECIMAL(5,2) NOT NULL
        CONSTRAINT DF_courses_final_weight DEFAULT (50.00);
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_courses_weight_range')
BEGIN
    ALTER TABLE dbo.courses ADD CONSTRAINT CK_courses_weight_range
        CHECK (coursework_weight BETWEEN 0 AND 100 AND midterm_weight BETWEEN 0 AND 100
               AND final_weight BETWEEN 0 AND 100);
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_courses_weights_sum_100')
BEGIN
    ALTER TABLE dbo.courses ADD CONSTRAINT CK_courses_weights_sum_100
        CHECK (coursework_weight + midterm_weight + final_weight = 100.00);
END
GO

IF COL_LENGTH('dbo.courses', 'coursework_max_mark') IS NULL
BEGIN
    ALTER TABLE dbo.courses ADD coursework_max_mark DECIMAL(6,2) NOT NULL
        CONSTRAINT DF_courses_coursework_max_mark DEFAULT (100.00);
END
GO

IF COL_LENGTH('dbo.courses', 'midterm_max_mark') IS NULL
BEGIN
    ALTER TABLE dbo.courses ADD midterm_max_mark DECIMAL(6,2) NOT NULL
        CONSTRAINT DF_courses_midterm_max_mark DEFAULT (100.00);
END
GO

IF COL_LENGTH('dbo.courses', 'final_max_mark') IS NULL
BEGIN
    ALTER TABLE dbo.courses ADD final_max_mark DECIMAL(6,2) NOT NULL
        CONSTRAINT DF_courses_final_max_mark DEFAULT (100.00);
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_courses_max_marks_positive')
BEGIN
    ALTER TABLE dbo.courses ADD CONSTRAINT CK_courses_max_marks_positive
        CHECK (coursework_max_mark > 0 AND midterm_max_mark > 0 AND final_max_mark > 0);
END
GO
