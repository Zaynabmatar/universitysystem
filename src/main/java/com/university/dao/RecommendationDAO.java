package com.university.dao;

import com.university.service.RecommendationService.Attempt;
import com.university.service.RecommendationService.Candidate;
import com.university.service.RecommendationService.CourseStats;
import com.university.service.RecommendationService.Meeting;
import com.university.service.RecommendationService.PassedCourse;
import com.university.service.RecommendationService.Prereq;
import com.university.service.RecommendationService.StudentProfile;

import java.math.BigDecimal;
import java.sql.Time;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every read the recommendation engine makes. **Read-only** — this class contains no
 * {@code INSERT}, {@code UPDATE} or {@code DELETE}, and the AI screen never writes.
 *
 * <p><b>Adaptation note.</b> project_details.md Section 9 reads {@code vw_CourseStatistics}
 * and {@code vw_SectionDetails}. This database has no views, so both are expressed here as
 * ordinary parameterised queries producing the same columns:</p>
 * <ul>
 *   <li>{@code unlocks_count} — how many courses list this one as a prerequisite (Factor 3);</li>
 *   <li>{@code avg_grade_points} / {@code times_taken} — the course's historical difficulty
 *       (Factor 4), {@code NULL} when nobody has ever completed it;</li>
 *   <li>the open sections of the current semester, with seats and instructor.</li>
 * </ul>
 *
 * <p><b>Every method runs once per recommendation run.</b> Nothing here is called per candidate —
 * that is what keeps a full run to a handful of queries instead of an N+1 storm.</p>
 *
 * <p>The grade filter is the same one {@code GradeDAO} uses for the GPA: completed, submitted,
 * points present, and never a {@code W} or an {@code I}.</p>
 */
public class RecommendationDAO extends AbstractDAO {

    /** Completed, published, points present, not a W or an I — the one definition of "graded". */
    private static final String GRADED =
            "e.status = 'COMPLETED' AND g.is_submitted = 1 "
          + "AND g.grade_points IS NOT NULL AND g.letter_grade NOT IN ('W', 'I')";

    /** R2's input: a recommendation screen is only for an active student. */
    public Optional<String> findStudentStatus(int studentId) {
        return queryOne("SELECT status FROM dbo.students WHERE student_id = ?",
                resultSet -> resultSet.getNString("status"), studentId);
    }

    /** The numbers Factors 2 and 4 are built from. */
    public Optional<StudentProfile> findProfile(int studentId) {
        return queryOne(
                "SELECT s.student_id, s.status, s.cumulative_gpa, s.completed_credits, "
              + "       s.program_id, p.program_name, p.total_credits_required "
              + "FROM dbo.students s "
              + "INNER JOIN dbo.programs p ON p.program_id = s.program_id "
              + "WHERE s.student_id = ?",
                resultSet -> {
                    StudentProfile profile   = new StudentProfile();
                    profile.studentId        = resultSet.getInt("student_id");
                    profile.status           = resultSet.getNString("status");
                    profile.gpa              = orZero(resultSet.getBigDecimal("cumulative_gpa"));
                    profile.completedCredits = resultSet.getInt("completed_credits");
                    profile.programId        = resultSet.getInt("program_id");
                    profile.programName      = resultSet.getNString("program_name");
                    profile.creditsRequired  = resultSet.getInt("total_credits_required");
                    return profile;
                }, studentId);
    }

    /** R3's input: every prerequisite edge in the catalogue, keyed by the course that needs it. */
    public Map<Integer, List<Prereq>> loadAllPrerequisites() {
        Map<Integer, List<Prereq>> map = new HashMap<>();
        queryList("SELECT cp.course_id, cp.prerequisite_course_id, cp.min_grade_points, "
                + "       pc.course_code "
                + "FROM dbo.course_prerequisites cp "
                + "INNER JOIN dbo.courses pc ON pc.course_id = cp.prerequisite_course_id",
                resultSet -> {
                    Prereq prereq = new Prereq();
                    prereq.courseId             = resultSet.getInt("course_id");
                    prereq.prerequisiteCourseId = resultSet.getInt("prerequisite_course_id");
                    prereq.prerequisiteCode     = resultSet.getNString("course_code");
                    prereq.minPoints            = orZero(resultSet.getBigDecimal("min_grade_points"));
                    return prereq;
                }).forEach(prereq ->
                        map.computeIfAbsent(prereq.courseId, key -> new ArrayList<>()).add(prereq));
        return map;
    }

