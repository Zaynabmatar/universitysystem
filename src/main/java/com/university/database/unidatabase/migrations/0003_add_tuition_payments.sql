/* ============================================================================
   0003 — add dbo.tuition_rates and dbo.student_tuition_installments

   WHY
     The Payments page needs a real, per-student, per-semester USD+LBP tuition
     bill driven by the student's own registered courses. The existing
     dbo.student_invoices / dbo.payments pair (seed 08) is a single-currency,
     fee-type-based bill with no per-course breakdown and no notion of a
     second, independent LBP obligation, so it cannot represent "USD and LBP
     are two separate debts, each split into installments." Those tables are
     untouched — this adds the two tables the new screen actually needs.

   WHAT IT DOES
     dbo.tuition_rates      — one row per semester: USD/LBP price per credit,
                               how many installments a bill is split into, and
                               a flat late penalty per currency. The single
                               place the app reads a tuition rate from, instead
                               of a number hard-coded in a controller.
     dbo.student_tuition_installments
                             — the stable, per-student installment schedule
                               (bank reference, amount, due date, status) that
                               TuitionService generates once per student per
                               semester and then only ever reads back, so the
                               numbers shown never change on their own between
                               page loads.
     Also seeds a tuition_rates row for every semester that does not already
     have one, with reasonable test/demo values ($100 and 300,000 LBP per
     credit) — not official Islamic University of Lebanon tuition figures.

   SAFE TO RE-RUN
     Both CREATE TABLEs are guarded on sys.tables, and the rate seed is
     guarded with WHERE NOT EXISTS, so a second run touches nothing.
============================================================================ */

SET NOCOUNT ON;

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = N'tuition_rates' AND schema_id = SCHEMA_ID(N'dbo'))
BEGIN
    PRINT N'0003: creating dbo.tuition_rates.';

    CREATE TABLE dbo.tuition_rates
    (
        semester_id         INT            NOT NULL,
        usd_rate_per_credit DECIMAL(10,2)  NOT NULL,
        lbp_rate_per_credit DECIMAL(14,2)  NOT NULL,
        installment_count   INT            NOT NULL CONSTRAINT DF_tuition_rates_count DEFAULT (3),
        late_penalty_usd    DECIMAL(10,2)  NOT NULL CONSTRAINT DF_tuition_rates_pen_usd DEFAULT (0),
        late_penalty_lbp    DECIMAL(14,2)  NOT NULL CONSTRAINT DF_tuition_rates_pen_lbp DEFAULT (0),
        created_at          DATETIME2      NOT NULL CONSTRAINT DF_tuition_rates_created DEFAULT (SYSUTCDATETIME()),

        CONSTRAINT PK_tuition_rates PRIMARY KEY (semester_id),
        CONSTRAINT FK_tuition_rates_semester FOREIGN KEY (semester_id)
            REFERENCES dbo.semesters (semester_id) ON DELETE CASCADE,

        CONSTRAINT CK_tuition_rates_usd_positive CHECK (usd_rate_per_credit >= 0),
        CONSTRAINT CK_tuition_rates_lbp_positive CHECK (lbp_rate_per_credit >= 0),
        CONSTRAINT CK_tuition_rates_count_positive CHECK (installment_count > 0)
    );
END
ELSE
BEGIN
    PRINT N'0003: dbo.tuition_rates already exists - nothing to do.';
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = N'student_tuition_installments' AND schema_id = SCHEMA_ID(N'dbo'))
BEGIN
    PRINT N'0003: creating dbo.student_tuition_installments.';

    CREATE TABLE dbo.student_tuition_installments
    (
        installment_id   INT            IDENTITY(1,1) NOT NULL,
        student_id       INT            NOT NULL,
        semester_id      INT            NOT NULL,
        currency         NVARCHAR(3)    NOT NULL,
        installment_no   INT            NOT NULL,
        bank_reference   NVARCHAR(20)   NOT NULL,
        amount           DECIMAL(14,2)  NOT NULL,
        due_date         DATE           NOT NULL,
        payment_date     DATE           NULL,
        penalty          DECIMAL(14,2)  NOT NULL CONSTRAINT DF_sti_penalty DEFAULT (0),
        status           NVARCHAR(20)   NOT NULL CONSTRAINT DF_sti_status DEFAULT (N'UNPAID'),
        created_at       DATETIME2      NOT NULL CONSTRAINT DF_sti_created DEFAULT (SYSUTCDATETIME()),

        CONSTRAINT PK_student_tuition_installments PRIMARY KEY (installment_id),

        CONSTRAINT FK_sti_student FOREIGN KEY (student_id)
            REFERENCES dbo.students (student_id) ON DELETE CASCADE,
        CONSTRAINT FK_sti_semester FOREIGN KEY (semester_id)
            REFERENCES dbo.semesters (semester_id) ON DELETE NO ACTION,

        CONSTRAINT CK_sti_currency CHECK (currency IN (N'USD', N'LBP')),
        CONSTRAINT CK_sti_status CHECK (status IN (N'UNPAID', N'PARTIALLY_PAID', N'PAID', N'OVERDUE')),
        CONSTRAINT CK_sti_amount_positive CHECK (amount >= 0),

        CONSTRAINT UQ_sti_student_semester_currency_no
            UNIQUE (student_id, semester_id, currency, installment_no),
        CONSTRAINT UQ_sti_bank_reference UNIQUE (bank_reference)
    );

    CREATE NONCLUSTERED INDEX IX_sti_student_semester
        ON dbo.student_tuition_installments (student_id, semester_id);
END
ELSE
BEGIN
    PRINT N'0003: dbo.student_tuition_installments already exists - nothing to do.';
END
GO

-- Demo/test tuition rate for every semester that does not have one yet.
-- $100.00 and 300,000 LBP per credit are placeholder figures for
-- demonstrating the system, not official university tuition rates.
INSERT INTO dbo.tuition_rates (semester_id, usd_rate_per_credit, lbp_rate_per_credit, installment_count,
                                late_penalty_usd, late_penalty_lbp)
SELECT s.semester_id, 100.00, 300000.00, 3, 10.00, 150000.00
FROM dbo.semesters s
WHERE NOT EXISTS (
    SELECT 1 FROM dbo.tuition_rates r WHERE r.semester_id = s.semester_id
);
GO
