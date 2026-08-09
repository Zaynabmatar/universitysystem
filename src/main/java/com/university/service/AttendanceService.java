package com.university.service;

import com.university.dao.AbstractDAO;
import com.university.dao.AttendanceDAO;
import com.university.dao.DataAccessException;
import com.university.dao.SectionDAO;
import com.university.dao.SectionScheduleDAO;
import com.university.dao.SemesterDAO;
import com.university.enums.DayOfWeekCode;
import com.university.model.AttendanceRecord;
import com.university.model.AttendanceRow;
import com.university.model.Section;
import com.university.model.SectionSchedule;
import com.university.model.Semester;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything about instructor attendance-taking.
 *
 * <p>Rule G1-style ownership check (mirrors {@code SectionService}/{@code GradeService}): every
 * entry point here takes the acting instructor's id and refuses a section that is not theirs,
 * even though the screen itself only ever offers the instructor their own sections. A class date
 * is only ever valid when it falls on one of the section's {@code section_schedules} weekdays
 * within its semester's {@code start_date}/{@code end_date} window — there is no per-meeting date
 * stored anywhere, so that window is recomputed here on every check rather than cached.</p>
 */
public class AttendanceService {

    private final AttendanceDAO attendanceDao = new AttendanceDAO();
    private final SectionDAO sectionDao = new SectionDAO();
    private final SectionScheduleDAO scheduleDao = new SectionScheduleDAO();
    private final SemesterDAO semesterDao = new SemesterDAO();

    /** Gives access to the connection helpers without exposing a whole data access object. */
    private final AbstractDAO transactions = new AbstractDAO() {
    };

    // ------------------------------------------------------------------ read

    /**
     * Every calendar date this section actually meets, in order — the semester's
     * {@code start_date}..{@code end_date} window filtered to the weekdays the section is
     * scheduled on. Used to drive the calendar's highlighted/selectable days.
     */
    public List<LocalDate> listScheduledDates(int sectionId) {
        Section section = requireSection(sectionId);
        Semester semester = requireSemester(section.getSemesterId());
        List<SectionSchedule> meetings = scheduleDao.findBySection(sectionId);
        if (meetings.isEmpty() || semester.getStartDate() == null || semester.getEndDate() == null) {
            return List.of();
        }

        Set<DayOfWeek> weekdays = new HashSet<>();
        for (SectionSchedule meeting : meetings) {
            weekdays.add(meeting.getDayOfWeek().toJavaDayOfWeek());
        }

        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate day = semester.getStartDate(); !day.isAfter(semester.getEndDate()); day = day.plusDays(1)) {
            if (weekdays.contains(day.getDayOfWeek())) {
                dates.add(day);
            }
        }
        return dates;
    }

    /** True only when {@code classDate} is one of this section's real scheduled meetings. */
    public boolean isValidClassDate(int sectionId, LocalDate classDate) {
        if (classDate == null) {
            return false;
        }
        Section section = requireSection(sectionId);
        Semester semester = requireSemester(section.getSemesterId());
        if (semester.getStartDate() == null || semester.getEndDate() == null) {
            return false;
        }
        if (classDate.isBefore(semester.getStartDate()) || classDate.isAfter(semester.getEndDate())) {
            return false;
        }
        DayOfWeekCode code = DayOfWeekCode.fromJavaDayOfWeek(classDate.getDayOfWeek());
        return scheduleDao.findBySection(sectionId).stream()
                .anyMatch(meeting -> meeting.getDayOfWeek() == code);
    }

    /**
     * The attendance sheet for one section on one date: every officially enrolled student,
     * defaulted to PRESENT unless a status was already saved for that exact date.
     *
     * @throws ValidationException when the section is not this instructor's, or the date is not
     *                              one of this section's real scheduled meetings
     */
    public List<AttendanceRow> getRoster(int instructorId, int sectionId, LocalDate classDate) {
        requireOwnedSection(instructorId, sectionId);
        requireValidClassDate(sectionId, classDate);
        return attendanceDao.findRosterForDate(sectionId, classDate);
    }

    // ----------------------------------------------------------------- write

    /**
     * Saves every row of the sheet for one section/date, one student at a time: updates the
     * existing attendance row for that student on that date if one exists, otherwise inserts one.
     * Never creates a duplicate — {@link AttendanceDAO#upsert} and the database's unique
     * constraint both guard the same (enrollment, date) pair.
     */
    public void saveAttendance(int instructorId, int sectionId, LocalDate classDate,
                                List<AttendanceRow> rows, int recordedByUserId) {
        requireOwnedSection(instructorId, sectionId);
        requireValidClassDate(sectionId, classDate);
        if (rows == null || rows.isEmpty()) {
            throw new ValidationException("There are no students to save attendance for.");
        }

        Connection connection = transactions.beginTransaction();
        try {
            for (AttendanceRow row : rows) {
                AttendanceRecord record = new AttendanceRecord();
                record.setEnrollmentId(row.getEnrollmentId());
                record.setSectionId(sectionId);
                record.setClassDate(classDate);
                record.setStatus(row.getStatus());
                record.setRecordedBy(recordedByUserId);
                attendanceDao.upsert(connection, record);
            }
            connection.commit();
        } catch (SQLException e) {
            transactions.rollbackQuietly(connection);
            throw new DataAccessException("Could not save attendance.", e);
        } catch (RuntimeException e) {
            transactions.rollbackQuietly(connection);
            throw e;
        } finally {
            transactions.closeQuietly(connection);
        }
    }

    // ================================================================== HELPERS

    private void requireValidClassDate(int sectionId, LocalDate classDate) {
        if (!isValidClassDate(sectionId, classDate)) {
            throw new ValidationException("This section has no class scheduled on "
                    + (classDate == null ? "that date" : classDate) + ".");
        }
    }

    /** RULE — an instructor may only take attendance for a section that is actually theirs. */
    private Section requireOwnedSection(int instructorId, int sectionId) {
        Section section = requireSection(sectionId);
        if (section.getInstructorId() == null || section.getInstructorId() != instructorId) {
            throw new ValidationException("That section does not belong to you.");
        }
        return section;
    }

    private Section requireSection(int sectionId) {
        ValidationException.requireId(sectionId, "Section");
        return sectionDao.findById(sectionId)
                .orElseThrow(() -> new ServiceException("That section no longer exists."));
    }

    private Semester requireSemester(int semesterId) {
        return semesterDao.findById(semesterId)
                .orElseThrow(() -> new ServiceException("That semester no longer exists."));
    }
}
