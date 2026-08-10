package com.university.service;

import com.university.enums.CalendarEventType;
import com.university.model.CalendarEvent;
import com.university.model.Exam;
import com.university.model.Semester;
import com.university.model.StudentInvoice;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the Academic Calendar for one semester: every date already in the database (semester
 * start/end, registration, drop/withdraw deadlines, exams, payment due dates) combined with
 * Admin's own {@link CalendarEventService} entries, plus the plain-English notes shown under the
 * calendar.
 *
 * <p>Nothing here is hardcoded or duplicated. Every entry and every note sentence is built from a
 * real row read through {@link SemesterService}, {@link ExamService}, {@link FinanceService} and
 * {@link CalendarEventService} — the same services every other screen already uses. Semester
 * dates, registration, drop/withdraw and exams stay owned by their own tables; this class only
 * combines them for display and writes nothing.</p>
 */
public class AcademicCalendarService {

    private static final DateTimeFormatter D_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    /** Highest-priority type wins a day cell's background; every other type on that day becomes a dot. */
    public static final List<CalendarEventType> DAY_PRIORITY = List.of(
            CalendarEventType.SEMESTER, CalendarEventType.EXAM, CalendarEventType.WITHDRAW,
            CalendarEventType.ADD_DROP, CalendarEventType.REGISTRATION, CalendarEventType.PAYMENT,
            CalendarEventType.HOLIDAY, CalendarEventType.UNIVERSITY_EVENT, CalendarEventType.OTHER);

    private final SemesterService semesterService = new SemesterService();
    private final ExamService examService = new ExamService();
    private final FinanceService financeService = new FinanceService();
    private final CalendarEventService calendarEventService = new CalendarEventService();

    /** One date or date range the calendar paints — either system-derived or a custom Admin event. */
    public record Entry(LocalDate startDate, LocalDate endDate, String title, CalendarEventType type,
                         Integer customEventId, boolean active) {

        /** True for an Admin-authored {@code dbo.calendar_events} row, false for a system date. */
        public boolean isCustom() {
            return customEventId != null;
        }

        public boolean includes(LocalDate date) {
            return !date.isBefore(startDate) && !date.isAfter(endDate);
        }
    }

    /** One Monday-through-Saturday day of the semester week table; {@code inSemester} is false for
     *  the leading/trailing days of a partial first/last week that fall outside the semester dates. */
    public record DayCell(LocalDate date, boolean inSemester) {
    }

    /** One row of the semester week table: six day cells (Mon-Sat) plus the week's distinct remarks,
     *  highest-priority type first. */
    public record WeekRow(int weekNumber, List<DayCell> days, List<Entry> remarks) {
    }

    /**
     * Splits the semester into Monday-Saturday week rows, from the Monday on/before the semester
     * start date through the Saturday on/after the semester end date, with each week's distinct
     * remarks (one entry per {@code (type, title)} pair touching that week, highest-priority first).
     */
    public List<WeekRow> weeksForSemester(Semester semester, List<Entry> entries) {
        List<WeekRow> weeks = new ArrayList<>();
        LocalDate start = semester.getStartDate();
        LocalDate end = semester.getEndDate();
        if (start == null || end == null) {
            return weeks;
        }

        LocalDate cursor = start.minusDays(start.getDayOfWeek().getValue() - 1); // Monday on/before start
        int weekNumber = 1;
        while (!cursor.isAfter(end)) {
            List<DayCell> days = new ArrayList<>(6);
            for (int i = 0; i < 6; i++) {
                LocalDate day = cursor.plusDays(i);
                days.add(new DayCell(day, !day.isBefore(start) && !day.isAfter(end)));
            }
            LocalDate weekEnd = cursor.plusDays(6); // include Sunday so a weekend-only entry is not missed
            weeks.add(new WeekRow(weekNumber++, days, remarksFor(entries, cursor, weekEnd)));
            cursor = cursor.plusWeeks(1);
        }
        return weeks;
    }

    private List<Entry> remarksFor(List<Entry> entries, LocalDate weekStart, LocalDate weekEnd) {
        Map<String, Entry> distinct = new LinkedHashMap<>();
        entries.stream()
                .filter(e -> !e.endDate().isBefore(weekStart) && !e.startDate().isAfter(weekEnd))
                .sorted(Comparator.comparing(e -> DAY_PRIORITY.indexOf(e.type())))
                .forEach(e -> distinct.putIfAbsent(e.type().name() + "|" + e.title(), e));
        return List.copyOf(distinct.values());
    }

