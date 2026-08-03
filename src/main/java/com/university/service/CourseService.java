package com.university.service;

import com.university.dao.CourseDAO;
import com.university.dao.DepartmentDAO;
import com.university.dao.ProgramDAO;
import com.university.dao.StudentDAO;
import com.university.model.Course;
import com.university.model.Department;
import com.university.model.Program;

import java.util.List;

/**
 * The academic-catalogue service: departments and programs in this phase.
 *
 * <p>Phase 07 adds course, prerequisite and program-requirement methods to
 * this same class rather than introducing a {@code DepartmentService} or a
 * {@code ProgramService} — the admin screens for people and for the academic
 * structure are separate screens, but the catalogue itself is one service.</p>
 */
public class CourseService {

    private final DepartmentDAO departmentDao = new DepartmentDAO();
    private final ProgramDAO programDao = new ProgramDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final StudentDAO studentDao = new StudentDAO();

    // ------------------------------------------------------------ departments

    public List<Department> listDepartments(boolean activeOnly) {
        return activeOnly ? departmentDao.findAllActive() : departmentDao.findAll();
    }

    /** Creates a department. New departments always start active. */
    public int createDepartment(Department department) {
        normalizeCode(department);
        requireUniqueDepartmentCode(department.getDepartmentCode(), null);
        department.setActive(true);
        return departmentDao.insert(department);
    }

    public void updateDepartment(Department department) {
        normalizeCode(department);
        requireUniqueDepartmentCode(department.getDepartmentCode(), department.getDepartmentId());
        departmentDao.update(department);
    }

    /** SOFT delete only (project_details.md Section 6.8) — is_active flips, the row stays. */
    public void setDepartmentActive(int departmentId, boolean active) {
        Department department = departmentDao.findById(departmentId)
                .orElseThrow(() -> new ServiceException("That department no longer exists."));
        department.setActive(active);
        departmentDao.update(department);
    }

    /** Used to warn the admin before deactivating: how many programs would be left orphaned-looking. */
    public int countActiveProgramsInDepartment(int departmentId) {
        return (int) programDao.findByDepartment(departmentId).stream()
                .filter(Program::isActive)
                .count();
    }

    /** Used to warn the admin before deactivating: how many courses belong to this department. */
    public int countActiveCoursesInDepartment(int departmentId) {
        return (int) courseDao.findByDepartment(departmentId).stream()
                .filter(Course::isActive)
                .count();
    }

    private void requireUniqueDepartmentCode(String code, Integer excludeId) {
        boolean taken = departmentDao.findByCode(code)
                .map(Department::getDepartmentId)
                .filter(id -> excludeId == null || !id.equals(excludeId))
                .isPresent();
        if (taken) {
            throw new ValidationException("That department code already exists.");
        }
    }

    private void normalizeCode(Department department) {
        ValidationException.requireText(department.getDepartmentCode(), "Department code");
        department.setDepartmentCode(department.getDepartmentCode().trim().toUpperCase());
    }

    // ---------------------------------------------------------------- programs

    public List<Program> listPrograms(boolean activeOnly) {
        return activeOnly ? programDao.findAllActive() : programDao.findAll();
    }

    /** Creates a program. New programs always start active. */
    public int createProgram(Program program) {
        normalizeCode(program);
        requireUniqueProgramCode(program.getProgramCode(), null);
        program.setActive(true);
        return programDao.insert(program);
    }

    public void updateProgram(Program program) {
        normalizeCode(program);
        requireUniqueProgramCode(program.getProgramCode(), program.getProgramId());
        programDao.update(program);
    }

    /** SOFT delete only (project_details.md Section 6.8) — is_active flips, the row stays. */
    public void setProgramActive(int programId, boolean active) {
        Program program = programDao.findById(programId)
                .orElseThrow(() -> new ServiceException("That program no longer exists."));
        program.setActive(active);
        programDao.update(program);
    }

    /** Used to warn the admin before deactivating: how many students are on this program. */
    public int countStudentsInProgram(int programId) {
        return studentDao.findByProgram(programId).size();
    }

    private void requireUniqueProgramCode(String code, Integer excludeId) {
        boolean taken = programDao.findByCode(code)
                .map(Program::getProgramId)
                .filter(id -> excludeId == null || !id.equals(excludeId))
                .isPresent();
        if (taken) {
            throw new ValidationException("That program code already exists.");
        }
    }

    private void normalizeCode(Program program) {
        ValidationException.requireText(program.getProgramCode(), "Program code");
        program.setProgramCode(program.getProgramCode().trim().toUpperCase());
    }
}
