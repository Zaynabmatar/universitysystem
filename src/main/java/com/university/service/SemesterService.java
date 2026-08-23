package com.university.service;

import com.university.dao.SectionDAO;
import com.university.dao.SemesterDAO;
import com.university.dao.TuitionInstallmentDAO;
import com.university.model.Semester;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Everything about {@code dbo.semesters} — the clock of the whole system.
 *
 * <p>This class owns two things nothing else may touch: the six date windows
 * every other rule reads (registration, drop/withdraw, grade entry), and the
 * "exactly one row may have {@code is_current = 1}" invariant. The second is
 * why {@code project_details.md} Section 8 does not list a
 * {@code DepartmentService} or a {@code ProgramService} but this project adds
 * a {@code SemesterService} anyway: a semester is not a person and not part
 * of the course catalogue, so it does not belong in any existing service, and
 * every later phase (registration, drops, grading) reads its dates.</p>
 */
public class SemesterService {

    /** How many days after a semester ends the instructor evaluation window may run at the latest. */
    public static final int EVALUATION_WINDOW_DAYS = 21;

    private final SemesterDAO semesterDao = new SemesterDAO();
    private final SectionDAO sectionDao = new SectionDAO();
    private final TuitionInstallmentDAO installmentDao = new TuitionInstallmentDAO();

    // ------------------------------------------------------------------ read

    public List<Semester> listAll() {
        return semesterDao.findAll();
    }

    public Semester findById(int semesterId) {
        return requireSemester(semesterId);
    }

    /** The one semester with is_current = 1, or null when the database has none. */
    public Semester getCurrentSemester() {
        return semesterDao.findCurrent().orElse(null);
    }

    /** How many sections already exist in a semester — shown next to it and checked before deleting. */
    public int countSections(int semesterId) {
        return sectionDao.findBySemester(semesterId).size();
    }

    public boolean nameExists(String semesterName, Integer excludeId) {
        return semesterDao.findByName(semesterName)
                .map(s -> s.getSemesterId())
                .filter(id -> excludeId == null || !id.equals(excludeId))
                .isPresent();
    }

    // ----------------------------------------------------------------- write

    /**
     * Creates a semester. New semesters never start as the current one.
     *
     * <p>Refused outright while a semester is already current — Admin must
     * {@link #closeCurrent} it first (Section 8: the next semester is only
     * planned once the current one's term is actually over), and refused
     * again when the proposed dates overlap any existing semester's.</p>
     *
     * @throws ServiceException when a semester is currently open, or the dates overlap another semester
     */
    public int create(Semester semester) {
        requireUniqueName(semester.getSemesterName(), null);
        Semester current = getCurrentSemester();
        if (current != null) {
            throw new ServiceException("Cannot create a new semester while " + current.getSemesterName()
                    + " is still open. Close it first.");
        }
        requireNoDateOverlap(semester, null);
        applyEvaluationDefaults(semester);
        requireValidEvaluationWindow(semester);
        semester.setCurrent(false);
        return semesterDao.insert(semester);
    }

    public void update(Semester semester) {
        requireUniqueName(semester.getSemesterName(), semester.getSemesterId());
        requireNoDateOverlap(semester, semester.getSemesterId());
        applyEvaluationDefaults(semester);
        requireValidEvaluationWindow(semester);
        semesterDao.update(semester);

        java.time.LocalDate firstDue = semester.getStartDate().plusWeeks(1);
        java.time.LocalDate lastDue = semester.getEndDate();

        if (firstDue.isAfter(lastDue)) {
            firstDue = semester.getStartDate();
        }

        installmentDao.rescheduleUnpaidForSemester(
                semester.getSemesterId(), firstDue, lastDue);

        installmentDao.refreshDelinquency(semester.getSemesterId());
    }

