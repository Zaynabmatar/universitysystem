package com.university.service;

import com.university.dao.CourseDAO;
import com.university.dao.CoursePrerequisiteDAO;
import com.university.dao.DepartmentDAO;
import com.university.dao.ProgramDAO;
import com.university.dao.ProgramRequirementDAO;
import com.university.dao.SectionDAO;
import com.university.dao.StudentDAO;
import com.university.model.Course;
import com.university.model.CoursePrerequisite;
import com.university.model.Department;
import com.university.model.Program;
import com.university.model.ProgramRequirement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The academic-catalogue service: departments, programs, courses,
 * prerequisites and program requirements (the degree plan).
 *
 * <p>Phase 06 built the department and program methods; Phase 07 adds
 * everything below rather than introducing a {@code CourseCatalogService},
 * a {@code PrerequisiteService} or a {@code ProgramRequirementService} —
 * the admin screens for people and for the academic structure are separate
 * screens, but the catalogue itself is one service.</p>
 */
public class CourseService {

    private final DepartmentDAO departmentDao = new DepartmentDAO();
    private final ProgramDAO programDao = new ProgramDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final StudentDAO studentDao = new StudentDAO();
    private final CoursePrerequisiteDAO coursePrerequisiteDao = new CoursePrerequisiteDAO();
    private final ProgramRequirementDAO programRequirementDao = new ProgramRequirementDAO();
    private final SectionDAO sectionDao = new SectionDAO();

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

    // ---------------------------------------------------------------- courses

    public List<Course> listCourses(boolean activeOnly) {
        return activeOnly ? courseDao.findAllActive() : courseDao.findAll();
    }

    /** Matched against course code and title; blank/null returns every course. */
    public List<Course> searchCourses(String term) {
        return (term == null || term.isBlank()) ? courseDao.findAll() : courseDao.search(term.trim());
    }

    /** Creates a course. New courses always start active. */
    public int createCourse(Course course) {
        normalizeCode(course);
        requireUniqueCourseCode(course.getCourseCode(), null);
        course.setActive(true);
        return courseDao.insert(course);
    }

    public void updateCourse(Course course) {
        normalizeCode(course);
        requireUniqueCourseCode(course.getCourseCode(), course.getCourseId());
        courseDao.update(course);
    }

    /**
     * SOFT delete only (project_details.md Section 6.8) — is_active flips, the row stays,
     * because grades and past sections reference a course forever.
     */
    public void setCourseActive(int courseId, boolean active) {
        Course course = courseDao.findById(courseId)
                .orElseThrow(() -> new ServiceException("That course no longer exists."));
        course.setActive(active);
        courseDao.update(course);
    }

    /** Open sections in the CURRENT semester — used to warn before deactivating a course. */
    public int countOpenSectionsForCourse(int courseId) {
        return sectionDao.countOpenInCurrentSemester(courseId);
    }

    /** How many courses this one requires — shown as the "Requires" column. */
    public int countPrerequisitesOf(int courseId) {
        return coursePrerequisiteDao.findByCourse(courseId).size();
    }

    /** How many courses require this one — shown as the "Unlocks" column. */
    public int countCoursesUnlockedBy(int courseId) {
        return coursePrerequisiteDao.findByPrerequisiteCourse(courseId).size();
    }

    private void requireUniqueCourseCode(String code, Integer excludeId) {
        boolean taken = courseDao.findByCode(code)
                .map(Course::getCourseId)
                .filter(id -> excludeId == null || !id.equals(excludeId))
                .isPresent();
        if (taken) {
            throw new ValidationException("That course code already exists.");
        }
    }

    private void normalizeCode(Course course) {
        ValidationException.requireText(course.getCourseCode(), "Course code");
        course.setCourseCode(course.getCourseCode().trim().toUpperCase());
    }

    // ---------------------------------------------------------- prerequisites

