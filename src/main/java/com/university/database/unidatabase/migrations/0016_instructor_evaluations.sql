/*
    Instructor Evaluation

    - Adds an evaluation window to each semester.
    - Stores one evaluation per enrollment.
    - Stores the 15 answers separately, each from 1 to 5.
    - Student identity is not duplicated into the evaluation table.
*/

IF COL_LENGTH('dbo.semesters', 'evaluation_start') IS NULL
BEGIN
    ALTER TABLE dbo.semesters
        ADD evaluation_start DATETIME2 NULL;
END;

IF COL_LENGTH('dbo.semesters', 'evaluation_end') IS NULL
BEGIN
    ALTER TABLE dbo.semesters
        ADD evaluation_end DATETIME2 NULL;
END;

GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.check_constraints
    WHERE name = 'CK_semesters_evaluation_window'
)
BEGIN
    ALTER TABLE dbo.semesters
        ADD CONSTRAINT CK_semesters_evaluation_window
        CHECK (
            (evaluation_start IS NULL AND evaluation_end IS NULL)
            OR
            (evaluation_start IS NOT NULL
             AND evaluation_end IS NOT NULL
             AND evaluation_end > evaluation_start)
        );
END;

IF OBJECT_ID('dbo.evaluation_questions', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.evaluation_questions
    (
        question_id   INT            IDENTITY(1,1) NOT NULL,
        question_text NVARCHAR(300)  NOT NULL,
        display_order TINYINT        NOT NULL,
        is_active     BIT            NOT NULL
                      CONSTRAINT DF_evaluation_questions_active DEFAULT (1),

        CONSTRAINT PK_evaluation_questions
            PRIMARY KEY (question_id),

        CONSTRAINT UQ_evaluation_questions_order
            UNIQUE (display_order),

        CONSTRAINT CK_evaluation_questions_order
            CHECK (display_order BETWEEN 1 AND 15)
    );
END;

IF OBJECT_ID('dbo.instructor_evaluations', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.instructor_evaluations
    (
        evaluation_id INT       IDENTITY(1,1) NOT NULL,
        enrollment_id INT       NOT NULL,
        submitted_at  DATETIME2 NOT NULL
                      CONSTRAINT DF_instructor_evaluations_submitted
                      DEFAULT (SYSDATETIME()),

        CONSTRAINT PK_instructor_evaluations
            PRIMARY KEY (evaluation_id),

        CONSTRAINT UQ_instructor_evaluations_enrollment
            UNIQUE (enrollment_id),

        CONSTRAINT FK_instructor_evaluations_enrollment
            FOREIGN KEY (enrollment_id)
            REFERENCES dbo.enrollments (enrollment_id)
            ON DELETE NO ACTION
    );
END;

IF OBJECT_ID('dbo.evaluation_answers', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.evaluation_answers
    (
        evaluation_id INT     NOT NULL,
        question_id   INT     NOT NULL,
        rating        TINYINT NOT NULL,

        CONSTRAINT PK_evaluation_answers
            PRIMARY KEY (evaluation_id, question_id),

        CONSTRAINT FK_evaluation_answers_evaluation
            FOREIGN KEY (evaluation_id)
            REFERENCES dbo.instructor_evaluations (evaluation_id)
            ON DELETE CASCADE,

        CONSTRAINT FK_evaluation_answers_question
            FOREIGN KEY (question_id)
            REFERENCES dbo.evaluation_questions (question_id)
            ON DELETE NO ACTION,

        CONSTRAINT CK_evaluation_answers_rating
            CHECK (rating BETWEEN 1 AND 5)
    );
END;

IF NOT EXISTS (SELECT 1 FROM dbo.evaluation_questions)
BEGIN
    INSERT INTO dbo.evaluation_questions (question_text, display_order)
    VALUES
        (N'The instructor explains the course material clearly.', 1),
        (N'The instructor is well prepared for lectures.', 2),
        (N'The instructor treats students fairly and respectfully.', 3),
        (N'The instructor answers students'' questions clearly.', 4),
        (N'The instructor encourages student participation.', 5),
        (N'The instructor manages class time effectively.', 6),
        (N'The instructor provides helpful feedback on assignments and exams.', 7),
        (N'The instructor is available when students need academic support.', 8),
        (N'The instructor follows the course syllabus and schedule.', 9),
        (N'The instructor uses effective teaching methods.', 10),
        (N'The exams and assignments are related to the material taught.', 11),
        (N'The instructor communicates course requirements clearly.', 12),
        (N'The instructor creates a positive learning environment.', 13),
        (N'The instructor helps students understand difficult topics.', 14),
        (N'Overall, I am satisfied with this instructor.', 15);
END;

