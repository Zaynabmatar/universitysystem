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

    /** Creates a semester. New semesters never start as the current one. */
    public int create(Semester semester) {
        requireUniqueName(semester.getSemesterName(), null);
        semester.setCurrent(false);
        return semesterDao.insert(semester);
    }

    public void update(Semester semester) {
        requireUniqueName(semester.getSemesterName(), semester.getSemesterId());
        semesterDao.update(semester);
    }

    /**
     * THE "exactly one current semester" operation. {@link SemesterDAO#makeCurrent} clears the
     * flag on every row and sets it on one, inside a single transaction, because "only one row
     * may have this value" is not something a plain constraint can express (Section 4.9). There
     * is deliberately no way to un-set the current semester from here — only to move it.
     *
     * <p>Chronology may only move forward: a semester that starts before the one currently active
     * is refused here with a clear message, and refused again — this time unconditionally — by
     * {@code trg_semesters_block_backward_activation} (Phase 19) if this check is ever bypassed.
     * The database, not just this service, is the real guard.</p>
     *
     * @throws ServiceException when {@code semesterId} starts before the semester now current
     */
    public void setCurrent(int semesterId) {
        Semester target = requireSemester(semesterId);
        Semester current = getCurrentSemester();
        if (current != null && current.getSemesterId() != target.getSemesterId()
                && target.getStartDate().isBefore(current.getStartDate())) {
            throw new ServiceException("Cannot make " + target.getSemesterName() + " current: it already ran before "
                    + current.getSemesterName() + ", the semester currently active. "
                    + "The current semester can only move forward in time.");
        }
        semesterDao.makeCurrent(semesterId);
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