    /** Every prerequisite link, optionally narrowed to what one course requires. */
    public List<CoursePrerequisite> listPrerequisites(Integer courseId) {
        return courseId == null ? coursePrerequisiteDao.findAll() : coursePrerequisiteDao.findByCourse(courseId);
    }

    /**
     * Adds "courseId requires prerequisiteCourseId".
     *
     * <p>Refuses when the two courses are the same, when the link already exists, or when it
     * would close a cycle — directly or transitively — in the prerequisite graph.</p>
     */
    public void addPrerequisite(int courseId, int prerequisiteCourseId, BigDecimal minGradePoints) {
        String courseCode = codeOf(courseId);
        String prereqCode = codeOf(prerequisiteCourseId);

        if (courseId == prerequisiteCourseId) {
            throw new ValidationException("A course cannot be its own prerequisite.");
        }
        if (prerequisiteExists(courseId, prerequisiteCourseId)) {
            throw new ValidationException(prereqCode + " is already a prerequisite of " + courseCode + ".");
        }

        // The new edge is courseId -> prerequisiteCourseId ("depends on"). It closes a loop
        // if prerequisiteCourseId can already reach courseId through existing edges.
        List<Integer> path = findDependencyPath(prerequisiteCourseId, courseId);
        if (path != null) {
            throw new ValidationException(buildCycleMessage(courseCode, prereqCode, path));
        }

        BigDecimal min = minGradePoints == null ? BigDecimal.ONE : minGradePoints;
        if (min.compareTo(BigDecimal.ZERO) < 0 || min.compareTo(new BigDecimal("4.00")) > 0) {
            throw new ValidationException("Minimum grade points must be between 0.00 and 4.00.");
        }

        CoursePrerequisite link = new CoursePrerequisite();
        link.setCourseId(courseId);
        link.setPrerequisiteCourseId(prerequisiteCourseId);
        link.setMinGradePoints(min);
        coursePrerequisiteDao.insert(link);
    }

    public void updatePrerequisiteMinGrade(int prerequisiteId, BigDecimal minGradePoints) {
        if (minGradePoints == null
                || minGradePoints.compareTo(BigDecimal.ZERO) < 0
                || minGradePoints.compareTo(new BigDecimal("4.00")) > 0) {
            throw new ValidationException("Minimum grade points must be between 0.00 and 4.00.");
        }
        CoursePrerequisite link = coursePrerequisiteDao.findById(prerequisiteId)
                .orElseThrow(() -> new ServiceException("That prerequisite no longer exists."));
        link.setMinGradePoints(minGradePoints);
        coursePrerequisiteDao.update(link);
    }

    /**
     * A prerequisite LINK may be physically removed. It is configuration, not history: no
     * grade, enrollment or transcript row points at a course_prerequisites row. Section 6.8
     * protects records of things that happened — this table only describes a rule.
     */
    public void removePrerequisite(int prerequisiteId) {
        coursePrerequisiteDao.deleteById(prerequisiteId);
    }

    public boolean prerequisiteExists(int courseId, int prerequisiteCourseId) {
        return coursePrerequisiteDao.findByCourse(courseId).stream()
                .anyMatch(link -> link.getPrerequisiteCourseId() == prerequisiteCourseId);
    }

    // ------------------------------------------------------- cycle detection

    /**
     * Depth-first search over the "depends on" graph.
     *
     * @return the path of course ids from {@code from} to {@code target} (inclusive of both),
     *         or null when target is unreachable from {@code from}
     */
    private List<Integer> findDependencyPath(int from, int target) {
        Map<Integer, List<Integer>> graph = loadDependencyGraph();
        Set<Integer> visited = new HashSet<>();
        List<Integer> path = new ArrayList<>();
        return dfs(from, target, graph, visited, path) ? path : null;
    }