    /**
     * Every entry for one semester.
     *
     * @param studentId when not null, exams and the payment due date are scoped to that student's
     *                  own enrollments/invoice (the Student calendar); when null, every exam in
     *                  the semester and every distinct invoice due date are shown instead (the
     *                  Admin calendar, which has no single student in view)
     * @param includeInactiveCustomEvents true for Admin's own management table, so a deactivated
     *                  event can still be found and reactivated; false for the read-only Student
     *                  calendar, which must never show one
     */
    public List<Entry> entriesForSemester(int semesterId, Integer studentId, boolean includeInactiveCustomEvents) {
        Semester semester = semesterService.findById(semesterId);
        List<Entry> entries = new ArrayList<>();

        entries.add(new Entry(semester.getStartDate(), semester.getStartDate(),
                semester.getSemesterName() + " Begins", CalendarEventType.SEMESTER, null, true));
        entries.add(new Entry(semester.getEndDate(), semester.getEndDate(),
                semester.getSemesterName() + " Ends", CalendarEventType.SEMESTER, null, true));

        if (semester.getRegistrationStart() != null && semester.getRegistrationEnd() != null) {
            entries.add(new Entry(semester.getRegistrationStart().toLocalDate(),
                    semester.getRegistrationEnd().toLocalDate(), "Registration",
                    CalendarEventType.REGISTRATION, null, true));
        }
        if (semester.getDropDeadline() != null) {
            entries.add(new Entry(semester.getDropDeadline(), semester.getDropDeadline(),
                    "Add/Drop Deadline", CalendarEventType.ADD_DROP, null, true));
        }
        if (semester.getWithdrawDeadline() != null) {
            entries.add(new Entry(semester.getWithdrawDeadline(), semester.getWithdrawDeadline(),
                    "Withdrawal Deadline", CalendarEventType.WITHDRAW, null, true));
        }

        List<Exam> exams = studentId == null
                ? examService.examsForSemester(semesterId)
                : examService.examsForStudentInSemester(studentId, semesterId);
        for (Exam exam : exams) {
            entries.add(new Entry(exam.getExamDate(), exam.getExamDate(), "Exam",
                    CalendarEventType.EXAM, null, true));
        }

        if (studentId == null) {
            for (LocalDate due : financeService.dueDatesForSemester(semesterId)) {
                entries.add(new Entry(due, due, "Payment Due", CalendarEventType.PAYMENT, null, true));
            }
        } else {
            financeService.invoiceFor(studentId, semesterId).ifPresent(invoice ->
                    entries.add(new Entry(invoice.getDueDate(), invoice.getDueDate(), "Payment Due",
                            CalendarEventType.PAYMENT, null, true)));
        }

        List<CalendarEvent> customEvents = includeInactiveCustomEvents
                ? calendarEventService.listForSemester(semesterId)
                : calendarEventService.listActiveForSemester(semesterId);
        for (CalendarEvent event : customEvents) {
            entries.add(new Entry(event.getStartDate(), event.getEndDate(), event.getTitle(), event.getType(),
                    event.getEventId(), event.isActive()));
        }

        return entries;
    }

    /**
     * The academic notes shown under the calendar — plain-English sentences built from the same
     * rows {@link #entriesForSemester} reads, never hardcoded text.
     *
     * @param studentId when not null, the payment/exam sentences are the student's own; when
     *                  null, they summarize the whole semester (Admin's view)
     */
    public List<String> notesForSemester(int semesterId, Integer studentId) {
        Semester semester = semesterService.findById(semesterId);
        List<String> notes = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        notes.add(semester.getSemesterName() + " runs from " + D_FMT.format(semester.getStartDate())
                + " to " + D_FMT.format(semester.getEndDate()) + ".");

        if (semester.getRegistrationStart() != null && semester.getRegistrationEnd() != null) {
            String state = semester.isRegistrationOpen(now) ? "Registration is currently OPEN."
                    : now.isBefore(semester.getRegistrationStart()) ? "Registration has not opened yet."
                    : "Registration is closed.";
            notes.add("Registration runs from " + D_FMT.format(semester.getRegistrationStart().toLocalDate())
                    + " to " + D_FMT.format(semester.getRegistrationEnd().toLocalDate()) + ". " + state);
        }
        if (semester.getDropDeadline() != null) {
            notes.add("Before " + D_FMT.format(semester.getDropDeadline())
                    + ", a course may be dropped with no record on the transcript.");
        }
        if (semester.getWithdrawDeadline() != null) {
            notes.add("Between the drop deadline and " + D_FMT.format(semester.getWithdrawDeadline())
                    + ", dropping a course records a W on the transcript. After that date, no course "
                    + "may be dropped.");
        }
        if (semester.getGradeEntryStart() != null && semester.getGradeEntryEnd() != null) {
            notes.add("Instructors submit final grades between " + D_FMT.format(semester.getGradeEntryStart())
                    + " and " + D_FMT.format(semester.getGradeEntryEnd()) + ".");
        }

        if (studentId == null) {
            List<LocalDate> dueDates = financeService.dueDatesForSemester(semesterId);
            if (!dueDates.isEmpty()) {
                StringBuilder dates = new StringBuilder();
                for (int i = 0; i < dueDates.size(); i++) {
                    if (i > 0) dates.append(", ");
                    dates.append(D_FMT.format(dueDates.get(i)));
                }
                notes.add("Tuition payment is due on " + dates + ".");
            }
            int examCount = examService.examsForSemester(semesterId).size();
            if (examCount > 0) {
                notes.add(examCount + " exam" + (examCount == 1 ? "" : "s") + " scheduled this semester.");
            }
        } else {
            Optional<StudentInvoice> invoice = financeService.invoiceFor(studentId, semesterId);
            invoice.ifPresent(inv -> notes.add("Your tuition payment for " + semester.getSemesterName()
                    + " is due on " + D_FMT.format(inv.getDueDate()) + "."));
            int examCount = examService.examsForStudentInSemester(studentId, semesterId).size();
            if (examCount > 0) {
                notes.add("You have " + examCount + " exam" + (examCount == 1 ? "" : "s")
                        + " scheduled this semester.");
            }
        }

        for (CalendarEvent event : calendarEventService.listActiveForSemester(semesterId)) {
            if (event.getType() == CalendarEventType.HOLIDAY
                    || event.getType() == CalendarEventType.UNIVERSITY_EVENT) {
                String range = event.getStartDate().equals(event.getEndDate())
                        ? D_FMT.format(event.getStartDate())
                        : D_FMT.format(event.getStartDate()) + " - " + D_FMT.format(event.getEndDate());
                notes.add(event.getType().getLabel() + ": " + event.getTitle() + " (" + range + ").");
            }
        }

        return notes;
    }
}
