package com.university.service;

import com.university.dao.SectionDAO;
import com.university.dao.SemesterDAO;
import com.university.model.Semester;

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

    private final SemesterDAO semesterDao = new SemesterDAO();
    private final SectionDAO sectionDao = new SectionDAO();

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
        semester.setCurrent(false);
        return semesterDao.insert(semester);
    }

    public void update(Semester semester) {
        requireUniqueName(semester.getSemesterName(), semester.getSemesterId());
        requireNoDateOverlap(semester, semester.getSemesterId());
        semesterDao.update(semester);
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
     * @throws ServiceException when a different semester is already current
     */
    public void setCurrent(int semesterId) {
        Semester target = requireSemester(semesterId);
        Semester current = getCurrentSemester();
        if (current != null && current.getSemesterId() != target.getSemesterId()) {
            throw new ServiceException(current.getSemesterName() + " is currently open. "
                    + "Close it before opening another semester.");
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
