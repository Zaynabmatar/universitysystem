package com.university.service;

import com.university.dao.ExamDAO;
import com.university.model.Exam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Scheduled exams: an instructor announces one for a section, and every student currently
 * enrolled in that section sees it — see {@link ExamDAO#findByStudent}.
 */
public class ExamService {

    private final ExamDAO examDao = new ExamDAO();

    public int createExam(int sectionId, LocalDate examDate, LocalTime startTime,
                           int durationMinutes, String room, Integer createdByUserId) {
        if (examDate == null) {
            throw new ServiceException("An exam date is required.");
        }
        if (startTime == null) {
            throw new ServiceException("A start time is required.");
        }
        if (durationMinutes <= 0) {
            throw new ServiceException("Duration must be greater than zero.");
        }
        assertNotInPast(examDate, startTime);

        Exam exam = new Exam();
        exam.setSectionId(sectionId);
        exam.setExamDate(examDate);
        exam.setStartTime(startTime);
        exam.setDurationMinutes(durationMinutes);
        exam.setRoom(room);
        exam.setCreatedBy(createdByUserId);
        return examDao.insert(exam);
    }

    /** Every exam scheduled for one section, for the instructor's own screens. */
    public List<Exam> examsForSection(int sectionId) {
        return examDao.findBySection(sectionId);
    }

    /** Every exam belonging to a section this student is enrolled in, across every semester. */
    public List<Exam> examsForStudent(int studentId) {
        return examDao.findByStudent(studentId);
    }

    /** Every exam scheduled anywhere in one semester — the Admin Academic Calendar's view. */
    public List<Exam> examsForSemester(int semesterId) {
        return examDao.findBySemester(semesterId);
    }

    /** One student's exams, restricted to one semester — the Student Academic Calendar's view. */
    public List<Exam> examsForStudentInSemester(int studentId, int semesterId) {
        return examDao.findByStudentAndSemester(studentId, semesterId);
    }

    public boolean updateExam(int examId, LocalDate examDate, LocalTime startTime,
                              int durationMinutes, String room) {
        if (examId <= 0) {
            throw new ServiceException("A valid exam is required.");
        }
        if (examDate == null) {
            throw new ServiceException("An exam date is required.");
        }
        if (startTime == null) {
            throw new ServiceException("A start time is required.");
        }
        if (durationMinutes <= 0) {
            throw new ServiceException("Duration must be greater than zero.");
        }
        assertNotInPast(examDate, startTime);

        Exam exam = examDao.findById(examId)
                .orElseThrow(() -> new ServiceException("Exam not found."));

        exam.setExamDate(examDate);
        exam.setStartTime(startTime);
        exam.setDurationMinutes(durationMinutes);
        exam.setRoom(room);

        return examDao.update(exam);
    }

    /**
     * An exam may never be scheduled in the past — checked here so that a caller other than the
     * date picker (or a picker with no day-disabling of its own) cannot save one anyway. Today at
     * an earlier clock time is still "in the past"; today at a later time is fine.
     */
    private void assertNotInPast(LocalDate examDate, LocalTime startTime) {
        if (!LocalDateTime.of(examDate, startTime).isAfter(LocalDateTime.now())) {
            throw new ServiceException("The exam date and time must be in the future.");
        }
    }
}