    private boolean dfs(int node, int target, Map<Integer, List<Integer>> graph,
                         Set<Integer> visited, List<Integer> path) {
        if (!visited.add(node)) {
            return false;                      // already explored — also guards against bad existing data
        }
        path.add(node);
        if (node == target) {
            return true;
        }
        for (int next : graph.getOrDefault(node, List.of())) {
            if (dfs(next, target, graph, visited, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);          // backtrack
        return false;
    }

    /** course_id -> list of prerequisite_course_id, the whole graph in one pass. */
    private Map<Integer, List<Integer>> loadDependencyGraph() {
        Map<Integer, List<Integer>> graph = new HashMap<>();
        for (CoursePrerequisite link : coursePrerequisiteDao.findAll()) {
            graph.computeIfAbsent(link.getCourseId(), k -> new ArrayList<>()).add(link.getPrerequisiteCourseId());
        }
        return graph;
    }

    /**
     * Builds the exact message the admin sees.
     *
     * @param pathIds the DFS path, starting at the prerequisite and ending at the course
     */
    private String buildCycleMessage(String courseCode, String prereqCode, List<Integer> pathIds) {
        StringBuilder loop = new StringBuilder(courseCode);
        for (int id : pathIds) {
            loop.append(" -> ").append(codeOf(id));
        }
        return "Circular prerequisite detected.\n\n"
             + "Making " + prereqCode + " a prerequisite of " + courseCode
             + " would create this loop:\n\n"
             + "    " + loop + "\n\n"
             + prereqCode + " already requires " + courseCode
             + ", directly or through other courses. A course can never depend on itself.";
    }

    private String codeOf(int courseId) {
        return courseDao.findById(courseId).map(Course::getCourseCode).orElse("#" + courseId);
    }

    // ------------------------------------------------- program requirements (degree plan)

    /** The whole degree plan of one program, optionally narrowed to the mandatory courses. */
    public List<ProgramRequirement> listRequirements(int programId, boolean mandatoryOnly) {
        return mandatoryOnly
                ? programRequirementDao.findMandatoryByProgram(programId)
                : programRequirementDao.findByProgram(programId);
    }

    public void addRequirement(int programId, int courseId, boolean mandatory, int recommendedSemester) {
        if (recommendedSemester < 1 || recommendedSemester > 8) {
            throw new ValidationException("Recommended semester must be between 1 and 8.");
        }
        if (requirementExists(programId, courseId)) {
            throw new ValidationException("That course is already part of this degree plan.");
        }
        ProgramRequirement requirement = new ProgramRequirement();
        requirement.setProgramId(programId);
        requirement.setCourseId(courseId);
        requirement.setMandatory(mandatory);
        requirement.setRecommendedSemester(recommendedSemester);
        programRequirementDao.insert(requirement);
    }

    public void updateRequirement(int requirementId, boolean mandatory, int recommendedSemester) {
        if (recommendedSemester < 1 || recommendedSemester > 8) {
            throw new ValidationException("Recommended semester must be between 1 and 8.");
        }
        ProgramRequirement requirement = programRequirementDao.findById(requirementId)
                .orElseThrow(() -> new ServiceException("That degree-plan entry no longer exists."));
        requirement.setMandatory(mandatory);
        requirement.setRecommendedSemester(recommendedSemester);
        programRequirementDao.update(requirement);
    }

    /** Configuration, not history — a physical delete is correct here, same as a prerequisite link. */
    public void removeRequirement(int requirementId) {
        programRequirementDao.deleteById(requirementId);
    }

    public boolean requirementExists(int programId, int courseId) {
        return programRequirementDao.findByProgram(programId).stream()
                .anyMatch(r -> r.getCourseId() == courseId);
    }

    /** Total mandatory credits in a degree plan — shown as a sanity figure next to the plan. */
    public int sumMandatoryCredits(int programId) {
        return programRequirementDao.findMandatoryByProgram(programId).stream()
                .mapToInt(r -> courseDao.findById(r.getCourseId()).map(Course::getCredits).orElse(0))
                .sum();
    }
}
