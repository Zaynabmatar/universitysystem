package com.university.service;

import com.university.dao.CourseDAO;
import com.university.dao.CoursePrerequisiteDAO;
import com.university.dao.DepartmentDAO;
import com.university.dao.EnrollmentDAO;
import com.university.dao.ExamDAO;
import com.university.dao.GradeDAO;
import com.university.dao.InstructorDAO;
import com.university.dao.ProgramDAO;
import com.university.dao.SectionDAO;
import com.university.dao.SectionScheduleDAO;
import com.university.dao.StudentDAO;
import com.university.enums.DayOfWeekCode;
import com.university.enums.InvoiceStatus;
import com.university.enums.LetterGrade;
import com.university.enums.StudentStatus;
import com.university.enums.UserRole;
import com.university.model.AttendanceRow;
import com.university.model.Course;
import com.university.model.Department;
import com.university.model.Enrollment;
import com.university.model.Exam;
import com.university.model.GradeSheetRow;
import com.university.model.Instructor;
import com.university.model.Notification;
import com.university.model.Program;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;
import com.university.model.Student;
import com.university.model.StudentGradeRow;
import com.university.model.StudentInvoice;
import com.university.service.RecommendationService.Recommendation;
import com.university.service.RecommendationService.RecommendationResult;
import com.university.service.TranscriptService.DegreeProgress;
import com.university.service.TranscriptService.RequirementRow;
import com.university.service.TranscriptService.SemesterPlan;
import com.university.util.GradeCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** "section 01", "section #2" — a reference to one specific section of a course. */
    private static final Pattern SECTION_NUMBER_PATTERN =
            Pattern.compile("section\\s*#?\\s*([A-Za-z0-9]{1,10})\\b", Pattern.CASE_INSENSITIVE);

    /** "what is the id of Noor Younis", "instructor id of Noor Younis" — role hint optional, name after "of"/"for". */
    private static final Pattern ID_OF_NAME_PATTERN = Pattern.compile(
            "(?:(student|instructor)\\s+)?id\\s+(?:of|for)\\s+([a-z][a-z .'-]{1,60})", Pattern.CASE_INSENSITIVE);

    /** "Noor Younis's instructor id", "Noor Younis's id" — the name comes before the possessive "'s ... id". */
    private static final Pattern NAME_POSSESSIVE_ID_PATTERN = Pattern.compile(
            "([a-z][a-z .'-]{1,60}?)(?:'s|s')\\s+(?:(student|instructor)\\s+)?id\\b", Pattern.CASE_INSENSITIVE);

    private final AcademicService academicService = new AcademicService();
    private final TranscriptService transcriptService = new TranscriptService();
    private final RegistrationService registrationService = new RegistrationService();
    private final SemesterService semesterService = new SemesterService();
    private final CourseService courseService = new CourseService();
    private final ExamService examService = new ExamService();
    private final NotificationService notificationService = new NotificationService();
    private final FinanceService financeService = new FinanceService();
    private final AttendanceService attendanceService = new AttendanceService();
    private final AcademicCalendarService academicCalendarService = new AcademicCalendarService();
    private final RecommendationService recommendationService = new RecommendationService();

    private final StudentDAO studentDao = new StudentDAO();
    private final InstructorDAO instructorDao = new InstructorDAO();
    private final SectionDAO sectionDao = new SectionDAO();
    private final SectionScheduleDAO scheduleDao = new SectionScheduleDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final CoursePrerequisiteDAO coursePrerequisiteDao = new CoursePrerequisiteDAO();
    private final DepartmentDAO departmentDao = new DepartmentDAO();
    private final ProgramDAO programDao = new ProgramDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final ExamDAO examDao = new ExamDAO();
    private final GradeDAO gradeDao = new GradeDAO();

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
            if (matches(normalized, "notification", "notifications", "my inbox", "unread message")) {
                return notificationsAnswer(session.getUser().getUserId());
            }
            String idLookupAnswer = personIdLookupAnswer(normalized, session);
            if (!NOT_SUPPORTED.equals(idLookupAnswer)) {
                return idLookupAnswer;
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
        if (matches(normalized, "current semester", "what semester", "semester dates")) {
            return currentSemesterAnswer();
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
        if (matches(normalized, "midterm", "coursework mark", "coursework grade", "final mark", "lab mark",
                "grade breakdown", "grade components", "grade component")) {
            return gradeBreakdownAnswer(studentId, original);
        }
        if (matches(normalized, "gpa", "grade point average", "grade", "academic standing")) {
            return gpaAnswer(studentId);
        }
        if (matches(normalized, "my exam", "my exams", "exam schedule", "exam date", "exam dates",
                "upcoming exam", "when is my exam", "what exams", "exams do i have")) {
            return studentExamsAnswer(studentId);
        }
        if (matches(normalized, "balance", "how much do i owe", "tuition", "invoice", "payment due",
                "outstanding payment", "installment")) {
            return studentTuitionAnswer(studentId);
        }
        if (matches(normalized, "absence", "absences", "attendance")) {
            return studentAttendanceAnswer(studentId, original);
        }
        if (matches(normalized, "recommend", "what course should i take", "which course should i take",
                "suggest a course", "course suggestion")) {
            return recommendationsAnswer(studentId);
        }
        if (matches(normalized, "calendar", "holiday", "holidays", "university event", "academic calendar")) {
            return calendarNotesAnswer(studentId);
        }
        if (matches(normalized, "seat", "seats", "open section", "available section")) {
            return seatsAnswer(original);
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
        if (!STUDENT_REF_PATTERN.matcher(normalized).find() && !BARE_ID_REFERENCE_PATTERN.matcher(normalized).find()) {
            // Neither ID pattern appears in the message at all, so there is nothing to compare
            // against another student's ID for — skip the database round trip entirely. This
            // matters for general, non-university questions (e.g. "What is Java?"), which must
            // never touch the database on their way to the Gemini fallback.
            return false;
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
        if (matches(normalized, "my exam", "my exams", "exam schedule", "exam date", "exam dates",
                "upcoming exam", "what exams", "exams do i have", "exams for my section")) {
            return instructorExamsAnswer(instructorId);
        }
        if (matches(normalized, "absence", "absences", "attendance")) {
            return instructorAttendanceAnswer(instructorId, original);
        }
        if (matches(normalized, "seat", "seats", "open section", "available section")) {
            return seatsAnswer(original);
        }
        if (matches(normalized, "next class", "next lecture", "upcoming class")) {
            return nextClassAnswer(instructorSchedule(instructorId));
        }
        if (matches(normalized, "today")
                && matches(normalized, "schedule", "class", "classes", "lecture", "timetable")) {
            return todayScheduleAnswer(instructorSchedule(instructorId));
        }
        if (matches(normalized, "timetable", "my schedule", "weekly schedule")) {
            return scheduleAnswer(instructorSchedule(instructorId));
        }
        if (matches(normalized, "average grade", "grade average", "class average", "section average",
                "average mark")) {
            return instructorGradeAverageAnswer(instructorId, original);
        }
        if (matches(normalized, "how many students", "number of students", "students do i have",
                "students are enrolled", "students enrolled")) {
            return instructorStudentCountAnswer(instructorId, original);
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
            Instructor instructor = instructorDao.findById(instructorId).orElse(null);
            if (instructor == null) {
                return NOT_SUPPORTED;
            }
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

    /**
     * "How many students do I have?" (own total) or "How many students are enrolled in CS101?"
     * (own section(s) of that course only) — a course code in the message scopes the count down
     * to it instead of the instructor's whole teaching load, and a course the instructor does not
     * teach is refused rather than silently reporting the unscoped total.
     */
    private String instructorStudentCountAnswer(int instructorId, String message) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Section> own = sectionDao.findByInstructorAndSemester(instructorId, semester.getSemesterId());
        String courseCode = extractCourseCode(message);
        List<Section> targets = own;
        if (courseCode != null) {
            Course course = courseDao.findByCode(courseCode).orElse(null);
            if (course == null) {
                return "I couldn't find a course with the code " + courseCode + ".";
            }
            final int courseId = course.getCourseId();
            targets = own.stream().filter(s -> s.getCourseId() == courseId).toList();
            if (targets.isEmpty()) {
                return UNAUTHORIZED;
            }
        }
        int total = targets.stream().mapToInt(Section::getEnrolledCount).sum();
        if (courseCode != null) {
            return "You have " + total + " student(s) enrolled in " + courseCode.toUpperCase(Locale.ROOT)
                    + " across " + targets.size() + " section(s) in " + semester.getSemesterName() + ".";
        }
        return "You have " + total + " student(s) across " + targets.size() + " section(s) in "
                + semester.getSemesterName() + ".";
    }

    /**
     * "What is the average grade in CS101?" (instructor) — own section(s) of that course only,
     * counting only grades that have already been submitted (a draft mark is never averaged in,
     * matching {@link com.university.dao.GradeDAO#findStudentGradeRows}'s "no drafts" rule).
     */
    private String instructorGradeAverageAnswer(int instructorId, String message) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        String courseCode = extractCourseCode(message);
        if (courseCode == null) {
            return "Which course? For example, \"What is the average grade in CS101?\"";
        }
        Course course = courseDao.findByCode(courseCode).orElse(null);
        if (course == null) {
            return "I couldn't find a course with the code " + courseCode + ".";
        }
        List<Section> own = sectionDao.findByInstructorAndSemester(instructorId, semester.getSemesterId());
        final int courseId = course.getCourseId();
        List<Section> targets = own.stream().filter(s -> s.getCourseId() == courseId).toList();
        if (targets.isEmpty()) {
            return UNAUTHORIZED;
        }
        String sectionNumber = extractSectionNumber(message);
        if (sectionNumber != null) {
            targets = targets.stream().filter(s -> sectionNumber.equalsIgnoreCase(s.getSectionNumber())).toList();
            if (targets.isEmpty()) {
                return "I couldn't find " + course.getCourseCode() + " section " + sectionNumber
                        + " among your sections.";
            }
        }
        return gradeAverageText(targets, course.getCourseCode() + " - " + course.getCourseTitle());
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
        if (matches(normalized, "section", "sections") && matches(normalized, "teach")) {
            Instructor named = findMentionedInstructor(original);
            if (named != null) {
                return adminInstructorSectionsAnswer(named);
            }
        }
        boolean asksAboutProgram = normalized.contains("program");
        if (asksAboutProgram
                && matches(normalized, "how many students", "number of students", "students are", "students in")) {
            return adminProgramStudentCountAnswer(original);
        }
        if (!asksAboutProgram && extractCourseCode(original) != null
                && matches(normalized, "how many students", "number of students", "students are", "students in",
                        "students enrolled", "enrolled in", "enrollment")) {
            return adminCourseEnrollmentAnswer(extractCourseCode(original), original);
        }
        if (matches(normalized, "average grade", "grade average", "class average", "section average",
                "average mark")) {
            return adminGradeAverageAnswer(original);
        }
        if (matches(normalized, "suspended student", "students are suspended", "how many suspended")) {
            return "There are " + studentDao.findByStatus(StudentStatus.SUSPENDED).size() + " suspended student(s).";
        }
        if (matches(normalized, "graduated student", "students have graduated", "how many graduated")) {
            return "There are " + studentDao.findByStatus(StudentStatus.GRADUATED).size() + " graduated student(s).";
        }
        if (matches(normalized, "withdrawn student", "students withdrew", "how many withdrawn")) {
            return "There are " + studentDao.findByStatus(StudentStatus.WITHDRAWN).size() + " withdrawn student(s).";
        }
        if (matches(normalized, "active student", "how many students", "number of students", "total students")) {
            return "There are " + studentDao.findByStatus(StudentStatus.ACTIVE).size() + " active student(s).";
        }
        if (matches(normalized, "overdue", "unpaid invoice", "unpaid invoices", "outstanding payment",
                "outstanding invoices", "who owes")) {
            return adminOverdueInvoicesAnswer();
        }
        if (matches(normalized, "how many exams", "number of exams", "exams are scheduled", "exams this semester",
                "exam schedule")) {
            return adminExamsAnswer();
        }
        if (matches(normalized, "calendar", "holiday", "holidays", "university event", "academic calendar")) {
            return calendarNotesAnswer(null);
        }
        if (matches(normalized, "seat", "seats", "open section", "available section")) {
            return seatsAnswer(original);
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

    /** "What sections does Dr. Ahmad teach?" (admin) — any instructor's teaching load, current semester. */
    private String adminInstructorSectionsAnswer(Instructor instructor) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Section> sections = sectionDao.findByInstructorAndSemester(instructor.getInstructorId(),
                semester.getSemesterId());
        if (sections.isEmpty()) {
            return instructor.getFullName() + " is not teaching any sections in " + semester.getSemesterName() + ".";
        }
        StringBuilder sb = new StringBuilder(
                instructor.getFullName() + " is teaching in " + semester.getSemesterName() + ":\n");
        for (Section s : sections) {
            Course course = courseDao.findById(s.getCourseId()).orElse(null);
            String label = course == null ? "Course #" + s.getCourseId()
                    : course.getCourseCode() + " - " + course.getCourseTitle();
            sb.append("• ").append(label).append(" (Section ").append(s.getSectionNumber()).append(", ")
                    .append(s.getEnrolledCount()).append("/").append(s.getCapacity()).append(" students)\n");
        }
        return sb.toString().trim();
    }

    /**
     * "How many students are enrolled in CS101?" (admin) — every section of that course this
     * semester, or one specific section when the message names one (e.g. "CS101 section 2").
     */
    private String adminCourseEnrollmentAnswer(String courseCode, String message) {
        Course course = courseDao.findByCode(courseCode).orElse(null);
        if (course == null) {
            return "I couldn't find a course with the code " + courseCode + ".";
        }
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Section> sections = sectionDao.findByCourseAndSemester(course.getCourseId(), semester.getSemesterId());
        if (sections.isEmpty()) {
            return course.getCourseCode() + " has no sections offered in " + semester.getSemesterName() + ".";
        }
        String sectionNumber = extractSectionNumber(message);
        if (sectionNumber != null) {
            Section match = sections.stream()
                    .filter(s -> sectionNumber.equalsIgnoreCase(s.getSectionNumber()))
                    .findFirst().orElse(null);
            if (match == null) {
                return "I couldn't find " + course.getCourseCode() + " section " + sectionNumber
                        + " in " + semester.getSemesterName() + ".";
            }
            return course.getCourseCode() + " section " + match.getSectionNumber() + " has "
                    + match.getEnrolledCount() + " student(s) enrolled (capacity " + match.getCapacity() + ").";
        }
        if (sections.size() == 1) {
            Section s = sections.get(0);
            return course.getCourseCode() + " - " + course.getCourseTitle() + " has " + s.getEnrolledCount()
                    + " student(s) enrolled (capacity " + s.getCapacity() + ").";
        }
        int total = sections.stream().mapToInt(Section::getEnrolledCount).sum();
        StringBuilder sb = new StringBuilder(course.getCourseCode() + " - " + course.getCourseTitle()
                + " has " + total + " student(s) enrolled across " + sections.size() + " section(s) in "
                + semester.getSemesterName() + ":\n");
        for (Section s : sections) {
            sb.append("• Section ").append(s.getSectionNumber()).append(": ").append(s.getEnrolledCount())
                    .append("/").append(s.getCapacity()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * "What is the average grade in CS101?" (admin) — every section of that course this semester,
     * or one specific section when named; counts submitted grades only.
     */
    private String adminGradeAverageAnswer(String message) {
        String courseCode = extractCourseCode(message);
        if (courseCode == null) {
            return "Which course? For example, \"What is the average grade in CS101?\"";
        }
        Course course = courseDao.findByCode(courseCode).orElse(null);
        if (course == null) {
            return "I couldn't find a course with the code " + courseCode + ".";
        }
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Section> sections = sectionDao.findByCourseAndSemester(course.getCourseId(), semester.getSemesterId());
        if (sections.isEmpty()) {
            return course.getCourseCode() + " has no sections offered in " + semester.getSemesterName() + ".";
        }
        String sectionNumber = extractSectionNumber(message);
        if (sectionNumber != null) {
            sections = sections.stream().filter(s -> sectionNumber.equalsIgnoreCase(s.getSectionNumber())).toList();
            if (sections.isEmpty()) {
                return "I couldn't find " + course.getCourseCode() + " section " + sectionNumber
                        + " in " + semester.getSemesterName() + ".";
            }
        }
        return gradeAverageText(sections, course.getCourseCode() + " - " + course.getCourseTitle());
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
    // Shared: notifications, exams, tuition/payments, attendance, grade
    // component breakdown, recommendations, academic calendar, seats
    // ======================================================================

    /** "Show my notifications" — the asker's own unread inbox, any role. */
    private String notificationsAnswer(int userId) {
        List<Notification> unread = notificationService.unread(userId);
        if (unread.isEmpty()) {
            return "You have no unread notifications.";
        }
        StringBuilder sb = new StringBuilder("You have " + unread.size() + " unread notification(s):\n");
        int limit = Math.min(10, unread.size());
        for (int i = 0; i < limit; i++) {
            Notification n = unread.get(i);
            sb.append("• ").append(n.getTitle()).append(" — ").append(n.getMessage()).append("\n");
        }
        if (unread.size() > limit) {
            sb.append("… and ").append(unread.size() - limit).append(" more.\n");
        }
        return sb.toString().trim();
    }

    /** "When is my next exam?" — the student's own exams, current semester only. */
    private String studentExamsAnswer(int studentId) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Exam> exams = examService.examsForStudentInSemester(studentId, semester.getSemesterId());
        return examsListText(exams, "You have no exams scheduled this semester.");
    }

    /** "What exams do I have scheduled?" (instructor) — every exam of every section this instructor teaches. */
    private String instructorExamsAnswer(int instructorId) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Section> own = sectionDao.findByInstructorAndSemester(instructorId, semester.getSemesterId());
        List<Exam> exams = new ArrayList<>();
        for (Section s : own) {
            exams.addAll(examService.examsForSection(s.getSectionId()));
        }
        exams.sort(Comparator.comparing(Exam::getExamDate).thenComparing(Exam::getStartTime));
        return examsListText(exams, "You have no exams scheduled for your sections this semester.");
    }

    /** "How many exams are scheduled?" (admin) — every exam in the current semester. */
    private String adminExamsAnswer() {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Exam> exams = examService.examsForSemester(semester.getSemesterId());
        return examsListText(exams, "No exams are scheduled in " + semester.getSemesterName() + ".");
    }

    private String examsListText(List<Exam> exams, String noneMessage) {
        if (exams.isEmpty()) {
            return noneMessage;
        }
        StringBuilder sb = new StringBuilder("Exams:\n");
        for (Exam exam : exams) {
            Section section = sectionDao.findById(exam.getSectionId()).orElse(null);
            Course course = section == null ? null : courseDao.findById(section.getCourseId()).orElse(null);
            String label = course == null ? "Section " + exam.getSectionId()
                    : course.getCourseCode() + " - " + course.getCourseTitle();
            sb.append("• ").append(label).append(": ").append(DATE.format(exam.getExamDate()))
                    .append(" ").append(HM.format(exam.getStartTime()))
                    .append(" (").append(exam.getDurationMinutes()).append(" min)");
            if (exam.getRoom() != null && !exam.getRoom().isBlank()) {
                sb.append(", Room ").append(exam.getRoom());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * "What do I owe?" / "show my invoices" — the student's own bills and running balance, read
     * straight from the finance ledger. Deliberately never calls {@link TuitionService#billFor},
     * which lazily generates installments and notifications (a write) the first time it runs for a
     * semester — the AI assistant is read-only, so it only ever reads what already exists.
     */
    private String studentTuitionAnswer(int studentId) {
        List<StudentInvoice> invoices = financeService.invoicesOf(studentId);
        if (invoices.isEmpty()) {
            return "You have no invoices on file.";
        }
        BigDecimal balance = financeService.balanceOf(studentId);
        StringBuilder sb = new StringBuilder("Your account balance is " + balance + ".\n");
        List<StudentInvoice> unpaid = invoices.stream()
                .filter(i -> i.getStatus() != InvoiceStatus.PAID)
                .toList();
        if (unpaid.isEmpty()) {
            sb.append("All your invoices are fully paid.");
        } else {
            sb.append("Outstanding invoices:\n");
            for (StudentInvoice invoice : unpaid) {
                sb.append("• ").append(invoice.getInvoiceNumber()).append(": ")
                        .append(invoice.getRemainingAmount()).append(" remaining (")
                        .append(invoice.getStatus().getLabel()).append("), due ")
                        .append(DATE.format(invoice.getDueDate())).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** "How many absences do I have in CS101?" — one course if named, otherwise every current course. */
    private String studentAttendanceAnswer(int studentId, String message) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Enrollment> enrolled = registrationService.currentRegistrations(studentId, semester.getSemesterId());
        if (enrolled.isEmpty()) {
            return "You are not enrolled in any courses this semester.";
        }
        String courseCode = extractCourseCode(message);
        if (courseCode != null) {
            Course course = courseDao.findByCode(courseCode).orElse(null);
            if (course == null) {
                return "I couldn't find a course with the code " + courseCode + ".";
            }
            Enrollment target = enrolled.stream()
                    .filter(e -> {
                        Course c = courseOfSection(e.getSectionId());
                        return c != null && c.getCourseId() == course.getCourseId();
                    })
                    .findFirst().orElse(null);
            if (target == null) {
                return "You are not enrolled in " + courseCode + " this semester.";
            }
            int absences = attendanceService.countAbsencesForEnrollment(target.getEnrollmentId());
            return "You have " + absences + " recorded absence(s) in " + course.getCourseCode()
                    + " - " + course.getCourseTitle() + " this semester.";
        }
        StringBuilder sb = new StringBuilder("Your absences this semester:\n");
        for (Enrollment e : enrolled) {
            Course course = courseOfSection(e.getSectionId());
            if (course == null) {
                continue;
            }
            int absences = attendanceService.countAbsencesForEnrollment(e.getEnrollmentId());
            sb.append("• ").append(course.getCourseCode()).append(": ").append(absences).append(" absence(s)\n");
        }
        return sb.toString().trim();
    }

    /** "How many absences does CS101 have?" (instructor) — own section(s) only. */
    private String instructorAttendanceAnswer(int instructorId, String message) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        String courseCode = extractCourseCode(message);
        List<Section> own = sectionDao.findByInstructorAndSemester(instructorId, semester.getSemesterId());
        List<Section> targets = own;
        if (courseCode != null) {
            Course course = courseDao.findByCode(courseCode).orElse(null);
            if (course == null) {
                return "I couldn't find a course with the code " + courseCode + ".";
            }
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
            Course course = courseDao.findById(s.getCourseId()).orElse(null);
            String label = course == null ? "Section " + s.getSectionNumber()
                    : course.getCourseCode() + "-" + s.getSectionNumber();
            List<AttendanceRow> summary = attendanceService.getSectionAttendanceSummary(s.getSectionId());
            sb.append(label).append(" attendance:\n");
            for (AttendanceRow row : summary) {
                sb.append("  • ").append(row.getStudentName()).append(": ")
                        .append(row.getTotalAbsences()).append(" absence(s)\n");
            }
        }
        return sb.toString().trim();
    }

    /** "What is my midterm grade in CS101?" — the component marks on file, released ones only. */
    private String gradeBreakdownAnswer(int studentId, String message) {
        String courseCode = extractCourseCode(message);
        if (courseCode == null) {
            return "Which course? For example, \"What is my midterm grade in CS101?\"";
        }
        List<StudentGradeRow> rows = academicService.gradeRows(studentId, null);
        StudentGradeRow row = rows.stream()
                .filter(r -> courseCode.equalsIgnoreCase(r.getCourseCode()))
                .findFirst().orElse(null);
        if (row == null) {
            return "I couldn't find " + courseCode + " on your record.";
        }
        StringBuilder sb = new StringBuilder(row.getCourseCode() + " - " + row.getCourseTitle() + ":\n");
        if (row.isHasLab()) {
            sb.append("• Lab: ").append(markText(row.getLabMark())).append("\n");
        } else {
            sb.append("• Coursework: ").append(markText(row.getCourseworkMark())).append("\n");
        }
        sb.append("• Midterm: ").append(markText(row.getMidtermMark())).append("\n");
        sb.append("• Final: ").append(markText(row.getFinalMark())).append("\n");
        if (row.getTotalMark() != null) {
            sb.append("• Total: ").append(row.getTotalMark()).append(" (")
                    .append(row.getLetterGrade() == null ? "—" : row.getLetterGrade().getLabel()).append(")\n");
        }
        return sb.toString().trim();
    }

    private String markText(BigDecimal mark) {
        return mark == null ? "not released yet" : mark.stripTrailingZeros().toPlainString();
    }

    /** "What course should I take next?" — the same read-only recommendation engine the Recommendations screen uses. */
    private String recommendationsAnswer(int studentId) {
        RecommendationResult result = recommendationService.recommend(studentId);
        if (result.isBlocked()) {
            return result.blockedReason;
        }
        if (result.recommendations.isEmpty()) {
            return "There are no course recommendations available for you right now.";
        }
        StringBuilder sb = new StringBuilder("Top course recommendations for you:\n");
        int limit = Math.min(5, result.recommendations.size());
        for (int i = 0; i < limit; i++) {
            Recommendation r = result.recommendations.get(i);
            sb.append("• ").append(r.courseCode).append(" - ").append(r.courseTitle)
                    .append(" (Score ").append(r.finalScore).append("/")
                    .append(RecommendationService.MAX_FINAL_SCORE).append(")\n");
        }
        return sb.toString().trim();
    }

    /** "What's on the academic calendar?" / "any holidays?" — {@code studentId} null for the admin-wide view. */
    private String calendarNotesAnswer(Integer studentId) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<String> notes = academicCalendarService.notesForSemester(semester.getSemesterId(), studentId);
        if (notes.isEmpty()) {
            return "There is nothing on the academic calendar yet.";
        }
        StringBuilder sb = new StringBuilder("Academic calendar for " + semester.getSemesterName() + ":\n");
        for (String note : notes) {
            sb.append("• ").append(note).append("\n");
        }
        return sb.toString().trim();
    }

    /** "How many seats are open in CS101?" — public catalogue information, any signed-in role. */
    private String seatsAnswer(String message) {
        String courseCode = extractCourseCode(message);
        if (courseCode == null) {
            return "Which course? For example, \"How many seats are open in CS101?\"";
        }
        Course course = courseDao.findByCode(courseCode).orElse(null);
        if (course == null) {
            return "I couldn't find a course with the code " + courseCode + ".";
        }
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return "There is no current semester set up yet.";
        }
        List<Section> sections = sectionDao.findByCourseAndSemester(course.getCourseId(), semester.getSemesterId());
        if (sections.isEmpty()) {
            return course.getCourseCode() + " has no sections offered in " + semester.getSemesterName() + ".";
        }
        StringBuilder sb = new StringBuilder(course.getCourseCode() + " - " + course.getCourseTitle()
                + " sections in " + semester.getSemesterName() + ":\n");
        for (Section s : sections) {
            int free = s.getCapacity() - s.getEnrolledCount();
            sb.append("• Section ").append(s.getSectionNumber()).append(": ").append(s.getEnrolledCount())
                    .append("/").append(s.getCapacity()).append(" (").append(free).append(" seat(s) free)\n");
        }
        return sb.toString().trim();
    }

    /** "Which invoices are overdue?" (admin) — the finance office's own worklist. */
    private String adminOverdueInvoicesAnswer() {
        List<StudentInvoice> overdue = financeService.overdueInvoices();
        if (overdue.isEmpty()) {
            return "No invoices are currently overdue.";
        }
        StringBuilder sb = new StringBuilder(overdue.size() + " overdue invoice(s):\n");
        int limit = Math.min(15, overdue.size());
        for (int i = 0; i < limit; i++) {
            StudentInvoice invoice = overdue.get(i);
            Student student = studentDao.findById(invoice.getStudentId()).orElse(null);
            String who = student == null ? "Student #" + invoice.getStudentId() : student.getFullName();
            sb.append("• ").append(who).append(" — ").append(invoice.getInvoiceNumber()).append(": ")
                    .append(invoice.getRemainingAmount()).append(" remaining, due ")
                    .append(DATE.format(invoice.getDueDate())).append("\n");
        }
        if (overdue.size() > limit) {
            sb.append("… and ").append(overdue.size() - limit).append(" more.\n");
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

    /** "section 01", "section #2" — null when the message names no specific section. */
    private String extractSectionNumber(String message) {
        Matcher m = SECTION_NUMBER_PATTERN.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The average of every submitted total mark across {@code sections} — a draft grade is never
     * counted, matching the rule that a student must never see (and this average must never be
     * skewed by) a mark the instructor has not submitted yet.
     */
    private String gradeAverageText(List<Section> sections, String courseLabel) {
        List<BigDecimal> totals = new ArrayList<>();
        for (Section s : sections) {
            for (GradeSheetRow row : gradeDao.findSectionRoster(s.getSectionId())) {
                if (row.isSubmitted() && row.getTotalMark() != null) {
                    totals.add(row.getTotalMark());
                }
            }
        }
        if (totals.isEmpty()) {
            return "No submitted grades are on file yet for " + courseLabel + ".";
        }
        BigDecimal sum = totals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(totals.size()), 2, RoundingMode.HALF_UP);
        return "The average grade in " + courseLabel + " is " + average.stripTrailingZeros().toPlainString()
                + " across " + totals.size() + " submitted grade(s).";
    }

    // ======================================================================
    // Shared: person ID lookup by name ("What is the ID of Noor Younis?")
    // ======================================================================

    /**
     * "What is the ID of Noor Younis?" / "What is Noor Younis's instructor ID?" — resolves a
     * Student or Instructor ID by name from the current database. Never guesses: a name that
     * matches more than one person (or matches both a student and an instructor with no role
     * stated) is turned back to the asker to clarify, and a name matching nobody on file is
     * reported as not found. Returns {@link #NOT_SUPPORTED} when the message isn't an ID-lookup
     * question at all, so callers fall through to the rest of the normal intent handling.
     *
     * <p>Permission is scoped the same way as every other lookup in this class: a student may
     * only confirm their own Student ID, an instructor may only look up Student IDs for students
     * actually in their own current-semester sections, and instructor-to-instructor ID lookups
     * are refused for non-admins (mirroring the existing "another instructor" refusal). Admins are
     * unrestricted, as everywhere else in this class.</p>
     */
    private String personIdLookupAnswer(String normalized, Session session) {
        String name = extractIdLookupName(normalized);
        if (name == null) {
            return NOT_SUPPORTED;
        }
        String roleHint = extractIdLookupRoleHint(normalized);
        List<Student> studentMatches = "instructor".equals(roleHint) ? List.of() : findStudentsByName(name);
        List<Instructor> instructorMatches = "student".equals(roleHint) ? List.of() : findInstructorsByName(name);
        int total = studentMatches.size() + instructorMatches.size();
        if (total == 0) {
            return "I couldn't find a student or instructor record matching that name.";
        }
        if (total > 1) {
            return "More than one record matches that name. Could you clarify whether you mean the "
                    + "Student or the Instructor, and which one specifically?";
        }
        UserRole role = session.getRole();
        if (!studentMatches.isEmpty()) {
            Student student = studentMatches.get(0);
            if (!canRevealStudentId(role, session, student)) {
                return UNAUTHORIZED;
            }
            return student.getFullName() + " — Student ID: " + student.getUserId();
        }
        Instructor instructor = instructorMatches.get(0);
        if (!canRevealInstructorId(role, session, instructor)) {
            return UNAUTHORIZED;
        }
        return instructor.getFullName() + " — Instructor ID: " + instructor.getUserId();
    }

    /**
     * The name text from an "id of NAME" or "NAME's ... id" question, or null when the message
     * isn't asking for anyone's ID. The captured text may include a few leading filler words
     * (e.g. "what is noor younis" instead of "noor younis") in the possessive form — harmless,
     * since {@link #fullNameMatches} only ever checks whether a real name is contained within it,
     * never exact equality, and the raw text is never echoed back to the user.
     */
    private static String extractIdLookupName(String normalized) {
        Matcher m = ID_OF_NAME_PATTERN.matcher(normalized);
        if (m.find()) {
            String name = m.group(2).trim();
            return name.length() >= 3 ? name : null;
        }
        m = NAME_POSSESSIVE_ID_PATTERN.matcher(normalized);
        if (m.find()) {
            String name = m.group(1).trim();
            return name.length() >= 3 ? name : null;
        }
        return null;
    }

    /** "student" or "instructor" when the question names a role explicitly, otherwise null. */
    private static String extractIdLookupRoleHint(String normalized) {
        Matcher m = ID_OF_NAME_PATTERN.matcher(normalized);
        if (m.find() && m.group(1) != null) {
            return m.group(1);
        }
        m = NAME_POSSESSIVE_ID_PATTERN.matcher(normalized);
        if (m.find() && m.group(2) != null) {
            return m.group(2);
        }
        return null;
    }

    private boolean canRevealStudentId(UserRole role, Session session, Student student) {
        return switch (role) {
            case ADMIN -> true;
            case STUDENT -> session.getStudent() != null
                    && session.getStudent().getStudentId() == student.getStudentId();
            case INSTRUCTOR -> session.getInstructor() != null
                    && isStudentTaughtByInstructor(session.getInstructor().getInstructorId(), student.getStudentId());
        };
    }

    /** Instructor contact/profile info is already visible to any signed-in student — see {@link #instructorContactAnswer}. */
    private boolean canRevealInstructorId(UserRole role, Session session, Instructor instructor) {
        return switch (role) {
            case ADMIN -> true;
            case STUDENT -> true;
            case INSTRUCTOR -> session.getInstructor() != null
                    && session.getInstructor().getInstructorId() == instructor.getInstructorId();
        };
    }

    private boolean isStudentTaughtByInstructor(int instructorId, int studentId) {
        Semester semester = semesterService.getCurrentSemester();
        if (semester == null) {
            return false;
        }
        return sectionDao.findByInstructorAndSemester(instructorId, semester.getSemesterId()).stream()
                .flatMap(s -> enrollmentDao.findActiveBySection(s.getSectionId()).stream())
                .anyMatch(e -> e.getStudentId() == studentId);
    }

    private List<Student> findStudentsByName(String capturedName) {
        List<Student> matches = new ArrayList<>();
        for (Student s : studentDao.findAll()) {
            if (fullNameMatches(capturedName, s.getFirstName(), s.getLastName())) {
                matches.add(s);
            }
        }
        return matches;
    }

    private List<Instructor> findInstructorsByName(String capturedName) {
        List<Instructor> matches = new ArrayList<>();
        for (Instructor i : instructorDao.findAll()) {
            if (fullNameMatches(capturedName, i.getFirstName(), i.getLastName())) {
                matches.add(i);
            }
        }
        return matches;
    }

    private static boolean fullNameMatches(String capturedName, String firstName, String lastName) {
        String full = (lower(firstName) + " " + lower(lastName)).trim();
        return full.length() >= 3 && capturedName.contains(full);
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