    /**
     * Refuses term dates that overlap any OTHER semester's — a new semester's start date must
     * fall strictly after the previous one's end date. Mirrors {@code trg_semesters_no_date_overlap}
     * (migration 0013), which refuses the same thing at the database level.
     */
    private void requireNoDateOverlap(Semester semester, Integer excludeId) {
        for (Semester other : semesterDao.findAll()) {
            if (excludeId != null && other.getSemesterId() == excludeId) {
                continue;
            }
            boolean overlaps = !semester.getStartDate().isAfter(other.getEndDate())
                    && !other.getStartDate().isAfter(semester.getEndDate());
            if (overlaps) {
                throw new ServiceException("These term dates overlap " + other.getSemesterName()
                        + " (" + other.getStartDate() + " to " + other.getEndDate()
                        + "). A new semester must start after the previous one ends.");
            }
        }
    }

    /**
     * THE "exactly one current semester" operation. {@link SemesterDAO#makeCurrent} clears the
     * flag on every row and sets it on one, inside a single transaction, because "only one row
     * may have this value" is not something a plain constraint can express (Section 4.9).
     *
     * <p>A different semester may not be opened directly on top of one that is already open — the
     * admin must {@link #closeCurrent} first. This is refused here with a clear message, and
     * refused again — this time unconditionally — by {@code trg_semesters_enforce_single_open} if
     * this check is ever bypassed. The database, not just this service, is the real guard.</p>
     *
     * <p>Equivalent to {@code setCurrent(semesterId, false)} — refuses (rather than silently
     * closing) any other semester whose instructor evaluation window is still open. See
     * {@link #setCurrent(int, boolean)}.</p>
     *
     * @throws ServiceException when a different semester is already current
     * @throws EvaluationWindowOpenException when another semester's evaluation window is still open
     */
    public void setCurrent(int semesterId) {
        setCurrent(semesterId, false);
    }

    /**
     * Same as {@link #setCurrent(int)}, but also decides what happens when another semester's
     * instructor evaluation window ({@code evaluation_start}/{@code evaluation_end}) is still open
     * right now: there must never be an old semester's evaluation period still open once a new
     * semester becomes current.
     *
     * @param closeOpenEvaluationWindows when false (the default via {@link #setCurrent(int)}), an
     *                                   open window on another semester refuses the whole call with
     *                                   {@link EvaluationWindowOpenException} instead of activating
     *                                   {@code semesterId} silently; when true, every such window is
     *                                   force-closed (its {@code evaluation_end} moved to now) first,
     *                                   and {@code semesterId} then becomes current — the Admin's
     *                                   explicit "close it for me" confirmation
     * @throws ServiceException when a different semester is already current
     * @throws EvaluationWindowOpenException when another semester's evaluation window is still open
     *                                        and {@code closeOpenEvaluationWindows} is false
     */
    public void setCurrent(int semesterId, boolean closeOpenEvaluationWindows) {
        Semester target = requireSemester(semesterId);
        Semester current = getCurrentSemester();
        if (current != null && current.getSemesterId() != target.getSemesterId()) {
            throw new ServiceException(current.getSemesterName() + " is currently open. "
                    + "Close it before opening another semester.");
        }

        LocalDateTime now = LocalDateTime.now();
        List<Semester> stillOpen = semesterDao.findAll().stream()
                .filter(other -> other.getSemesterId() != target.getSemesterId())
                .filter(other -> other.isEvaluationOpen(now))
                .toList();

        if (!stillOpen.isEmpty()) {
            if (!closeOpenEvaluationWindows) {
                String names = stillOpen.stream().map(Semester::getSemesterName)
                        .collect(java.util.stream.Collectors.joining(", "));
                throw new EvaluationWindowOpenException(stillOpen,
                        "The instructor evaluation period for " + names + " is still open. "
                        + "It must be closed before " + target.getSemesterName()
                        + " can become the current semester.");
            }
            for (Semester s : stillOpen) {
                semesterDao.closeEvaluationWindow(s.getSemesterId(), now);
            }
        }

        semesterDao.makeCurrent(semesterId);
    }

