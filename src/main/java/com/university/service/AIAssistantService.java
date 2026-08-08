package com.university.service;

import com.university.dao.CourseDAO;
import com.university.dao.CoursePrerequisiteDAO;
import com.university.dao.DepartmentDAO;
import com.university.dao.EnrollmentDAO;
import com.university.dao.InstructorDAO;
import com.university.dao.ProgramDAO;
import com.university.dao.SectionDAO;
import com.university.dao.SectionScheduleDAO;
import com.university.dao.StudentDAO;
import com.university.enums.DayOfWeekCode;
import com.university.enums.LetterGrade;
import com.university.enums.StudentStatus;
import com.university.model.Course;
import com.university.model.Department;
import com.university.model.Enrollment;
import com.university.model.Instructor;
import com.university.model.Program;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.model.StudentGradeRow;
import com.university.service.TranscriptService.DegreeProgress;
import com.university.service.TranscriptService.RequirementRow;
import com.university.service.TranscriptService.SemesterPlan;
import com.university.util.GradeCalculator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers the University AI Assistant chat panel with real, read-only answers drawn from the
 * signed-in {@link Session} and the existing service/DAO layer.
 *
 * <p>Every answer is scoped to the person asking: a student only ever reads their own record
 * (their Student ID from {@link Session#requireStudentId()}), an instructor only ever reads
 * sections they are actually assigned to, and an admin reads aggregate/reference data. Nothing
 * here writes to the database, executes text as SQL, or invents a figure that is not on file —
 * a question this class cannot answer from real data gets the phase-1 placeholder reply back.</p>
 *
 * <p>Matching is by intent, not exact phrase: {@link #matches} checks whether the lower-cased
 * message contains any of a handful of keyword phrases for that intent, so "Show my GPA" and
 * "What is my GPA?" land on the same handler.</p>
 */
public class AIAssistantService {

    /**
     * Internal sentinel meaning "no university intent matched this message" — never shown to the
     * user. {@link #respond} catches it and hands the original question to {@link
     * #generalAiService} instead (Phase 4's Gemini fallback for general, non-university questions).
     */
    private static final String NOT_SUPPORTED = "NOT_SUPPORTED";

    private static final String NOT_SUPPORTED_MESSAGE = "Please type a question.";

    /**
     * Privacy/permission refusal (rule: check role + existing system permissions before answering
     * any university-data question; when access is not allowed in the normal system, refuse with
     * this exact message instead of substituting the asker's own data).
     */
    private static final String UNAUTHORIZED = "You are not authorized to access this information.";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** "CS101", "CS 101", "CS-101" — anywhere in a message. */
    private static final Pattern COURSE_CODE_PATTERN =
            Pattern.compile("\\b([A-Za-z]{2,6})\\s?-?\\s?(\\d{3,4})\\b");

    /** "student id 200", "student #200", "student number 200" — a reference to a specific person. */
    private static final Pattern STUDENT_REF_PATTERN =
            Pattern.compile("(?:student|user)\\s*(?:id|no\\.?|number|#)?\\s*[:#]?\\s*(\\d{1,10})");

    /**
     * "ID 200", "id: 200", "#200" — a bare identifier reference with no leading "student"/"user"
     * word (e.g. "What is the grade of ID 200?"). Used only for the student-side own-data guard
     * below, so it doesn't loosen the instructor-side {@link #referencesUnauthorizedStudent} check.
     */
    private static final Pattern BARE_ID_REFERENCE_PATTERN =
            Pattern.compile("\\b(?:id|number|#)\\s*[:#]?\\s*(\\d{1,10})\\b");

    /** "semester 5", "semester #5" — a reference to a specific stage of a program's study plan. */
    private static final Pattern SEMESTER_NUMBER_PATTERN = Pattern.compile("semester\\s*#?\\s*(\\d{1,2})\\b");

    private final AcademicService academicService = new AcademicService();
    private final TranscriptService transcriptService = new TranscriptService();
    private final RegistrationService registrationService = new RegistrationService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();

    private final StudentDAO studentDao = new StudentDAO();
    private final InstructorDAO instructorDao = new InstructorDAO();
    private final SectionDAO sectionDao = new SectionDAO();
    private final SectionScheduleDAO scheduleDao = new SectionScheduleDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final CoursePrerequisiteDAO coursePrerequisiteDao = new CoursePrerequisiteDAO();
    private final DepartmentDAO departmentDao = new DepartmentDAO();
    private final ProgramDAO programDao = new ProgramDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();

    /**
     * Phase 4's Gemini fallback, shared by all three roles: whatever question no university
     * intent above recognizes gets sent here instead of the old "not supported yet" placeholder.
     */
    private final GeneralAIService generalAiService = new GeneralAIService();

    // ======================================================================
    // Entry point
    // ======================================================================

    /**
     * May perform a blocking network call (the Gemini fallback) — callers must not invoke this
     * on the JavaFX Application Thread.
     */
    public String respond(String message) {
        if (message == null || message.isBlank()) {
            return NOT_SUPPORTED_MESSAGE;
        }
        if (!Session.isActive()) {
            return UNAUTHORIZED;
        }
        try {
            Session session = Session.current();
            String normalized = normalize(message);
            if (matches(normalized, "password", "hash")) {
                return UNAUTHORIZED;
            }
            String universityAnswer = switch (session.getRole()) {
                case STUDENT -> respondToStudent(normalized, message, session.requireStudentId());
                case INSTRUCTOR -> respondToInstructor(normalized, message, session.requireInstructorId());
                case ADMIN -> respondToAdmin(normalized, message);
            };
            if (!NOT_SUPPORTED.equals(universityAnswer)) {
                return universityAnswer;
            }
        } catch (RuntimeException e) {
            return "I couldn't retrieve that information right now. Please try again.";
        }
        // No university intent matched — this is a general question, not a database lookup, so
        // only the asker's own chat text (never anything read from the database) goes to Gemini.
        return generalAiService.respond(message);
    }

    // ======================================================================
    // STUDENT
    // ======================================================================

    private String respondToStudent(String normalized, String original, int studentId) {
        if (referencesAnotherStudent(normalized, studentId)) {
            return UNAUTHORIZED;
        }
        if (matches(normalized, "add/drop", "add drop", "drop deadline", "withdraw deadline",
                "last day to drop", "last day to withdraw", "last day to add")) {
            return addDropDeadlineAnswer();
        }
        if (matches(normalized, "regist")) {
            return registrationWindowAnswer();
        }
        if (matches(normalized, "prerequisite", "prereq")) {
            return prerequisitesAnswer(original);
        }
        if (matches(normalized, "my major", "what is my major", "which major", "my program",
                "what program", "which program")) {
            return majorAnswer(studentId);
        }
        if (matches(normalized, "my grades", "show my grades", "show grades", "list my grades", "list grades",
                "what grades", "which grades", "grades this semester", "grades for", "all my grades",
                "passed course", "failed course", "courses i passed", "courses i failed",
                "in progress course", "did i pass", "did i fail", "which courses did i pass",
                "which courses did i fail")) {
            return gradesListAnswer(studentId);
        }
        if (matches(normalized, "gpa", "grade point average", "grade", "academic standing")) {
            return gpaAnswer(studentId);
        }
        if (matches(normalized, "next class", "next lecture", "upcoming class", "when is my next")) {
            return nextClassAnswer(studentSchedule(studentId));
        }
        Matcher semesterMatch = SEMESTER_NUMBER_PATTERN.matcher(normalized);
        if (semesterMatch.find() && matches(normalized, "course", "class", "subject")) {
            return semesterPlanAnswer(studentId, Integer.parseInt(semesterMatch.group(1)));
        }
        if (matches(normalized, "study plan", "degree plan", "degree progress", "what should i take",
                "graduation plan", "what to take next", "how close am i to graduating",
                "am i eligible to graduate")) {
            return studyPlanAnswer(studentId);
        }
        if (matches(normalized, "schedule", "timetable")) {
            return scheduleAnswer(studentSchedule(studentId));
        }
        if (matches(normalized, "current course", "my courses", "am i taking", "enrolled in",
                "what classes", "courses this semester")) {
            return currentCoursesAnswer(studentId);
        }
        if (matches(normalized, "completed credit", "remaining credit", "credits left", "how many credits",
                "credits do i have", "credits do i need", "credits earned", "credits required")) {
            return creditsAnswer(studentId);
        }
        if (matches(normalized, "email", "contact", "phone number", "who teaches")) {
            return instructorContactAnswer(original, studentId);
        }
        return NOT_SUPPORTED;
    }

    /**
     * "What courses are in semester 5?" — the program's planned curriculum for one recommended
     * semester, from the same {@link DegreeProgress#semesterPlans} that backs the Study Plan
     * screen. Distinct from {@link #studyPlanAnswer}, which reports what is eligible to take next.
     */
    private String semesterPlanAnswer(int studentId, int semesterNumber) {
        DegreeProgress progress = transcriptService.getDegreeProgress(studentId);
        SemesterPlan plan = progress.semesterPlans.stream()
                .filter(p -> p.semesterNumber == semesterNumber)
                .findFirst()
                .orElse(null);
        if (plan == null || plan.courses.isEmpty()) {
            return "There are no study plan courses on file for semester " + semesterNumber + ".";
        }
        StringBuilder sb = new StringBuilder("Semester " + semesterNumber + " courses in your study plan:\n");
        for (RequirementRow row : plan.courses) {
            sb.append("• ").append(row.courseCode).append(" - ").append(row.courseTitle)
                    .append(" (").append(row.statusText()).append(")\n");
        }
        return sb.toString().trim();
    }

    private String gpaAnswer(int studentId) {
        Student student = academicService.academicRecordOf(studentId);
        return "Your cumulative GPA is " + GradeCalculator.formatGpa(student.getCumulativeGpa())
                + " (" + student.getAcademicStanding().getLabel() + ").";
    }

    /** "What is my major?" — the student's own program/major, from their own record only. */
    private String majorAnswer(int studentId) {
        Student student = studentDao.findById(studentId).orElse(null);
        if (student == null) {
            return "I couldn't find your student record.";
        }
        Program program = programDao.findById(student.getProgramId()).orElse(null);
        if (program == null) {
            return "You don't have a program on file yet.";
        }
        return "Your major is " + program.getProgramName() + " (" + program.getProgramCode() + ").";
    }

    /**
     * "Show my grades" / "which courses did I pass or fail" — every enrollment on file (minus
     * dropped ones, which {@link com.university.dao.GradeDAO#findStudentGradeRows} never
     * returns), grouped into Passed / Failed / In progress. A withdrawn or incomplete attempt is
     * neither a pass nor a fail, so it is left out of both groups rather than guessed at.
     */
    private String gradesListAnswer(int studentId) {
        List<StudentGradeRow> rows = academicService.gradeRows(studentId, null);
        if (rows.isEmpty()) {
            return "There are no course records on file for you yet.";
        }
        List<StudentGradeRow> passed = new ArrayList<>();
        List<StudentGradeRow> failed = new ArrayList<>();
        List<StudentGradeRow> inProgress = new ArrayList<>();
        for (StudentGradeRow row : rows) {
            if (!row.isSubmitted()) {
                inProgress.add(row);
                continue;
            }
            LetterGrade letter = row.getLetterGrade();
            if (letter == null || letter == LetterGrade.W || letter == LetterGrade.I) {
                continue;
            }
            (letter.isPassing() ? passed : failed).add(row);
        }
        StringBuilder sb = new StringBuilder();
        appendGradeGroup(sb, "Passed", passed);
        appendGradeGroup(sb, "Failed", failed);
        appendGradeGroup(sb, "In progress", inProgress);
        return sb.toString().trim();
    }

    private void appendGradeGroup(StringBuilder sb, String label, List<StudentGradeRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        sb.append(label).append(" (").append(rows.size()).append("):\n");
        for (StudentGradeRow row : rows) {
            String grade = row.isSubmitted() && row.getLetterGrade() != null
                    ? row.getLetterGrade().getLabel() : "not graded yet";
            sb.append("• ").append(row.getCourseCode()).append(" - ").append(row.getCourseTitle())
                    .append(" (").append(grade).append(")\n");
        }
        sb.append("\n");
    }

    /**
     * "What are the prerequisites for CS201?" — public catalogue information, available to any
     * signed-in role (student, instructor or admin), never anything from another student's record.
     */
    private String prerequisitesAnswer(String message) {
        String courseCode = extractCourseCode(message);
        if (courseCode == null) {
            return "Which course's prerequisites would you like? For example, "
                    + "\"What are the prerequisites for CS201?\"";
        }
        Course course = courseDao.findByCode(courseCode).orElse(null);
        if (course == null) {
            return "I couldn't find a course with the code " + courseCode + ".";
        }
        List<Course> prerequisites = coursePrerequisiteDao.findPrerequisiteCourses(course.getCourseId());
        if (prerequisites.isEmpty()) {
            return course.getCourseCode() + " - " + course.getCourseTitle() + " has no prerequisites.";
        }
        StringBuilder sb = new StringBuilder(
                "Prerequisites for " + course.getCourseCode() + " - " + course.getCourseTitle() + ":\n");
        for (Course prerequisite : prerequisites) {
            sb.append("• ").append(prerequisite.getCourseCode()).append(" - ")
                    .append(prerequisite.getCourseTitle()).append("\n");
        }
        return sb.toString().trim();
    }

    private String creditsAnswer(int studentId) {
        int completed = academicService.completedCredits(studentId);
        int required = academicService.requiredCredits(studentId);
        int remaining = academicService.remainingCredits(studentId);
        return "You have completed " + completed + " of " + required
                + " required credits (" + remaining + " remaining).";
    }

    private String currentCoursesAnswer(int studentId) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Enrollment> enrolled = registrationService.currentRegistrations(studentId, semester.getSemesterId());
        if (enrolled.isEmpty()) {
            return "You are not enrolled in any courses this semester.";
        }
        StringBuilder sb = new StringBuilder("You are currently enrolled in:\n");
        for (Enrollment e : enrolled) {
            Course course = courseOfSection(e.getSectionId());
            if (course != null) {
                sb.append("• ").append(course.getCourseCode()).append(" - ").append(course.getCourseTitle())
                        .append(" (").append(course.getCredits()).append(" credits)\n");
            }
        }
        return sb.toString().trim();
    }

    /** "Who teaches my course?" — the instructor(s) of the student's own current enrollments. */
    private String myCourseInstructorsAnswer(int studentId) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Enrollment> enrolled = registrationService.currentRegistrations(studentId, semester.getSemesterId());
        if (enrolled.isEmpty()) {
            return "You are not enrolled in any courses this semester.";
        }
        StringBuilder sb = new StringBuilder("Your instructors this semester:\n");
        for (Enrollment e : enrolled) {
            Section section = sectionDao.findById(e.getSectionId()).orElse(null);
            if (section == null) {
                continue;
            }
            Course course = courseDao.findById(section.getCourseId()).orElse(null);
            String courseLabel = course == null ? "Section " + section.getSectionNumber() : course.getCourseCode();
            Instructor instructor = section.getInstructorId() == null ? null
                    : instructorDao.findById(section.getInstructorId()).orElse(null);
            String instructorLabel = instructor == null ? "no instructor assigned" : instructor.getFullName();
            sb.append("• ").append(courseLabel).append(": ").append(instructorLabel).append("\n");
        }
        return sb.toString().trim();
    }

    private String studyPlanAnswer(int studentId) {
        DegreeProgress progress = transcriptService.getDegreeProgress(studentId);
        List<RequirementRow> eligible = new ArrayList<>();
        for (RequirementRow row : progress.requirements) {
            if (row.isEligible()) {
                eligible.add(row);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Degree progress: ").append(progress.creditsCompleted).append(" of ")
                .append(progress.creditsRequired).append(" credits (").append(progress.percentText())
                .append("), cumulative GPA ").append(GradeCalculator.formatGpa(progress.cumulativeGpa)).append(".\n");
        if (eligible.isEmpty()) {
            sb.append("There are no courses currently eligible for you to take next.");
        } else {
            sb.append("Courses you are eligible to take next:\n");
            for (RequirementRow row : eligible) {
                sb.append("• ").append(row.courseCode).append(" - ").append(row.courseTitle).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * True when a student's message asks about somebody other than themselves — an explicit
     * "another/other student" phrase, or a "student id/number ###" that isn't their own. A
     * student may only ever read their own record, so this is checked before any handler runs
     * and, when it fires, the caller must refuse rather than quietly answering with the asker's
     * own data.
     */
    private boolean referencesAnotherStudent(String normalized, int studentId) {
        if (matches(normalized, "another student", "other student", "someone else", "classmate")) {
            return true;
        }
        Student self = studentDao.findById(studentId).orElse(null);
        return referencesForeignId(STUDENT_REF_PATTERN, normalized, self)
                || referencesForeignId(BARE_ID_REFERENCE_PATTERN, normalized, self);
    }

    /** True when {@code pattern} finds an ID/number in the message that does not belong to {@code self}. */
    private boolean referencesForeignId(Pattern pattern, String normalized, Student self) {
        Matcher matcher = pattern.matcher(normalized);
        while (matcher.find()) {
            if (!matchesStudent(matcher.group(1), self)) {
                return true;
            }
        }
        return false;
    }

    /** Does this ID/number in a message identify {@code student}? */
    private static boolean matchesStudent(String number, Student student) {
        return student != null
                && (number.equals(String.valueOf(student.getStudentId()))
                        || number.equals(String.valueOf(student.getUserId()))
                        || number.equals(student.getStudentNumber()));
    }

    private List<SectionSchedule> studentSchedule(int studentId) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return List.of();
        }
        return scheduleDao.findByStudentAndSemester(studentId, semester.getSemesterId());
    }

    // ======================================================================
    // INSTRUCTOR
    // ======================================================================

    private String respondToInstructor(String normalized, String original, int instructorId) {
        Instructor instructor = instructorDao.findById(instructorId).orElse(null);
        if (instructor == null) {
            return NOT_SUPPORTED;
        }
        if (matches(normalized, "another section", "other section", "unrelated section",
                "another instructor", "other instructor's")
                || referencesUnauthorizedStudent(normalized, instructorId)) {
            return UNAUTHORIZED;
        }
        if (matches(normalized, "grade entry", "grade window", "grading window", "grades due", "grade due")) {
            return gradeWindowAnswer();
        }
        if (matches(normalized, "add/drop", "add drop", "drop deadline", "withdraw deadline")) {
            return addDropDeadlineAnswer();
        }
        if (matches(normalized, "regist")) {
            return registrationWindowAnswer();
        }
        if (matches(normalized, "current semester", "what semester", "semester dates")) {
            return currentSemesterAnswer();
        }
        if (matches(normalized, "prerequisite", "prereq")) {
            return prerequisitesAnswer(original);
        }
        if (matches(normalized, "next class", "next lecture", "upcoming class")) {
            return nextClassAnswer(instructorSchedule(instructorId));
        }
        if (matches(normalized, "today")) {
            return todayScheduleAnswer(instructorSchedule(instructorId));
        }
        if (matches(normalized, "timetable", "my schedule", "weekly schedule")) {
            return scheduleAnswer(instructorSchedule(instructorId));
        }
        if (matches(normalized, "how many students", "number of students", "students do i have")) {
            return instructorStudentCountAnswer(instructorId);
        }
        if (matches(normalized, "who is in", "students in", "roster", "class list")) {
            return instructorSectionRosterAnswer(instructorId, original);
        }
        if (matches(normalized, "my sections", "my courses", "what do i teach", "courses do i teach",
                "sections do i teach", "what classes", "classes do i teach")) {
            return instructorSectionsAnswer(instructorId);
        }
        if (matches(normalized, "my department", "my profile", "my info", "my rank", "employee number",
                "about me")) {
            return instructorProfileAnswer(instructor);
        }
        return NOT_SUPPORTED;
    }

    private String instructorSectionsAnswer(int instructorId) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Section> sections = sectionDao.findByInstructorAndSemester(instructorId, semester.getSemesterId());
        if (sections.isEmpty()) {
            return "You are not teaching any sections in " + semester.getSemesterName() + ".";
        }
        StringBuilder sb = new StringBuilder("You are teaching in " + semester.getSemesterName() + ":\n");
        for (Section s : sections) {
            Course course = courseDao.findById(s.getCourseId()).orElse(null);
            String label = course == null ? "Course #" + s.getCourseId()
                    : course.getCourseCode() + " - " + course.getCourseTitle();
            sb.append("• ").append(label).append(" (Section ").append(s.getSectionNumber()).append(", ")
                    .append(s.getEnrolledCount()).append("/").append(s.getCapacity()).append(" students)\n");
        }
        return sb.toString().trim();
    }

    private String instructorStudentCountAnswer(int instructorId) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Section> sections = sectionDao.findByInstructorAndSemester(instructorId, semester.getSemesterId());
        int total = sections.stream().mapToInt(Section::getEnrolledCount).sum();
        return "You have " + total + " student(s) across " + sections.size() + " section(s) in "
                + semester.getSemesterName() + ".";
    }

    /** Only ever reads sections {@code instructorId} is actually assigned to (rule G1's spirit). */
    private String instructorSectionRosterAnswer(int instructorId, String message) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        String courseCode = extractCourseCode(message);
        List<Section> own = sectionDao.findByInstructorAndSemester(instructorId, semester.getSemesterId());
        List<Section> targets = own;
        Course course = courseCode == null ? null : courseDao.findByCode(courseCode).orElse(null);
        if (course != null) {
            final int courseId = course.getCourseId();
            targets = own.stream().filter(s -> s.getCourseId() == courseId).toList();
            if (targets.isEmpty()) {
                return UNAUTHORIZED;
            }
        }
        if (targets.isEmpty()) {
            return "You are not teaching any sections in " + semester.getSemesterName() + ".";
        }
        StringBuilder sb = new StringBuilder();
        for (Section s : targets) {
            Course sectionCourse = courseDao.findById(s.getCourseId()).orElse(null);
            String label = sectionCourse == null ? "Section " + s.getSectionNumber()
                    : sectionCourse.getCourseCode() + "-" + s.getSectionNumber();
            List<Enrollment> active = enrollmentDao.findActiveBySection(s.getSectionId());
            sb.append(label).append(" (").append(active.size()).append(" students):\n");
            for (Enrollment e : active) {
                Student student = studentDao.findById(e.getStudentId()).orElse(null);
                if (student != null) {
                    sb.append("  • ").append(student.getFullName())
                            .append(" (ID ").append(student.getUserId()).append(")\n");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * True when an instructor's message names a specific student (by ID/number) who is not
     * currently enrolled in one of that instructor's own sections. Instructors may only read
     * students they actually teach, so an unresolvable or out-of-scope reference is denied
     * rather than silently ignored.
     */
    private boolean referencesUnauthorizedStudent(String normalized, int instructorId) {
        Matcher matcher = STUDENT_REF_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return false;
        }
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return true;
        }
        List<Student> own = sectionDao.findByInstructorAndSemester(instructorId, semester.getSemesterId()).stream()
                .flatMap(s -> enrollmentDao.findActiveBySection(s.getSectionId()).stream())
                .map(e -> studentDao.findById(e.getStudentId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        do {
            String number = matcher.group(1);
            boolean taught = own.stream().anyMatch(st -> matchesStudent(number, st));
            if (!taught) {
                return true;
            }
        } while (matcher.find());
        return false;
    }

    private String gradeWindowAnswer() {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        LocalDate start = semester.getGradeEntryStart();
        LocalDate end = semester.getGradeEntryEnd();
        if (start == null || end == null) {
            return "The grade entry period has not been set for " + semester.getSemesterName() + ".";
        }
        LocalDate today = LocalDate.now();
        boolean open = !today.isBefore(start) && !today.isAfter(end);
        return "The grade entry window for " + semester.getSemesterName() + " is "
                + (open ? "OPEN" : "CLOSED") + " (" + DATE.format(start) + " to " + DATE.format(end) + ").";
    }

    private String instructorProfileAnswer(Instructor instructor) {
        Department dept = departmentDao.findById(instructor.getDepartmentId()).orElse(null);
        StringBuilder sb = new StringBuilder(instructor.getFullName());
        sb.append(" — ").append(instructor.getAcademicRank());
        if (dept != null) {
            sb.append(", ").append(dept.getDepartmentName());
        }
        sb.append(". Email: ").append(instructor.getEmail());
        if (instructor.getPhone() != null && !instructor.getPhone().isBlank()) {
            sb.append(", Phone: ").append(instructor.getPhone());
        }
        sb.append(".");
        return sb.toString();
    }

    private String todayScheduleAnswer(List<SectionSchedule> meetings) {
        DayOfWeekCode today = DayOfWeekCode.fromJavaDayOfWeek(LocalDate.now().getDayOfWeek());
        List<SectionSchedule> todays = meetings.stream()
                .filter(m -> m.getDayOfWeek() == today)
                .sorted(Comparator.comparing(SectionSchedule::getStartTime))
                .toList();
        if (todays.isEmpty()) {
            return "You have no classes today.";
        }
        StringBuilder sb = new StringBuilder("Today's schedule:\n");
        for (SectionSchedule m : todays) {
            sb.append("• ").append(meetingLabel(m)).append("\n");
        }
        return sb.toString().trim();
    }

    private List<SectionSchedule> instructorSchedule(int instructorId) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return List.of();
        }
        return scheduleDao.findByInstructorAndSemester(instructorId, semester.getSemesterId());
    }

    // ======================================================================
    // ADMIN
    // ======================================================================

    private String respondToAdmin(String normalized, String original) {
        if (matches(normalized, "prerequisite", "prereq")) {
            return prerequisitesAnswer(original);
        }
        boolean asksAboutProgram = normalized.contains("program");
        if (asksAboutProgram
                && matches(normalized, "how many students", "number of students", "students are", "students in")) {
            return adminProgramStudentCountAnswer(original);
        }
        if (matches(normalized, "active student", "how many students", "number of students", "total students")) {
            return "There are " + studentDao.findByStatus(StudentStatus.ACTIVE).size() + " active student(s).";
        }
        if (asksAboutProgram
                && matches(normalized, "courses for", "courses in", "show courses", "list courses")) {
            return adminProgramCoursesAnswer(original);
        }
        if (matches(normalized, "catalogue", "catalog", "how many courses", "number of courses",
                "courses are offered", "courses are in")) {
            return "The catalogue has " + courseDao.findAllActive().size() + " active course(s) across "
                    + departmentDao.findAllActive().size() + " department(s).";
        }
        if (matches(normalized, "current semester", "what semester", "semester dates")) {
            return currentSemesterAnswer();
        }
        if (matches(normalized, "department overview", "departments", "overview of departments")) {
            return adminDepartmentOverviewAnswer();
        }
        if (matches(normalized, "how many instructors", "number of instructors", "active instructors")) {
            return "There are " + instructorDao.findAllActive().size() + " active instructor(s).";
        }
        if (matches(normalized, "how many programs", "number of programs", "active programs")) {
            return "There are " + programDao.findAllActive().size() + " active program(s).";
        }
        if (matches(normalized, "sections are full", "sections full", "which sections", "full section")) {
            return adminFullSectionsAnswer();
        }
        if (matches(normalized, "how many sections", "number of sections")) {
            Semester semester = semesterService.getCurrentSemester();
            if (semester == null) {
                return "There is no current semester set up yet.";
            }
            return "There are " + sectionDao.findBySemester(semester.getSemesterId()).size()
                    + " section(s) in " + semester.getSemesterName() + ".";
        }
        if (matches(normalized, "add/drop", "add drop", "drop deadline", "withdraw deadline")) {
            return addDropDeadlineAnswer();
        }
        if (matches(normalized, "regist")) {
            return registrationWindowAnswer();
        }
        if (matches(normalized, "email", "contact", "who teaches")) {
            return instructorContactAnswer(original);
        }
        if (matches(normalized, "gpa", "grade point average", "grade", "academic standing", "credit")) {
            return adminStudentAcademicAnswer(normalized);
        }
        return NOT_SUPPORTED;
    }

    /** "Which sections are full?" — sections at or over capacity in the current semester. */
    private String adminFullSectionsAnswer() {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Section> full = sectionDao.findBySemester(semester.getSemesterId()).stream()
                .filter(s -> s.getCapacity() > 0 && s.getEnrolledCount() >= s.getCapacity())
                .toList();
        if (full.isEmpty()) {
            return "No sections are full in " + semester.getSemesterName() + ".";
        }
        StringBuilder sb = new StringBuilder("Full sections in " + semester.getSemesterName() + ":\n");
        for (Section s : full) {
            Course course = courseDao.findById(s.getCourseId()).orElse(null);
            String label = course == null ? "Course #" + s.getCourseId()
                    : course.getCourseCode() + " - " + course.getCourseTitle();
            sb.append("• ").append(label).append(" (Section ").append(s.getSectionNumber()).append(", ")
                    .append(s.getEnrolledCount()).append("/").append(s.getCapacity()).append(")\n");
        }
        return sb.toString().trim();
    }

    /** "How many students are in this program?" — named by program code or program name. */
    private String adminProgramStudentCountAnswer(String message) {
        Program program = findMentionedProgram(message);
        if (program == null) {
            return "I couldn't identify which program you mean. Try including the program name or code, "
                    + "for example \"How many students are in the Computer Science program?\"";
        }
        int count = studentDao.findByProgram(program.getProgramId()).size();
        return "There are " + count + " student(s) in " + program.getProgramName()
                + " (" + program.getProgramCode() + ").";
    }

    /** "Show courses for this program." — named by program code or program name. */
    private String adminProgramCoursesAnswer(String message) {
        Program program = findMentionedProgram(message);
        if (program == null) {
            return "I couldn't identify which program you mean. Try including the program name or code, "
                    + "for example \"Show courses for the Computer Science program.\"";
        }
        List<Course> courses = courseDao.findByProgram(program.getProgramId());
        if (courses.isEmpty()) {
            return "There are no courses on file for " + program.getProgramName() + ".";
        }
        StringBuilder sb = new StringBuilder(
                "Courses in " + program.getProgramName() + " (" + program.getProgramCode() + "):\n");
        for (Course c : courses) {
            sb.append("• ").append(c.getCourseCode()).append(" - ").append(c.getCourseTitle())
                    .append(" (").append(c.getCredits()).append(" credits)\n");
        }
        return sb.toString().trim();
    }

    /**
     * Finds the program a message is asking about, by program code or program name. Mirrors
     * {@link #findMentionedInstructor}: returns null (rather than guessing) when no active
     * program is named, or when more than one is named ambiguously.
     */
    private Program findMentionedProgram(String message) {
        String normalized = normalize(message);
        Program match = null;
        for (Program p : programDao.findAllActive()) {
            String code = lower(p.getProgramCode());
            String name = lower(p.getProgramName());
            boolean mentioned = (!code.isBlank() && containsWord(normalized, code))
                    || (name.length() >= 4 && normalized.contains(name));
            if (mentioned) {
                if (match != null && match.getProgramId() != p.getProgramId()) {
                    return null;
                }
                match = p;
            }
        }
        return match;
    }

    /**
     * Admin per-student academic lookup (GPA, grades, academic standing, completed credits) —
     * read-only, and only for a specific student named by "student ID ###" (or an equivalent
     * "id/number/#" reference) in the message. Reuses the same {@link AcademicService} calls the
     * student's own dashboard uses ({@link #gpaAnswer} / {@link #creditsAnswer}), just for the
     * named student instead of the asker; falls back to {@link #NOT_SUPPORTED} when no student is
     * identified so this never turns into a blanket admin GPA/credits report.
     */
    private String adminStudentAcademicAnswer(String normalized) {
        Integer studentUserId = extractReferencedStudentUserId(normalized);
        if (studentUserId == null) {
            return NOT_SUPPORTED;
        }
        Student student = studentDao.findByUserId(studentUserId).orElse(null);
        if (student == null) {
            return "I couldn't find a student with ID " + studentUserId + ".";
        }
        int studentId = student.getStudentId();
        String who = student.getFullName() + " (Student ID " + studentUserId + ")";
        if (matches(normalized, "credit")) {
            int completed = academicService.completedCredits(studentId);
            int required = academicService.requiredCredits(studentId);
            int remaining = academicService.remainingCredits(studentId);
            return who + " has completed " + completed + " of " + required
                    + " required credits (" + remaining + " remaining).";
        }
        Student record = academicService.academicRecordOf(studentId);
        return who + " has a cumulative GPA of " + GradeCalculator.formatGpa(record.getCumulativeGpa())
                + " (" + record.getAcademicStanding().getLabel() + ").";
    }

    /** The Student ID (users.user_id) named in an admin's message, or null when none is found. */
    private Integer extractReferencedStudentUserId(String normalized) {
        Matcher matcher = STUDENT_REF_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            matcher = BARE_ID_REFERENCE_PATTERN.matcher(normalized);
            if (!matcher.find()) {
                return null;
            }
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String adminDepartmentOverviewAnswer() {
        List<Department> departments = departmentDao.findAllActive();
        if (departments.isEmpty()) {
            return "There are no active departments on file.";
        }
        StringBuilder sb = new StringBuilder("Department overview:\n");
        for (Department d : departments) {
            int programs = courseService.countActiveProgramsInDepartment(d.getDepartmentId());
            int courses = courseService.countActiveCoursesInDepartment(d.getDepartmentId());
            int instructors = instructorDao.findByDepartment(d.getDepartmentId()).size();
            sb.append("• ").append(d.getDepartmentName()).append(": ").append(programs)
                    .append(" program(s), ").append(courses).append(" course(s), ").append(instructors)
                    .append(" instructor(s)\n");
        }
        return sb.toString().trim();
    }

    // ======================================================================
    // Shared: semester dates, timetable, "next class", instructor lookup
    // ======================================================================

    private String registrationWindowAnswer() {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null || semester.getRegistrationStart() == null || semester.getRegistrationEnd() == null) {
            return "Registration dates have not been set for the current semester.";
        }
        LocalDateTime now = LocalDateTime.now();
        String status = semester.isRegistrationOpen(now) ? "Registration is currently OPEN"
                : "Registration is currently CLOSED";
        return status + " for " + semester.getSemesterName() + ". It opens "
                + DATE_TIME.format(semester.getRegistrationStart()) + " and closes "
                + DATE_TIME.format(semester.getRegistrationEnd()) + ".";
    }

    private String addDropDeadlineAnswer() {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        String drop = semester.getDropDeadline() == null ? "not set" : DATE.format(semester.getDropDeadline());
        String withdraw = semester.getWithdrawDeadline() == null ? "not set"
                : DATE.format(semester.getWithdrawDeadline());
        return "For " + semester.getSemesterName() + ": the drop deadline is " + drop
                + " and the withdrawal deadline is " + withdraw + ".";
    }

    private String currentSemesterAnswer() {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "No semester is currently marked as the active one.";
        }
        return "The current semester is " + semester.getSemesterName() + " (" + semester.getAcademicYear()
                + "), running " + DATE.format(semester.getStartDate()) + " to "
                + DATE.format(semester.getEndDate()) + ".";
    }

    private String nextClassAnswer(List<SectionSchedule> meetings) {
        if (meetings.isEmpty()) {
            return "You have no scheduled classes this semester.";
        }
        LocalDateTime now = LocalDateTime.now();
        SectionSchedule best = null;
        LocalDateTime bestWhen = null;
        for (SectionSchedule m : meetings) {
            LocalDateTime when = nextOccurrence(m, now);
            if (bestWhen == null || when.isBefore(bestWhen)) {
                bestWhen = when;
                best = m;
            }
        }
        return "Your next class is " + meetingLabel(best) + " on " + best.getDayOfWeek().getLabel() + ".";
    }

    /** The next date/time this weekly meeting occurs at or after {@code now}. */
    private LocalDateTime nextOccurrence(SectionSchedule meeting, LocalDateTime now) {
        DayOfWeek target = meeting.getDayOfWeek().toJavaDayOfWeek();
        LocalDate today = now.toLocalDate();
        int daysUntil = (target.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        LocalDateTime candidate = LocalDateTime.of(today.plusDays(daysUntil), meeting.getStartTime());
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(7);
        }
        return candidate;
    }

    private String scheduleAnswer(List<SectionSchedule> meetings) {
        if (meetings.isEmpty()) {
            return "There are no scheduled classes on file for this semester.";
        }
        Map<DayOfWeekCode, List<SectionSchedule>> byDay = new EnumMap<>(DayOfWeekCode.class);
        for (SectionSchedule m : meetings) {
            byDay.computeIfAbsent(m.getDayOfWeek(), d -> new ArrayList<>()).add(m);
        }
        StringBuilder sb = new StringBuilder("Your weekly schedule:\n");
        for (DayOfWeekCode day : DayOfWeekCode.values()) {
            List<SectionSchedule> dayMeetings = byDay.get(day);
            if (dayMeetings == null) {
                continue;
            }
            dayMeetings.sort(Comparator.comparing(SectionSchedule::getStartTime));
            sb.append("\n").append(day.getLabel()).append(":\n");
            for (SectionSchedule m : dayMeetings) {
                sb.append("  ").append(meetingLabel(m)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** "CS101 09:00-10:15 (Room A12)". */
    private String meetingLabel(SectionSchedule meeting) {
        Section section = sectionDao.findById(meeting.getSectionId()).orElse(null);
        Course course = section == null ? null : courseDao.findById(section.getCourseId()).orElse(null);
        String code = course == null ? "your class" : course.getCourseCode() + " - " + course.getCourseTitle();
        String room = section != null && section.getRoom() != null && !section.getRoom().isBlank()
                ? " (Room " + section.getRoom() + ")" : "";
        return code + " " + HM.format(meeting.getStartTime()) + "-" + HM.format(meeting.getEndTime()) + room;
    }

    private Course courseOfSection(int sectionId) {
        Section section = sectionDao.findById(sectionId).orElse(null);
        return section == null ? null : courseDao.findById(section.getCourseId()).orElse(null);
    }

    /** Admin-side lookup: by name or by a course code only, no "my course" self-reference. */
    private String instructorContactAnswer(String message) {
        return instructorContactAnswer(message, null);
    }

    /**
     * Finds the instructor a message is asking about, by name or by a course code, or — for a
     * student asking about "my course"/"my class" with no name or code given — the instructor(s)
     * of that student's own current enrollments. Never invents an answer: returns a clarifying
     * message when nobody can be identified.
     */
    private String instructorContactAnswer(String message, Integer studentId) {
        Instructor instructor = findMentionedInstructor(message);
        if (instructor == null) {
            String courseCode = extractCourseCode(message);
            if (courseCode != null) {
                instructor = instructorForCourse(courseCode);
            }
        }
        if (instructor == null && studentId != null && matches(normalize(message), "my course", "my courses",
                "my class", "my classes", "this course", "our course")) {
            return myCourseInstructorsAnswer(studentId);
        }
        if (instructor == null) {
            return "I couldn't identify which instructor you mean. Try including their last name or the "
                    + "course code, for example \"What is Dr. Ahmad's email?\" or \"Who teaches CS101?\"";
        }
        Department dept = departmentDao.findById(instructor.getDepartmentId()).orElse(null);
        StringBuilder sb = new StringBuilder(instructor.getFullName());
        if (dept != null) {
            sb.append(" (").append(dept.getDepartmentName()).append(")");
        }
        sb.append(" can be reached at ").append(instructor.getEmail());
        if (instructor.getPhone() != null && !instructor.getPhone().isBlank()) {
            sb.append(" or ").append(instructor.getPhone());
        }
        sb.append(".");
        return sb.toString();
    }

    private Instructor findMentionedInstructor(String message) {
        String normalized = normalize(message);
        List<Instructor> all = instructorDao.findAll();

        // A full "first last" name is unambiguous even when one of the two tokens, taken alone,
        // also belongs to a different instructor (two instructors can share a first name).
        Instructor fullNameMatch = null;
        for (Instructor i : all) {
            String full = (lower(i.getFirstName()) + " " + lower(i.getLastName())).trim();
            if (full.length() >= 5 && normalized.contains(full)) {
                if (fullNameMatch != null && fullNameMatch.getInstructorId() != i.getInstructorId()) {
                    return null; // ambiguous — two full names both appear
                }
                fullNameMatch = i;
            }
        }
        if (fullNameMatch != null) {
            return fullNameMatch;
        }

        Instructor match = null;
        for (Instructor i : all) {
            String last = lower(i.getLastName());
            String first = lower(i.getFirstName());
            boolean mentioned = (last.length() >= 3 && containsWord(normalized, last))
                    || (first.length() >= 3 && containsWord(normalized, first));
            if (mentioned) {
                if (match != null && match.getInstructorId() != i.getInstructorId()) {
                    return null; // ambiguous — more than one instructor mentioned
                }
                match = i;
            }
        }
        return match;
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private Instructor instructorForCourse(String courseCode) {
        Course course = courseDao.findByCode(courseCode).orElse(null);
        if (course == null) {
            return null;
        }
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return null;
        }
        return sectionDao.findByCourseAndSemester(course.getCourseId(), semester.getSemesterId()).stream()
                .map(Section::getInstructorId)
                .filter(Objects::nonNull)
                .findFirst()
                .flatMap(instructorDao::findById)
                .orElse(null);
    }

    private String extractCourseCode(String message) {
        Matcher m = COURSE_CODE_PATTERN.matcher(message);
        if (m.find()) {
            return (m.group(1) + m.group(2)).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    // ======================================================================
    // Intent matching helpers
    // ======================================================================

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean matches(String normalized, String... keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }
}
