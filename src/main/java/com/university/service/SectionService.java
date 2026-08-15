package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.CampusDAO;
import com.university.dao.CourseDAO;
import com.university.dao.DataAccessException;
import com.university.dao.EnrollmentDAO;
import com.university.dao.InstructorDAO;
import com.university.dao.SectionDAO;
import com.university.dao.SectionScheduleDAO;
import com.university.enums.SectionStatus;
import com.university.model.Campus;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.util.ValidationUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything about {@code sections} and {@code section_schedules}.
 *
 * <p>Implements project_details.md Section 6.7:</p>
 * <ul>
 *   <li><b>S1</b> an instructor cannot teach two sections that overlap in time</li>
 *   <li><b>S2</b> a room cannot host two sections that overlap in time</li>
 *   <li><b>S3</b> capacity cannot be set below the current {@code enrolled_count}</li>
 *   <li><b>S4</b> a section with enrollments is CANCELLED, never deleted</li>
 * </ul>
 *
 * <p>The overlap test (Section 6.3) is written once and shared by S1 and S2:
 * {@link SectionScheduleDAO}'s clash queries use it in SQL, and
 * {@link SectionSchedule#clashesWith} uses the identical strict-inequality
 * formula for the in-memory self-overlap check (two proposed meetings of the
 * same section). {@code sections.enrolled_count} is never written here — the
 * database trigger {@code trg_Enrollment_UpdateCount} owns it (Section 4.10).</p>
 */
public class SectionService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final SectionDAO sectionDao = new SectionDAO();
    private final SectionScheduleDAO scheduleDao = new SectionScheduleDAO();
    private final CourseDAO courseDao = new CourseDAO();
    private final InstructorDAO instructorDao = new InstructorDAO();
    private final EnrollmentDAO enrollmentDao = new EnrollmentDAO();
    private final CampusDAO campusDao = new CampusDAO();

    /** Gives access to the connection helpers without exposing a whole data access object. */
    private final AbstractDAO transactions = new AbstractDAO() {
    };

    // ------------------------------------------------------------------ read

    /**
     * @param semesterId   null = every semester
     * @param courseId     null = every course
     * @param instructorId null = every instructor
     * @param status       null = every status
     */
    public List<Section> searchSections(Integer semesterId, Integer courseId, Integer instructorId, SectionStatus status) {
        List<Section> base = semesterId != null ? sectionDao.findBySemester(semesterId) : sectionDao.findAll();
        return base.stream()
                .filter(s -> courseId == null || s.getCourseId() == courseId)
                .filter(s -> instructorId == null || (s.getInstructorId() != null && s.getInstructorId() == instructorId))
                .filter(s -> status == null || s.getStatus() == status)
                .toList();
    }

    public Section findById(int sectionId) {
        return requireSection(sectionId);
    }

    /** The weekly meetings of one section, in timetable order (Sunday first). */
    public List<SectionSchedule> listMeetings(int sectionId) {
        List<SectionSchedule> meetings = new ArrayList<>(scheduleDao.findBySection(sectionId));
        meetings.sort(Comparator.<SectionSchedule>comparingInt(m -> m.getDayOfWeek().ordinal())
                .thenComparing((SectionSchedule m) -> m.getStartTime()));
        return meetings;
    }

    /** Every section a student is enrolled in for one semester, in a single round trip. */
    public List<Section> listForStudent(int studentId, int semesterId) {
        return sectionDao.findByStudentAndSemester(studentId, semesterId);
    }

    /**
     * The weekly meetings of every section a student is enrolled in for one semester, grouped by
     * section id — one round trip instead of one {@link #listMeetings} call per section.
     */
    public java.util.Map<Integer, List<SectionSchedule>> listMeetingsForStudent(int studentId, int semesterId) {
        return scheduleDao.findByStudentAndSemester(studentId, semesterId).stream()
                .collect(java.util.stream.Collectors.groupingBy(SectionSchedule::getSectionId));
    }

    /**
     * The weekly meetings of every section one instructor teaches in one semester, grouped by
     * section id — one round trip instead of one {@link #listMeetings} call per section.
     */
    public java.util.Map<Integer, List<SectionSchedule>> listMeetingsForInstructor(int instructorId, int semesterId) {
        return scheduleDao.findByInstructorAndSemester(instructorId, semesterId).stream()
                .collect(java.util.stream.Collectors.groupingBy(SectionSchedule::getSectionId));
    }

    public int countEnrollments(int sectionId) {
        return enrollmentDao.findBySection(sectionId).size();
    }

    public boolean sectionNumberExists(int courseId, int semesterId, String sectionNumber, Integer excludeSectionId) {
        return sectionDao.findByCourseAndSemester(courseId, semesterId).stream()
                .anyMatch(s -> s.getSectionNumber().equalsIgnoreCase(sectionNumber.trim())
                        && (excludeSectionId == null || s.getSectionId() != excludeSectionId));
    }

    public List<Campus> listCampuses() {
        return campusDao.findAll();
    }

    // ----------------------------------------------------------------- write

    /**
     * Creates or updates one section together with its whole weekly schedule.
     *
     * Order of checks (do not reorder — the cheap ones come first):
     *   1. field validation
     *   2. UNIQUE(course_id, semester_id, section_number)
     *   3. S3  capacity &gt;= enrolled_count            (edit only)
     *   4. the proposed meetings must not overlap EACH OTHER
     *   5. S1  instructor clash
     *   6. S2  room clash
     * The write itself (the section row plus a full rewrite of its meetings) runs in one
     * transaction, once every check above has already passed.
     *
     * @return the section id
     */
    public int saveSection(Section section, List<SectionSchedule> meetings) {
        boolean isNew = section.getSectionId() <= 0;
        int excludeId = isNew ? -1 : section.getSectionId();

        validateFields(section, meetings);

        if (sectionNumberExists(section.getCourseId(), section.getSemesterId(), section.getSectionNumber(),
                isNew ? null : section.getSectionId())) {
            throw new ValidationException("Section " + section.getSectionNumber().trim()
                    + " of this course already exists in this semester.");
        }

        if (!isNew) {
            Section existing = requireSection(section.getSectionId());
            if (section.getCapacity() < existing.getEnrolledCount()) {
                throw new ValidationException("Capacity cannot be less than the "
                        + existing.getEnrolledCount() + " student(s) already enrolled.");
            }
        }

        for (int i = 0; i < meetings.size(); i++) {
            for (int j = i + 1; j < meetings.size(); j++) {
                if (meetings.get(i).clashesWith(meetings.get(j))) {
                    throw new ValidationException("Two meetings of this section overlap: "
                            + label(meetings.get(i)) + " and " + label(meetings.get(j)) + ".");
                }
            }
        }

        if (section.getInstructorId() != null) {
            for (SectionSchedule m : meetings) {
                checkInstructorClash(section.getInstructorId(), section.getSemesterId(), excludeId, m);
            }
        }
        if (ValidationUtil.notBlank(section.getRoom())) {
            for (SectionSchedule m : meetings) {
                checkRoomClash(section.getRoom().trim(), section.getSemesterId(), excludeId, m);
            }
        }

        Connection connection = transactions.beginTransaction();
        try {
            int sectionId;
            if (isNew) {
                section.setEnrolledCount(0);
                sectionId = sectionDao.insert(connection, section);
            } else {
                sectionDao.update(connection, section);
                sectionId = section.getSectionId();
            }

            // Delete-then-insert is correct here: a schedule row carries no history and nothing
            // references it, which keeps the editor logic trivial.
            scheduleDao.deleteBySection(connection, sectionId);
            for (SectionSchedule m : meetings) {
                m.setSectionId(sectionId);
                scheduleDao.insert(connection, m);
            }

            connection.commit();
            return sectionId;

        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new DataAccessException("Could not save the section.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    private void validateFields(Section section, List<SectionSchedule> meetings) {
        if (ValidationUtil.isBlank(section.getSectionNumber())) {
            throw new ValidationException("Section number is required, for example 01.");
        }
        if (!ValidationUtil.maxLength(section.getSectionNumber(), 10)) {
            throw new ValidationException("Section number must be 10 characters or fewer.");
        }
        if (section.getCapacity() <= 0) {
            throw new ValidationException("Capacity must be greater than zero.");
        }
        if (ValidationUtil.notBlank(section.getRoom()) && !ValidationUtil.maxLength(section.getRoom(), 20)) {
            throw new ValidationException("Room must be 20 characters or fewer.");
        }
        if (meetings == null || meetings.isEmpty()) {
            throw new ValidationException("Add at least one weekly meeting. Without meeting times the "
                    + "timetable and the conflict rules cannot work.");
        }
    }

    // ============================================================ S1 AND S2

    private void checkInstructorClash(int instructorId, int semesterId, int excludeSectionId, SectionSchedule m) {
        scheduleDao.findInstructorClash(instructorId, semesterId, excludeSectionId,
                m.getDayOfWeek(), m.getStartTime(), m.getEndTime()).ifPresent(clash -> {
            throw new ValidationException(instructorNameOf(instructorId) + " already teaches "
                    + sectionLabelOf(clash.getSectionId()) + " on " + clash.getDayOfWeek().name()
                    + " at " + timeRange(clash.getStartTime(), clash.getEndTime()) + ".");
        });
    }

    private void checkRoomClash(String room, int semesterId, int excludeSectionId, SectionSchedule m) {
        scheduleDao.findRoomClash(room, semesterId, excludeSectionId,
                m.getDayOfWeek(), m.getStartTime(), m.getEndTime()).ifPresent(clash -> {
            throw new ValidationException("Room " + room + " is already used by "
                    + sectionLabelOf(clash.getSectionId()) + " on " + clash.getDayOfWeek().name()
                    + " at " + timeRange(clash.getStartTime(), clash.getEndTime()) + ".");
        });
    }

    private String sectionLabelOf(int sectionId) {
        return sectionDao.findById(sectionId)
                .map(s -> courseDao.findById(s.getCourseId()).map(c -> c.getCourseCode()).orElse("?")
                        + "-" + s.getSectionNumber())
                .orElse("#" + sectionId);
    }

    private String instructorNameOf(int instructorId) {
        return instructorDao.findById(instructorId)
                .map(i -> i.getFirstName() + " " + i.getLastName())
                .orElse("That instructor");
    }

    private String timeRange(LocalTime start, LocalTime end) {
        return HM.format(start) + "-" + HM.format(end);
    }

    private String label(SectionSchedule m) {
        return m.getDayOfWeek().name() + " " + timeRange(m.getStartTime(), m.getEndTime());
    }

    // ============================================================ S4 AND STATUS

    /** RULE S4 — cancelling keeps the history. This is the only "delete" the UI offers by default. */
    public void cancelSection(int sectionId) {
        sectionDao.setStatus(sectionId, SectionStatus.CANCELLED);
    }

    public void reopenSection(int sectionId) {
        sectionDao.setStatus(sectionId, SectionStatus.OPEN);
    }

    // ================================================================== HELPERS

    private Section requireSection(int sectionId) {
        ValidationException.requireId(sectionId, "Section");
        return sectionDao.findById(sectionId)
                .orElseThrow(() -> new ServiceException("That section no longer exists."));
    }
}
