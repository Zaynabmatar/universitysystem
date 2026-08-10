package com.university.service;

import com.university.dao.CalendarEventDAO;
import com.university.dao.SemesterDAO;
import com.university.enums.CalendarEventType;
import com.university.model.CalendarEvent;
import com.university.model.Semester;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin-authored Academic Calendar entries — everything {@code dbo.calendar_events} holds.
 *
 * <p>This is deliberately the only place that writes that table. Every other date the calendar
 * shows (semester dates, registration, drop/withdraw, exams, payment due dates) is owned by its
 * own service and is never duplicated into a row here — see {@link AcademicCalendarService},
 * which reads this service alongside those others to build one combined view.</p>
 */
public class CalendarEventService {

    private static final int TITLE_MAX_LENGTH = 150;
    private static final int DESCRIPTION_MAX_LENGTH = 500;

    private final CalendarEventDAO eventDao = new CalendarEventDAO();
    private final SemesterDAO semesterDao = new SemesterDAO();

    /** Every custom event for one semester, active or not — Admin's management table. */
    public List<CalendarEvent> listForSemester(int semesterId) {
        return eventDao.findBySemester(semesterId);
    }

    /** Only the active custom events for one semester — the read-only Student calendar. */
    public List<CalendarEvent> listActiveForSemester(int semesterId) {
        return eventDao.findActiveBySemester(semesterId);
    }

    /**
     * Creates a custom event.
     *
     * @throws ServiceException when a field is missing/invalid, or the dates make no sense
     */
    public int create(CalendarEvent event, Integer createdByUserId) {
        validate(event);
        event.setActive(true);
        event.setCreatedBy(createdByUserId);
        return eventDao.insert(event);
    }

    public void update(CalendarEvent event) {
        ValidationException.requireId(event.getEventId(), "Event");
        validate(event);
        requireEvent(event.getEventId());
        event.setUpdatedAt(LocalDateTime.now());
        eventDao.update(event);
    }

    /** Hides the event from the Student calendar without losing it — the reversible removal. */
    public void deactivate(int eventId) {
        requireEvent(eventId);
        eventDao.setActive(eventId, false, LocalDateTime.now());
    }

    /** Brings a deactivated event back. */
    public void reactivate(int eventId) {
        requireEvent(eventId);
        eventDao.setActive(eventId, true, LocalDateTime.now());
    }

    /** Removes the event outright — the irreversible one, for a mistake rather than a retired date. */
    public void delete(int eventId) {
        requireEvent(eventId);
        eventDao.deleteById(eventId);
    }

    private void validate(CalendarEvent event) {
        ValidationException.requireId(event.getSemesterId(), "Semester");
        ValidationException.requireText(event.getTitle(), "Title");
        if (event.getTitle().trim().length() > TITLE_MAX_LENGTH) {
            throw new ValidationException("Title must be " + TITLE_MAX_LENGTH + " characters or fewer.");
        }
        ValidationException.requireNonNull(event.getType(), "Event type");
        if (event.getType() == CalendarEventType.SEMESTER) {
            throw new ValidationException(
                    "Semester Start/End is drawn from the semester itself, not a custom event.");
        }
        ValidationException.requireNonNull(event.getStartDate(), "Start date");
        ValidationException.requireNonNull(event.getEndDate(), "End date");
        if (event.getEndDate().isBefore(event.getStartDate())) {
            throw new ValidationException("The end date cannot be before the start date.");
        }
        if (event.getDescription() != null && event.getDescription().length() > DESCRIPTION_MAX_LENGTH) {
            throw new ValidationException("Description must be " + DESCRIPTION_MAX_LENGTH + " characters or fewer.");
        }

        Semester semester = semesterDao.findById(event.getSemesterId())
                .orElseThrow(() -> new ServiceException("That semester no longer exists."));
        LocalDate start = event.getStartDate();
        LocalDate end = event.getEndDate();
        if (start.isBefore(semester.getStartDate()) || end.isAfter(semester.getEndDate())) {
            throw new ValidationException("The event dates must fall within " + semester.getSemesterName()
                    + " (" + semester.getStartDate() + " to " + semester.getEndDate() + ").");
        }
    }

    private CalendarEvent requireEvent(int eventId) {
        return eventDao.findById(eventId)
                .orElseThrow(() -> new ServiceException("That calendar event no longer exists."));
    }
}
