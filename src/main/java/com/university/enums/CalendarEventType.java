package com.university.enums;

/**
 * The fixed set of colors the Academic Calendar (Student "My Calendar", Admin
 * "Academic Calendar") paints a date with. Every type has one fixed color —
 * nobody, including Admin, picks a color freely — so the legend always means
 * the same thing everywhere it appears.
 *
 * <p>{@link #SEMESTER} marks a semester's own start/end date, derived
 * straight from {@code dbo.semesters} — it is never a choice on the Admin
 * event form, only something the calendar renders on its own. Every other
 * constant here also matches an {@code event_type} value {@code CK_calendar_events_type}
 * (migration 0015) accepts for an Admin-authored {@code dbo.calendar_events} row.</p>
 */
public enum CalendarEventType {

    SEMESTER("Semester Start/End", "cal-semester"),
    REGISTRATION("Registration", "cal-registration"),
    ADD_DROP("Add/Drop", "cal-add-drop"),
    WITHDRAW("Withdraw Deadline", "cal-withdraw"),
    EXAM("Exams", "cal-exam"),
    PAYMENT("Payment", "cal-payment"),
    HOLIDAY("Holiday", "cal-holiday"),
    UNIVERSITY_EVENT("University Event", "cal-university-event"),
    OTHER("Other", "cal-other");

    private final String label;
    private final String cssClass;

    CalendarEventType(String label, String cssClass) {
        this.label = label;
        this.cssClass = cssClass;
    }

    /** The text shown in the legend and the Admin event form. */
    public String getLabel() {
        return label;
    }

    /** The app.css class that fills a calendar cell/legend swatch with this type's fixed color. */
    public String getCssClass() {
        return cssClass;
    }

    /** The exact string stored in {@code dbo.calendar_events.event_type}. */
    public String toDb() {
        return name();
    }

    /**
     * Converts a database value into a constant.
     *
     * @param value the column value, may be null
     * @return the matching constant, or null when the column is NULL
     */
    public static CalendarEventType fromDb(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }

    @Override
    public String toString() {
        return label;
    }
}