    /** R3 and R4's input: the student's best passing grade in each course they have passed. */
    public Map<Integer, PassedCourse> loadMyBestPassedGrades(int studentId) {
        Map<Integer, PassedCourse> map = new HashMap<>();
        queryList("SELECT s.course_id, co.course_code, g.grade_points, g.letter_grade "
                + "FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s  ON s.section_id  = e.section_id "
                + "INNER JOIN dbo.courses co  ON co.course_id  = s.course_id "
                + "INNER JOIN dbo.grades g    ON g.enrollment_id = e.enrollment_id "
                + "WHERE e.student_id = ? AND " + GRADED + " AND g.grade_points >= 1.00",
                resultSet -> {
                    PassedCourse passed = new PassedCourse();
                    passed.courseId    = resultSet.getInt("course_id");
                    passed.courseCode  = resultSet.getNString("course_code");
                    passed.letterGrade = resultSet.getNString("letter_grade");
                    passed.points      = orZero(resultSet.getBigDecimal("grade_points"));
                    return passed;
                }, studentId).forEach(passed -> {
                    PassedCourse current = map.get(passed.courseId);
                    if (current == null || passed.points.compareTo(current.points) > 0) {
                        map.put(passed.courseId, passed);   // keep the best attempt
                    }
                });
        return map;
    }

    /**
     * R4 and R7's input: fills {@code enrolledNow} with the course ids the student is sitting in
     * right now and returns the credits those carry.
     */
    public int loadCurrentEnrolment(int studentId, Set<Integer> enrolledNow) {
        int[] credits = { 0 };
        queryList("SELECT s.course_id, co.credits "
                + "FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s    ON s.section_id   = e.section_id "
                + "INNER JOIN dbo.semesters sem ON sem.semester_id = s.semester_id "
                + "                            AND sem.is_current = 1 "
                + "INNER JOIN dbo.courses co    ON co.course_id   = s.course_id "
                + "WHERE e.student_id = ? AND e.status = 'ENROLLED'",
                resultSet -> {
                    enrolledNow.add(resultSet.getInt("course_id"));
                    credits[0] += resultSet.getInt("credits");
                    return Boolean.TRUE;
                }, studentId);
        return credits[0];
    }

    /** R6's input: the meetings the student already has this semester. */
    public List<Meeting> loadMyMeetings(int studentId) {
        return queryList("SELECT sch.section_id, sch.day_of_week, sch.start_time, sch.end_time "
                + "FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s    ON s.section_id   = e.section_id "
                + "INNER JOIN dbo.semesters sem ON sem.semester_id = s.semester_id "
                + "                            AND sem.is_current = 1 "
                + "INNER JOIN dbo.section_schedules sch ON sch.section_id = s.section_id "
                + "WHERE e.student_id = ? AND e.status = 'ENROLLED'",
                RecommendationDAO::readMeeting, studentId);
    }

    /** R6's input: every meeting of every section running this semester. */
    public Map<Integer, List<Meeting>> loadAllSectionMeetings() {
        Map<Integer, List<Meeting>> map = new HashMap<>();
        queryList("SELECT sch.section_id, sch.day_of_week, sch.start_time, sch.end_time "
                + "FROM dbo.section_schedules sch "
                + "INNER JOIN dbo.sections s    ON s.section_id   = sch.section_id "
                + "INNER JOIN dbo.semesters sem ON sem.semester_id = s.semester_id "
                + "                            AND sem.is_current = 1",
                RecommendationDAO::readMeeting)
                .forEach(meeting ->
                        map.computeIfAbsent(meeting.sectionId, key -> new ArrayList<>()).add(meeting));
        return map;
    }

    /**
     * Factors 3 and 4's input — what {@code vw_CourseStatistics} would have supplied.
     *
     * <p>Every course in the catalogue appears, so the catalogue-wide {@code MAX(unlocks_count)}
     * that Factor 3 divides by is computed over the same population the view would have used.
     * {@code avg_grade_points} is {@code NULL} for a course nobody has completed — Factor 4
     * turns that into the neutral 8.</p>
     */
    public Map<Integer, CourseStats> loadCourseStatistics() {
        Map<Integer, CourseStats> map = new HashMap<>();
        queryList("SELECT c.course_id, "
                + "       ISNULL(u.unlocks_count, 0) AS unlocks_count, "
                + "       a.avg_grade_points, "
                + "       ISNULL(a.times_taken, 0) AS times_taken "
                + "FROM dbo.courses c "
                + "LEFT JOIN (SELECT cp.prerequisite_course_id AS cid, COUNT(*) AS unlocks_count "
                + "           FROM dbo.course_prerequisites cp "
                + "           GROUP BY cp.prerequisite_course_id) u ON u.cid = c.course_id "
                + "LEFT JOIN (SELECT s.course_id AS cid, COUNT(*) AS times_taken, "
                + "                  AVG(g.grade_points) AS avg_grade_points "
                + "           FROM dbo.grades g "
                + "           INNER JOIN dbo.enrollments e ON e.enrollment_id = g.enrollment_id "
                + "           INNER JOIN dbo.sections s    ON s.section_id    = e.section_id "
                + "           WHERE " + GRADED + " "
                + "           GROUP BY s.course_id) a ON a.cid = c.course_id",
                resultSet -> {
                    CourseStats stats   = new CourseStats();
                    stats.courseId      = resultSet.getInt("course_id");
                    stats.unlocksCount  = resultSet.getInt("unlocks_count");
                    stats.avgGradePoints = resultSet.getBigDecimal("avg_grade_points");  // may be null
                    stats.timesTaken    = resultSet.getInt("times_taken");
                    return stats;
                }).forEach(stats -> map.put(stats.courseId, stats));
        return map;
    }

