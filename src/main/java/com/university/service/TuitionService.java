package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.CourseDAO;
import com.university.dao.EnrollmentDAO;
import com.university.dao.SectionDAO;
import com.university.dao.SemesterDAO;
import com.university.dao.StudentDAO;
import com.university.dao.TuitionInstallmentDAO;
import com.university.dao.TuitionRateDAO;
import com.university.enums.Currency;
import com.university.enums.EnrollmentStatus;
import com.university.enums.InvoiceStatus;
import com.university.enums.NotificationType;
import com.university.model.Course;
import com.university.model.Enrollment;
import com.university.model.Section;
import com.university.model.Semester;
import com.university.model.TuitionInstallment;
import com.university.model.TuitionRate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The logged-in student's own semester bill: what each registered course
 * costs in USD and in LBP, and the stable installment schedule for each.
 *
 * <p>USD and LBP are two independent obligations, never a currency
 * conversion of one another — every total is a straight sum of that
 * currency's own course charges (credits &times; {@link TuitionRate}), and the
 * two installment schedules are generated, stored and read back
 * separately.</p>
 *
 * <p>Installments are generated once per student per semester, the first
 * time this class is asked for them, and normally never rewritten after
 * that — see {@link #billFor}. That is what keeps a bank reference, a due
 * date and a status stable across page loads. The one exception is a
 * schedule nothing has been paid against yet whose total has gone stale
 * (typically a dropped course) — {@link #scheduleIsStale} — which is
 * regenerated from the student's current charges so a dropped course is
 * never still billed or payable.</p>
 */
public class TuitionService {

    private static final BigDecimal FALLBACK_USD_RATE = new BigDecimal("100.00");
    private static final BigDecimal FALLBACK_LBP_RATE = new BigDecimal("300000");
    private static final int FALLBACK_INSTALLMENTS = 3;

    /**
     * Installment #1 and #2 of this one semester are pinned to these exact dates -- a fixed,
     * one-off exception that must hold even if this semester's own start/end dates are edited
     * afterward, so any newly-billed student is generated with the same dates the rest of the
     * semester already has. Not a general rule for every semester; see
     * {@link TuitionInstallmentDAO#rescheduleUnpaidForSemester}.
     */
    private static final int FIXED_DUE_DATE_SEMESTER_ID = 19;
    private static final LocalDate FIXED_INSTALLMENT_1_DUE = LocalDate.of(2026, 8, 8);
    private static final LocalDate FIXED_INSTALLMENT_2_DUE = LocalDate.of(2026, 8, 15);

    private final TuitionRateDAO rateDao = new TuitionRateDAO();
    private final TuitionInstallmentDAO installmentDao = new TuitionInstallmentDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final SectionDAO sectionDao = new SectionDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final SemesterDAO semesterDao = new SemesterDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final NotificationService notificationService = new NotificationService();

    private final AbstractDAO transactions = new AbstractDAO() {
    };

    /** Everything the Payments page shows, for one student in one semester. */
    public static final class Bill {
        public final Semester semester;
        public final List<CourseCharge> courseCharges;
        public final BigDecimal totalUsd;
        public final BigDecimal totalLbp;
        /** What is still owed: unpaid/overdue installment principal plus any unpaid penalty. Zero once every installment is Paid. */
        public final BigDecimal remainingUsd;
        public final BigDecimal remainingLbp;
        public final List<TuitionInstallment> usdInstallments;
        public final List<TuitionInstallment> lbpInstallments;

        Bill(Semester semester, List<CourseCharge> courseCharges, BigDecimal totalUsd,
             BigDecimal totalLbp, BigDecimal remainingUsd, BigDecimal remainingLbp,
             List<TuitionInstallment> usdInstallments, List<TuitionInstallment> lbpInstallments) {
            this.semester = semester;
            this.courseCharges = courseCharges;
            this.totalUsd = totalUsd;
            this.totalLbp = totalLbp;
            this.remainingUsd = remainingUsd;
            this.remainingLbp = remainingLbp;
            this.usdInstallments = usdInstallments;
            this.lbpInstallments = lbpInstallments;
        }

        public int totalCredits() {
            return courseCharges.stream().mapToInt(c -> c.credits).sum();
        }
    }

    /** One registered course's charge in both currencies, and what of it is still due. */
    public static final class CourseCharge {
        public final String courseCode;
        public final String courseTitle;
        public final int credits;
        public final BigDecimal amountUsd;
        public final BigDecimal amountLbp;
        public final BigDecimal dueUsd;
        public final BigDecimal dueLbp;

        CourseCharge(String courseCode, String courseTitle, int credits, BigDecimal amountUsd,
                     BigDecimal amountLbp, BigDecimal dueUsd, BigDecimal dueLbp) {
            this.courseCode = courseCode;
            this.courseTitle = courseTitle;
            this.credits = credits;
            this.amountUsd = amountUsd;
            this.amountLbp = amountLbp;
            this.dueUsd = dueUsd;
            this.dueLbp = dueLbp;
        }
    }

    /**
     * Builds the logged-in student's bill for one semester: their real
     * registered courses, priced from {@link TuitionRate}, plus their stable
     * installment schedule (generated on first use).
     */
    public Bill billFor(int studentId, Semester semester) {
        ValidationException.requireId(studentId, "Student");
        if (semester == null) {
            throw new ServiceException("No semester was given.");
        }

        TuitionRate rate = rateDao.findBySemester(semester.getSemesterId()).orElseGet(() -> fallbackRate(semester));

        // Notify while the penalty is still about to happen, then apply it -- reversing the order
        // matters: read the affected rows before refreshDelinquency touches them, or the penalty
        // is already on the row by the time anyone looks and the notice can only ever say
        // "has been added" after the fact.
        notifyPendingPenalty(installmentDao.findPendingPenalty(semester.getSemesterId()));
        installmentDao.refreshDelinquency(semester.getSemesterId());

        record RawCharge(String courseCode, String courseTitle, int credits, BigDecimal usd, BigDecimal lbp) {
        }
        List<RawCharge> rawCharges = new ArrayList<>();
        BigDecimal totalUsd = BigDecimal.ZERO;
        BigDecimal totalLbp = BigDecimal.ZERO;

        List<Enrollment> enrollments = enrollmentDao.findByStudentAndSemester(studentId, semester.getSemesterId());
        for (Enrollment enrollment : enrollments) {
            if (enrollment.getStatus() == EnrollmentStatus.DROPPED) {
                continue;
            }
            Section section = sectionDao.findById(enrollment.getSectionId()).orElse(null);
            if (section == null) {
                continue;
            }
            Course course = courseDao.findById(section.getCourseId()).orElse(null);
            if (course == null) {
                continue;
            }

            BigDecimal credits = BigDecimal.valueOf(course.getCredits());
            BigDecimal usd = rate.getUsdRatePerCredit().multiply(credits).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lbp = rate.getLbpRatePerCredit().multiply(credits).setScale(0, RoundingMode.HALF_UP);

            rawCharges.add(new RawCharge(course.getCourseCode(), course.getCourseTitle(),
                    course.getCredits(), usd, lbp));
            totalUsd = totalUsd.add(usd);
            totalLbp = totalLbp.add(lbp);
        }

        List<TuitionInstallment> stored = installmentDao.findByStudentAndSemester(studentId, semester.getSemesterId());
        if (stored.isEmpty() && !rawCharges.isEmpty()) {
            stored = generateInstallments(studentId, semester, rate, totalUsd, totalLbp);
        } else if (!stored.isEmpty() && scheduleTotalsDiffer(stored, totalUsd, totalLbp)) {
            boolean anyPaid = stored.stream()
                    .anyMatch(i -> i.getPaymentDate() != null || i.getStatus() == InvoiceStatus.PAID);

            if (!anyPaid) {
                installmentDao.deleteByStudentAndSemester(studentId, semester.getSemesterId());
                stored = rawCharges.isEmpty()
                        ? List.of()
                        : generateInstallments(studentId, semester, rate, totalUsd, totalLbp);
            } else {
                rebalanceUnpaidInstallments(stored, totalUsd, totalLbp);
                stored = installmentDao.findByStudentAndSemester(studentId, semester.getSemesterId());
            }
        }

        sendPaymentNotifications(studentId, stored);

        List<TuitionInstallment> usdInstallments = stored.stream()
                .filter(i -> i.getCurrency() == Currency.USD).toList();
        List<TuitionInstallment> lbpInstallments = stored.stream()
                .filter(i -> i.getCurrency() == Currency.LBP).toList();

        BigDecimal remainingPrincipalUsd = remainingPrincipal(usdInstallments);
        BigDecimal remainingPrincipalLbp = remainingPrincipal(lbpInstallments);
        BigDecimal remainingUsd = remainingPrincipalUsd.add(remainingPenalty(usdInstallments));
        BigDecimal remainingLbp = remainingPrincipalLbp.add(remainingPenalty(lbpInstallments));

        BigDecimal remainingFractionUsd = remainingFraction(remainingPrincipalUsd, totalUsd);
        BigDecimal remainingFractionLbp = remainingFraction(remainingPrincipalLbp, totalLbp);

        List<CourseCharge> charges = new ArrayList<>();
        for (RawCharge raw : rawCharges) {
            BigDecimal dueUsd = raw.usd().multiply(remainingFractionUsd).setScale(2, RoundingMode.HALF_UP);
            BigDecimal dueLbp = raw.lbp().multiply(remainingFractionLbp).setScale(0, RoundingMode.HALF_UP);
            charges.add(new CourseCharge(raw.courseCode(), raw.courseTitle(), raw.credits(),
                    raw.usd(), raw.lbp(), dueUsd, dueLbp));
        }

        return new Bill(semester, charges, totalUsd, totalLbp, remainingUsd, remainingLbp,
                usdInstallments, lbpInstallments);
    }

    /**
     * True when the stored schedule's own totals no longer match what the student's current
     * (non-dropped) enrollments actually cost, AND nothing on it has been paid — the only case
     * where it is safe to throw the schedule away and regenerate it. A stray rounding difference
     * never trips this: the same {@code divide(..., scale, HALF_UP)} split
     * {@link #buildAndInsert} used to generate the stored rows is exact to the currency's own
     * scale, so a genuine drop/add changes the total by whole currency units, not by a fraction of
     * one.
     */
    private boolean scheduleTotalsDiffer(List<TuitionInstallment> stored, BigDecimal totalUsd, BigDecimal totalLbp) {
        BigDecimal storedUsd = sumAmount(stored, Currency.USD);
        BigDecimal storedLbp = sumAmount(stored, Currency.LBP);
        return storedUsd.compareTo(totalUsd) != 0 || storedLbp.compareTo(totalLbp) != 0;
    }

    private void rebalanceUnpaidInstallments(List<TuitionInstallment> stored,
                                             BigDecimal totalUsd, BigDecimal totalLbp) {
        rebalanceCurrency(stored, Currency.USD, totalUsd, 2);
        rebalanceCurrency(stored, Currency.LBP, totalLbp, 0);
    }

    private void rebalanceCurrency(List<TuitionInstallment> stored, Currency currency,
                                   BigDecimal newTotal, int scale) {
        List<TuitionInstallment> rows = stored.stream()
                .filter(i -> i.getCurrency() == currency)
                .toList();

        BigDecimal paid = rows.stream()
                .filter(i -> i.getPaymentDate() != null || i.getStatus() == InvoiceStatus.PAID)
                .map(TuitionInstallment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<TuitionInstallment> unpaid = rows.stream()
                .filter(i -> i.getPaymentDate() == null && i.getStatus() != InvoiceStatus.PAID)
                .toList();

        if (unpaid.isEmpty()) {
            return;
        }

        BigDecimal remaining = newTotal.subtract(paid).max(BigDecimal.ZERO);
        BigDecimal share = remaining.divide(
                BigDecimal.valueOf(unpaid.size()), scale, RoundingMode.DOWN);

        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < unpaid.size(); i++) {
            BigDecimal amount = (i == unpaid.size() - 1)
                    ? remaining.subtract(assigned)
                    : share;

            installmentDao.updateUnpaidAmount(unpaid.get(i).getInstallmentId(), amount);
            assigned = assigned.add(amount);
        }
    }

    private static BigDecimal sumAmount(List<TuitionInstallment> installments, Currency currency) {
        return installments.stream()
                .filter(i -> i.getCurrency() == currency)
                .map(TuitionInstallment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal remainingPrincipal(List<TuitionInstallment> installments) {
        return installments.stream()
                .filter(i -> i.getStatus() != InvoiceStatus.PAID)
                .map(TuitionInstallment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal remainingPenalty(List<TuitionInstallment> installments) {
        return installments.stream()
                .filter(i -> i.getStatus() != InvoiceStatus.PAID)
                .map(TuitionInstallment::getPenalty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The still-owed share of a total, as a fraction between 0 (fully paid) and 1 (nothing paid). */
    private static BigDecimal remainingFraction(BigDecimal remainingPrincipal, BigDecimal total) {
        if (total == null || total.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal fraction = remainingPrincipal.divide(total, 10, RoundingMode.HALF_UP);
        if (fraction.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        if (fraction.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return fraction;
    }

    /**
     * The registration financial hold: true when the student has any required balance still
     * owed ({@link com.university.enums.InvoiceStatus#hasBalance()}) from a semester that
     * started before {@code targetSemester}.
     *
     * <p>Walks every semester the student has actually studied in and asks {@link #billFor} for
     * each one, the same call the Payments page itself makes — that both prices the semester from
     * its real enrollments and rates, and lazily generates its installment schedule the first time
     * anyone (this check included) asks, so a previous balance is caught even if the student never
     * opened the Payments page for that semester.</p>
     */

    /**
     * Warns each installment's student that a late penalty is about to be added, before {@link
     * TuitionInstallmentDAO#refreshDelinquency} actually adds it. Callers must read {@code
     * pending} via {@link TuitionInstallmentDAO#findPendingPenalty} <em>before</em> calling
     * {@code refreshDelinquency} in the same pass, or there is nothing left to warn about.
     */
    private void notifyPendingPenalty(List<TuitionInstallment> pending) {
        String title = "Payment Overdue";

        for (TuitionInstallment installment : pending) {
            var student = studentDao.findById(installment.getStudentId()).orElse(null);
            if (student == null) {
                continue;
            }
            int userId = student.getUserId();
            int installmentId = installment.getInstallmentId();

            if (notificationService.alreadyNotified(userId, "TUITION_INSTALLMENT", installmentId, title)) {
                continue;
            }

            String penaltyText = installment.getCurrency() == Currency.USD
                    ? "$10"
                    : "900,000 LBP";

            notificationService.notify(
                    userId,
                    NotificationType.WARNING,
                    title,
                    "Your payment deadline has passed. A late penalty of "
                            + penaltyText
                            + " is about to be added to your installment. "
                            + "Please settle the outstanding balance as soon as possible.",
                    "TUITION_INSTALLMENT",
                    installmentId
            );
        }
    }

    private void sendPaymentNotifications(int studentId, List<TuitionInstallment> installments) {
        var student = studentDao.findById(studentId).orElse(null);
        if (student == null) {
            return;
        }

        int userId = student.getUserId();
        LocalDate today = LocalDate.now();

        for (TuitionInstallment installment : installments) {

            if (installment.getPaymentDate() != null
                    || installment.getStatus() == InvoiceStatus.PAID
                    || installment.getDueDate() == null) {
                continue;
            }

            int installmentId = installment.getInstallmentId();
            LocalDate dueDate = installment.getDueDate();

            // Deadline already passed and penalty was added.
            if (installment.getStatus() == InvoiceStatus.OVERDUE) {
String title = "Payment Overdue";

                if (!notificationService.alreadyNotified(
                        userId, "TUITION_INSTALLMENT", installmentId, title)) {

                    String penaltyText = installment.getCurrency() == Currency.USD
                            ? "$10"
                            : "900,000 LBP";

                    notificationService.notify(
                            userId,
                            NotificationType.WARNING,
                            title,
                            "Your payment deadline has passed. A late penalty of "
                                    + penaltyText
                                    + " has been added to your installment. "
                                    + "Please settle the outstanding balance.",
                            "TUITION_INSTALLMENT",
                            installmentId
                    );
                }

                continue;
            }

            // Reminder starting 4 days before the deadline.
            LocalDate reminderStart = dueDate.minusDays(4);

            if (!today.isBefore(reminderStart) && !today.isAfter(dueDate)) {
                String title = "Payment Reminder";

                if (!notificationService.alreadyNotified(
                        userId, "TUITION_INSTALLMENT", installmentId, title)) {

                    String penaltyText = installment.getCurrency() == Currency.USD
                            ? "$10"
                            : "900,000 LBP";

                    notificationService.notify(
                            userId,
                            NotificationType.PAYMENT,
                            title,
                            "Your " + installment.getCurrency()
                                    + " installment is due on " + dueDate
                                    + ". Please pay before the deadline. "
                                    + "If payment is late, a penalty of "
                                    + penaltyText + " will be added.",
                            "TUITION_INSTALLMENT",
                            installmentId
                    );
                }
            }
        }
    }
    /** Refreshes payment reminders/overdue notices for all semesters studied by this student. */
    /** Refreshes payment notifications for every stored installment in the system. */
    public void refreshPaymentNotificationsForAllStudents() {
        LocalDate today = LocalDate.now();

        // Same ordering as billFor(): read who is about to be charged a penalty and notify them
        // first, before refreshDelinquency() actually adds it. Bringing every semester's status up
        // to date is still done here regardless -- billFor() only flips a semester's installments
        // to OVERDUE as a side effect of one student's own bill being computed, so without this a
        // semester nobody happened to log into yet would still read UNPAID here even past its due
        // date, and every student in it would silently get skipped below.
        notifyPendingPenalty(installmentDao.findPendingPenalty());
        installmentDao.refreshDelinquency();

        for (TuitionInstallment installment : installmentDao.findAll()) {

            if (installment.getDueDate() == null) {
                continue;
            }

            // Do not notify already-paid installments.
            if (installment.getPaymentDate() != null
                    || installment.getStatus() == InvoiceStatus.PAID) {
                continue;
            }

            var student = studentDao.findById(installment.getStudentId()).orElse(null);
            if (student == null) {
                continue;
            }

            int userId = student.getUserId();
            int installmentId = installment.getInstallmentId();
            LocalDate dueDate = installment.getDueDate();

            // Reminder: starting 4 days before the due date.
            LocalDate reminderStart = dueDate.minusDays(4);

            if (!today.isBefore(reminderStart)
                    && !today.isAfter(dueDate)
                    && installment.getStatus() != InvoiceStatus.OVERDUE) {

                String title = "Payment Reminder";

                if (!notificationService.alreadyNotified(
                        userId, "TUITION_INSTALLMENT", installmentId, title)) {

                    String penaltyText = installment.getCurrency() == Currency.USD
                            ? "$10"
                            : "900,000 LBP";

                    notificationService.notify(
                            userId,
                            NotificationType.PAYMENT,
                            title,
                            "Your " + installment.getCurrency()
                                    + " installment is due on " + dueDate
                                    + ". Please pay before the deadline. "
                                    + "If payment is late, a penalty of "
                                    + penaltyText + " will be added.",
                            "TUITION_INSTALLMENT",
                            installmentId
                    );
                }
            }

            // Overdue: only when the installment is really overdue and has a penalty.
            if (installment.getStatus() == InvoiceStatus.OVERDUE
                    && installment.getPenalty() != null
                    && installment.getPenalty().compareTo(BigDecimal.ZERO) > 0) {

                String title = "Payment Overdue";

                if (!notificationService.alreadyNotified(
                        userId, "TUITION_INSTALLMENT", installmentId, title)) {

                    String penaltyText = installment.getCurrency() == Currency.USD
                            ? "$10"
                            : "900,000 LBP";

                    notificationService.notify(
                            userId,
                            NotificationType.WARNING,
                            title,
                            "Your payment deadline has passed. A late penalty of "
                                    + penaltyText
                                    + " has been added. Please settle the outstanding balance.",
                            "TUITION_INSTALLMENT",
                            installmentId
                    );
                }
            }
        }
    }
    public void refreshPaymentNotificationsForStudent(int studentId) {
        ValidationException.requireId(studentId, "Student");

        for (Semester semester : semesterDao.findWithEnrollments(studentId)) {
            billFor(studentId, semester);
        }
    }
    public boolean hasUnpaidPreviousBalance(int studentId, Semester targetSemester) {
        ValidationException.requireId(studentId, "Student");
        if (targetSemester == null) {
            return false;
        }
        List<Semester> previousSemesters = semesterDao.findWithEnrollments(studentId).stream()
                .filter(s -> s.getStartDate().isBefore(targetSemester.getStartDate()))
                .toList();

        for (Semester semester : previousSemesters) {
            Bill bill = billFor(studentId, semester);
            boolean unpaid = bill.usdInstallments.stream().anyMatch(i -> i.getStatus().hasBalance())
                    || bill.lbpInstallments.stream().anyMatch(i -> i.getStatus().hasBalance());
            if (unpaid) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits {@code totalUsd} and {@code totalLbp} into {@code rate.getInstallmentCount()}
     * stable installments each and persists them.
     *
     * <p>The last installment of each currency absorbs whatever the equal
     * split does not divide evenly, so the installments always add up to
     * exactly the total — never more, never less.</p>
     */
    private List<TuitionInstallment> generateInstallments(int studentId, Semester semester, TuitionRate rate,
                                                           BigDecimal totalUsd, BigDecimal totalLbp) {
        int count = rate.getInstallmentCount() > 0 ? rate.getInstallmentCount() : FALLBACK_INSTALLMENTS;
        LocalDate firstDue = LocalDate.now().plusMonths(1).withDayOfMonth(10);

        Connection connection = transactions.beginTransaction();
        try {
            List<TuitionInstallment> created = new ArrayList<>();
            created.addAll(buildAndInsert(connection, studentId, semester, Currency.USD, count,
                    totalUsd, firstDue));
            created.addAll(buildAndInsert(connection, studentId, semester, Currency.LBP, count,
                    totalLbp, firstDue));
            connection.commit();
            return created;
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new ServiceException("The installment schedule could not be created.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    private List<TuitionInstallment> buildAndInsert(Connection connection, int studentId, Semester semester,
                                                     Currency currency, int count, BigDecimal total,
                                                     LocalDate firstDue) {
        int scale = currency == Currency.USD ? 2 : 0;
        BigDecimal share = total.divide(BigDecimal.valueOf(count), scale, RoundingMode.DOWN);

        List<TuitionInstallment> rows = new ArrayList<>();
        BigDecimal runningTotal = BigDecimal.ZERO;
        for (int no = 1; no <= count; no++) {
            boolean last = no == count;
            BigDecimal amount = last ? total.subtract(runningTotal) : share;
            runningTotal = runningTotal.add(amount);

            TuitionInstallment installment = new TuitionInstallment();
            installment.setStudentId(studentId);
            installment.setSemesterId(semester.getSemesterId());
            installment.setCurrency(currency);
            installment.setInstallmentNo(no);
            installment.setBankReference(bankReference(currency, semester, studentId, no));
            installment.setAmount(amount);
            installment.setDueDate(dueDateFor(semester, no, firstDue));
            installment.setPaymentDate(null);
            installment.setPenalty(BigDecimal.ZERO);
            installment.setStatus(InvoiceStatus.UNPAID);

            int id = installmentDao.insert(connection, installment);
            installment.setInstallmentId(id);
            rows.add(installment);
        }
        return rows;
    }

    /** {@link #FIXED_INSTALLMENT_1_DUE} / {@link #FIXED_INSTALLMENT_2_DUE} override the normal formula, only for {@link #FIXED_DUE_DATE_SEMESTER_ID}. */
    private static LocalDate dueDateFor(Semester semester, int installmentNo, LocalDate firstDue) {
        if (semester.getSemesterId() == FIXED_DUE_DATE_SEMESTER_ID) {
            if (installmentNo == 1) {
                return FIXED_INSTALLMENT_1_DUE;
            }
            if (installmentNo == 2) {
                return FIXED_INSTALLMENT_2_DUE;
            }
        }
        return firstDue.plusMonths(installmentNo - 1);
    }

    /**
     * A deterministic, globally-unique reference: a pure function of currency,
     * semester, student and installment number, so it needs no shared counter
     * and can never collide between two students — or two semesters of the
     * same student — generating a bill at once.
     *
     * <p>Keyed on {@code semester.getSemesterId()} rather than the calendar
     * year the bill happens to be generated in: with the semester selector,
     * the same student's Fall 2025 and Spring 2025 bills (or any two
     * semesters first billed in the same real-world year) would otherwise
     * compute the identical reference and fail {@code UQ_sti_bank_reference}.
     * The semester id already appears in every reference's own key
     * ({@code UQ_sti_student_semester_currency_no}), so reusing it here keeps
     * the two constraints impossible to violate independently.</p>
     */
    private String bankReference(Currency currency, Semester semester, int studentId, int installmentNo) {
        String prefix = currency == Currency.LBP ? "L" : "";
        return String.format("REF-%d-%s%03d-%02d", semester.getSemesterId(), prefix, studentId, installmentNo);
    }

    private TuitionRate fallbackRate(Semester semester) {
        TuitionRate rate = new TuitionRate();
        rate.setSemesterId(semester.getSemesterId());
        rate.setUsdRatePerCredit(FALLBACK_USD_RATE);
        rate.setLbpRatePerCredit(FALLBACK_LBP_RATE);
        rate.setInstallmentCount(FALLBACK_INSTALLMENTS);
        return rate;
    }
}