    /**
     * Closes the currently open semester, leaving none current until another is explicitly
     * opened. The one required step before {@link #setCurrent} may open a different semester.
     *
     * @throws ServiceException when no semester is currently open
     */
    public void closeCurrent() {
        Semester current = getCurrentSemester();
        if (current == null) {
            throw new ServiceException("There is no open semester to close.");
        }
        semesterDao.closeCurrent();
    }

    /**
     * Fills in {@code evaluation_start}/{@code evaluation_end} when the caller left them blank,
     * so every semester gets an instructor evaluation window without the Admin having to set one
     * by hand: start defaults to the semester's own start date, end defaults to the latest this
     * semester is ever allowed to run ({@link #maxEvaluationEnd}).
     */
    private void applyEvaluationDefaults(Semester semester) {
        if (semester.getEvaluationStart() == null) {
            semester.setEvaluationStart(semester.getStartDate().atStartOfDay());
        }
        if (semester.getEvaluationEnd() == null) {
            semester.setEvaluationEnd(maxEvaluationEnd(semester));
        }
    }

    /**
     * The latest moment this semester's evaluation window may end: {@link #EVALUATION_WINDOW_DAYS}
     * days after the semester itself ends, pulled in earlier when the next semester (by start date)
     * already begins before that, so two semesters' evaluation windows can never overlap.
     *
     * <p>{@code semester.getSemesterId()} is 0 for a not-yet-inserted semester, which excludes
     * nothing here since no stored row ever has that id — so this same lookup works unchanged for
     * both {@link #create} and {@link #update}.</p>
     */
    private LocalDateTime maxEvaluationEnd(Semester semester) {
        LocalDateTime hardCap = semester.getEndDate().plusDays(EVALUATION_WINDOW_DAYS).atTime(23, 59, 59);
        return semesterDao.findAll().stream()
                .filter(other -> other.getSemesterId() != semester.getSemesterId())
                .filter(other -> other.getStartDate().isAfter(semester.getEndDate()))
                .map(Semester::getStartDate)
                .min(Comparator.naturalOrder())
                .map(nextStart -> nextStart.atStartOfDay().minusSeconds(1))
                .filter(latestBeforeNext -> latestBeforeNext.isBefore(hardCap))
                .orElse(hardCap);
    }

    /**
     * Section requirement: evaluation_end must be after evaluation_start, and never later than
     * {@link #maxEvaluationEnd} — replaces the old "must fall within the semester dates" rule,
     * which forbade the whole point of a window that runs past the semester's own end date.
     */
    private void requireValidEvaluationWindow(Semester semester) {
        LocalDateTime start = semester.getEvaluationStart();
        LocalDateTime end = semester.getEvaluationEnd();
        if (start == null || end == null) {
            throw new ValidationException("Instructor evaluation start and end are required.");
        }
        if (!end.isAfter(start)) {
            throw new ValidationException("Evaluation end must be after evaluation start.");
        }
        LocalDateTime maxEnd = maxEvaluationEnd(semester);
        if (end.isAfter(maxEnd)) {
            throw new ServiceException("Evaluation end cannot be later than " + maxEnd + " for "
                    + semester.getSemesterName() + " — at most " + EVALUATION_WINDOW_DAYS
                    + " days after the semester ends, and always before the next semester begins.");
        }
    }

    private void requireUniqueName(String semesterName, Integer excludeId) {
        ValidationException.requireText(semesterName, "Semester name");
        if (nameExists(semesterName, excludeId)) {
            throw new ValidationException("That semester name already exists.");
        }
    }

    private Semester requireSemester(int semesterId) {
        ValidationException.requireId(semesterId, "Semester");
        return semesterDao.findById(semesterId)
                .orElseThrow(() -> new ServiceException("That semester no longer exists."));
    }
}