    /** Factor 3's wording: for each course, the codes of the courses it unlocks, in code order. */
    public Map<Integer, List<String>> loadUnlockedCourseCodes() {
        record Unlock(int prerequisiteCourseId, String courseCode) { }

        Map<Integer, List<String>> map = new HashMap<>();
        queryList("SELECT cp.prerequisite_course_id, co.course_code "
                + "FROM dbo.course_prerequisites cp "
                + "INNER JOIN dbo.courses co ON co.course_id = cp.course_id "
                + "ORDER BY co.course_code",
                resultSet -> new Unlock(resultSet.getInt("prerequisite_course_id"),
                                        resultSet.getNString("course_code")))
                .forEach(unlock -> map
                        .computeIfAbsent(unlock.prerequisiteCourseId(), key -> new ArrayList<>())
                        .add(unlock.courseCode()));
        return map;
    }

    /** Layer 3's only input: every graded attempt by every student. */
    public List<Attempt> loadAllGradedAttempts() {
        return queryList("SELECT e.student_id, s.course_id, g.grade_points "
                + "FROM dbo.enrollments e "
                + "INNER JOIN dbo.sections s ON s.section_id    = e.section_id "
                + "INNER JOIN dbo.grades g   ON g.enrollment_id = e.enrollment_id "
                + "WHERE " + GRADED,
                resultSet -> {
                    Attempt attempt  = new Attempt();
                    attempt.studentId = resultSet.getInt("student_id");
                    attempt.courseId  = resultSet.getInt("course_id");
                    attempt.points    = orZero(resultSet.getBigDecimal("grade_points"));
                    return attempt;
                });
    }

    /**
     * The candidate set — what {@code vw_SectionDetails} would have supplied: every OPEN section
     * of every active course in the current semester.
     *
     * <p>{@code scheduleText} is left blank here and filled in by the service from the meetings
     * it already loads, so no second query and no {@code STRING_AGG} is needed.</p>
     */
    public List<Candidate> loadOpenSections() {
        return queryList("SELECT sec.section_id, sec.section_number, sec.course_id, "
                + "       c.course_code, c.course_title, c.credits, "
                + "       CASE WHEN i.instructor_id IS NULL THEN NULL "
                + "            ELSE i.first_name + N' ' + i.last_name END AS instructor_name, "
                + "       sec.room, sec.capacity, sec.enrolled_count "
                + "FROM dbo.sections sec "
                + "INNER JOIN dbo.semesters sem ON sem.semester_id = sec.semester_id "
                + "                            AND sem.is_current = 1 "
                + "INNER JOIN dbo.courses c     ON c.course_id     = sec.course_id "
                + "                            AND c.is_active     = 1 "
                + "LEFT JOIN  dbo.instructors i ON i.instructor_id = sec.instructor_id "
                + "WHERE sec.status = 'OPEN' "
                + "ORDER BY c.course_code, sec.section_number",
                resultSet -> {
                    Candidate candidate    = new Candidate();
                    candidate.sectionId     = resultSet.getInt("section_id");
                    candidate.sectionNumber = resultSet.getNString("section_number");
                    candidate.courseId      = resultSet.getInt("course_id");
                    candidate.courseCode    = resultSet.getNString("course_code");
                    candidate.courseTitle   = resultSet.getNString("course_title");
                    candidate.credits       = resultSet.getInt("credits");
                    candidate.instructorName = resultSet.getNString("instructor_name");
                    candidate.room          = resultSet.getNString("room");
                    candidate.capacity      = resultSet.getInt("capacity");
                    candidate.enrolledCount = resultSet.getInt("enrolled_count");
                    candidate.seatsAvailable = candidate.capacity - candidate.enrolledCount;
                    return candidate;
                });
    }

    private static Meeting readMeeting(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        Meeting meeting   = new Meeting();
        meeting.sectionId = resultSet.getInt("section_id");
        meeting.day       = resultSet.getNString("day_of_week");
        Time start        = resultSet.getTime("start_time");
        Time end          = resultSet.getTime("end_time");
        meeting.start     = start.toLocalTime();
        meeting.end       = end.toLocalTime();
        return meeting;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
