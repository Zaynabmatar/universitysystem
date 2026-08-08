/* ============================================================================
   0007 — backfill paid tuition history for already-concluded semesters

   WHY
     TuitionService.hasUnpaidPreviousBalance (RegistrationService's financial
     hold, requirement 4) generates a student's installment schedule the first
     time anyone asks for it, always UNPAID, and nothing in the application
     ever marks an installment PAID — the Payments page is read-only by
     design. Left alone, that means no continuing student could ever clear a
     financial hold: the moment their earlier semesters are billed for the
     first time, they are unpaid forever, and every later registration is
     blocked. On this database 601 distinct students enrolled in the current
     semester (Fall 2026) already have real enrollment history in an earlier
     semester, so this was not a corner case.

     A semester that has already concluded is, in reality, one a continuing
     student has already settled — otherwise they could not have gone on
     registering in every semester since. This migration makes that history
     explicit: for every (student, already-concluded semester) pair with real,
     non-dropped enrollments and no installment rows yet, it generates the
     same schedule TuitionService would (same rate table, same per-currency
     split, same bank-reference format) and records it as PAID, on its own
     due date, with no penalty.

     "Already concluded" means started before the current semester
     (semesters.is_current = 1), the same boundary TuitionService itself uses
     to decide what counts as "previous". Due dates are anchored to each
     semester's own start date rather than to today, since a historical bill
     due five years after its semester ended would not be believable.

     Any (student, semester) pair that already has installment rows is left
     completely untouched — both the current semester's own in-progress bills
     (student_id 63/333/581, semester 19) and the two students who had
     already generated an unpaid Fall 2025 bill before this migration existed
     (student_id 45/54, semester 2) keep exactly what they had. Those two
     remain the database's real "student with an unpaid previous semester"
     case for testing the financial hold.

   SAFE TO RE-RUN
     Every inserted (student, semester) pair is chosen by NOT EXISTS against
     student_tuition_installments. Once a pair has rows — from this migration
     or from the application — a second run finds it already covered and
     inserts nothing further for it.
============================================================================ */

SET NOCOUNT ON;

DECLARE @currentStart DATE = (SELECT start_date FROM dbo.semesters WHERE is_current = 1);

IF @currentStart IS NULL
BEGIN
    PRINT N'0007: no current semester is set - nothing to do.';
END
ELSE
BEGIN
    ;WITH StudentSemesterCredits AS (
        SELECT e.student_id, sec.semester_id, SUM(c.credits) AS total_credits
          FROM dbo.enrollments e
          JOIN dbo.sections sec  ON sec.section_id = e.section_id
          JOIN dbo.courses c     ON c.course_id = sec.course_id
          JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id
         WHERE sem.start_date < @currentStart
           AND e.status <> 'DROPPED'
         GROUP BY e.student_id, sec.semester_id
        HAVING SUM(c.credits) > 0
    ),
    Eligible AS (
        SELECT ssc.student_id, ssc.semester_id, ssc.total_credits,
               tr.usd_rate_per_credit, tr.lbp_rate_per_credit, tr.installment_count,
               sem.start_date AS sem_start
          FROM StudentSemesterCredits ssc
          JOIN dbo.tuition_rates tr  ON tr.semester_id = ssc.semester_id
          JOIN dbo.semesters sem    ON sem.semester_id = ssc.semester_id
         WHERE NOT EXISTS (
             SELECT 1 FROM dbo.student_tuition_installments sti
              WHERE sti.student_id = ssc.student_id AND sti.semester_id = ssc.semester_id)
    ),
    Numbers AS (
        SELECT TOP (20) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n
          FROM sys.all_objects
    ),
    Billed AS (
        SELECT el.student_id, el.semester_id, el.installment_count, n.n AS installment_no,
               CAST(el.total_credits * el.usd_rate_per_credit AS DECIMAL(14,2)) AS total_usd,
               CAST(el.total_credits * el.lbp_rate_per_credit AS DECIMAL(14,2)) AS total_lbp,
               ROUND(CAST(el.total_credits * el.usd_rate_per_credit AS DECIMAL(14,2))
                     / el.installment_count, 2, 1) AS share_usd,
               ROUND(CAST(el.total_credits * el.lbp_rate_per_credit AS DECIMAL(14,2))
                     / el.installment_count, 0, 1) AS share_lbp,
               DATEADD(MONTH, n.n - 1,
                   DATEFROMPARTS(YEAR(DATEADD(MONTH, 1, el.sem_start)),
                                 MONTH(DATEADD(MONTH, 1, el.sem_start)), 10)) AS due_date
          FROM Eligible el
          JOIN Numbers n ON n.n <= el.installment_count
    ),
    Rows AS (
        SELECT student_id, semester_id, installment_no, due_date, 'USD' AS currency,
               CASE WHEN installment_no = installment_count
                    THEN total_usd - share_usd * (installment_count - 1)
                    ELSE share_usd END AS amount,
               'REF-' + CAST(semester_id AS VARCHAR(10)) + '-'
                   + RIGHT('00' + CAST(student_id AS VARCHAR(10)),
                           CASE WHEN LEN(CAST(student_id AS VARCHAR(10))) > 3
                                THEN LEN(CAST(student_id AS VARCHAR(10))) ELSE 3 END)
                   + '-' + RIGHT('0' + CAST(installment_no AS VARCHAR(10)),
                           CASE WHEN LEN(CAST(installment_no AS VARCHAR(10))) > 2
                                THEN LEN(CAST(installment_no AS VARCHAR(10))) ELSE 2 END) AS bank_reference
          FROM Billed
        UNION ALL
        SELECT student_id, semester_id, installment_no, due_date, 'LBP' AS currency,
               CASE WHEN installment_no = installment_count
                    THEN total_lbp - share_lbp * (installment_count - 1)
                    ELSE share_lbp END AS amount,
               'REF-' + CAST(semester_id AS VARCHAR(10)) + '-L'
                   + RIGHT('00' + CAST(student_id AS VARCHAR(10)),
                           CASE WHEN LEN(CAST(student_id AS VARCHAR(10))) > 3
                                THEN LEN(CAST(student_id AS VARCHAR(10))) ELSE 3 END)
                   + '-' + RIGHT('0' + CAST(installment_no AS VARCHAR(10)),
                           CASE WHEN LEN(CAST(installment_no AS VARCHAR(10))) > 2
                                THEN LEN(CAST(installment_no AS VARCHAR(10))) ELSE 2 END) AS bank_reference
          FROM Billed
    )
    INSERT INTO dbo.student_tuition_installments
        (student_id, semester_id, currency, installment_no, bank_reference, amount,
         due_date, payment_date, penalty, status)
    SELECT student_id, semester_id, currency, installment_no, bank_reference, amount,
           due_date, due_date, 0, 'PAID'
      FROM Rows;

    PRINT N'0007: historical installment rows inserted = ' + CAST(@@ROWCOUNT AS NVARCHAR(10));
END
