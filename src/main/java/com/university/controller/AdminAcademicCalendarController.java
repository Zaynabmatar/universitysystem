package com.university.controller;

import com.university.controller.dialog.CalendarEventFormDialog;
import com.university.enums.CalendarEventType;
import com.university.enums.UserRole;
import com.university.model.CalendarEvent;
import com.university.model.Semester;
import com.university.service.AcademicCalendarService;
import com.university.service.AcademicCalendarService.Entry;
import com.university.service.CalendarEventService;
import com.university.service.SemesterService;
import com.university.service.ServiceException;
import com.university.service.Session;
import com.university.util.AlertUtil;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Admin's "Academic Calendar" screen: the same combined calendar the Student sees, plus full
 * Add/Edit/Delete/Deactivate control over the custom events {@link CalendarEventService} owns.
 * Every other date on the grid (semester dates, registration, drop/withdraw, exams, payment due
 * dates) is read-only here too — it keeps coming from its own screen (Manage Semesters, Sections,
 * Invoicing), never from this one.
 */
public class AdminAcademicCalendarController {

    private static final List<CalendarEventType> PRIORITY = AcademicCalendarService.DAY_PRIORITY;

    @FXML private ComboBox<Semester> semesterCombo;
    @FXML private VBox calendarBox;
    @FXML private VBox legendBox;
    @FXML private VBox notesBox;

    private final SemesterService semesterService = new SemesterService();
    private final AcademicCalendarService academicCalendarService = new AcademicCalendarService();
    private final CalendarEventService calendarEventService = new CalendarEventService();

    private Semester currentSemester;
    private List<Entry> visibleEntries = List.of();
    private List<CalendarEvent> customEvents = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.ADMIN);

        semesterCombo.setCellFactory(list -> semesterCell());
        semesterCombo.setButtonCell(semesterCell());
        semesterCombo.valueProperty().addListener((obs, old, chosen) -> {
            if (chosen != null) {
                reload(chosen);
            }
        });

        fillSemesterChooser();
    }

    private javafx.scene.control.ListCell<Semester> semesterCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Semester s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.getSemesterName());
            }
        };
    }


    // ------------------------------------------------------------------ loading

    private void fillSemesterChooser() {
        try {
            List<Semester> semesters = semesterService.listAll();
            semesterCombo.setItems(FXCollections.observableArrayList(semesters));
            Semester current = semesterService.getCurrentSemester();
            Semester toSelect = current != null ? current : semesters.isEmpty() ? null : semesters.get(0);
            if (toSelect != null) {
                semesterCombo.setValue(toSelect);
                reload(toSelect);
            } else {
                calendarBox.getChildren().setAll(new Label("No semester has been set up yet."));
            }
        } catch (RuntimeException e) {
            AlertUtil.error("Academic Calendar", "The semester list could not be loaded.", e);
        }
    }

    private void reload(Semester semester) {
        currentSemester = semester;
        try {
            int semesterId = semester.getSemesterId();
            List<Entry> all = academicCalendarService.entriesForSemester(semesterId, null, true);
            visibleEntries = all.stream().filter(Entry::active).toList();
            customEvents = calendarEventService.listForSemester(semesterId);

            buildCalendar(semester);
            buildLegend();
            buildNotes(academicCalendarService.notesForSemester(semesterId, null));
        } catch (RuntimeException e) {
            visibleEntries = List.of();
            customEvents = List.of();
            AlertUtil.error("Academic Calendar", "The calendar could not be loaded.", e);
        }
    }


    // ------------------------------------------------------------------ calendar table

    private void buildCalendar(Semester semester) {
        calendarBox.getChildren().clear();
        if (semester.getStartDate() == null || semester.getEndDate() == null) {
            return;
        }
        var weeks = academicCalendarService.weeksForSemester(semester, visibleEntries);
        calendarBox.getChildren().add(
                CalendarWeekTable.build(weeks, visibleEntries, PRIORITY, this::onDayClicked));
    }

    private void onDayClicked(LocalDate date) {
        Optional<CalendarEvent> hit = customEvents.stream()
                .filter(CalendarEvent::isActive)
                .filter(ev -> !date.isBefore(ev.getStartDate()) && !date.isAfter(ev.getEndDate()))
                .findFirst();
        if (hit.isPresent()) {
            openEditDialog(hit.get());
        } else {
            openAddDialog(date);
        }
    }

    // ------------------------------------------------------------------ legend

    private void buildLegend() {
        legendBox.getChildren().clear();
        Label title = new Label("Legend");
        title.getStyleClass().add("legend-title");
        legendBox.getChildren().add(title);

        Set<CalendarEventType> used = new LinkedHashSet<>();
        for (CalendarEventType type : PRIORITY) {
            if (visibleEntries.stream().anyMatch(e -> e.type() == type)) {
                used.add(type);
            }
        }
        if (used.isEmpty()) {
            Label none = new Label("Nothing scheduled yet.");
            none.getStyleClass().add("muted-text");
            legendBox.getChildren().add(none);
            return;
        }
        for (CalendarEventType type : used) {
            Region swatch = new Region();
            swatch.getStyleClass().addAll("legend-swatch", type.getCssClass());
            Label label = new Label(type.getLabel());
            label.getStyleClass().add("legend-label");
            HBox row = new HBox(8, swatch, label);
            row.getStyleClass().add("legend-row");
            legendBox.getChildren().add(row);
        }
    }

    // ------------------------------------------------------------------ notes

    private void buildNotes(List<String> notes) {
        notesBox.getChildren().clear();
        if (notes.isEmpty()) {
            Label none = new Label("No academic notes for this semester yet.");
            none.getStyleClass().add("muted-text");
            notesBox.getChildren().add(none);
            return;
        }
        for (String note : notes) {
            Label line = new Label("•  " + note);
            line.getStyleClass().add("calendar-note-line");
            line.setWrapText(true);
            notesBox.getChildren().add(line);
        }
    }

    // ------------------------------------------------------------------ actions

    private void openAddDialog(LocalDate date) {
        Optional<CalendarEvent> result = new CalendarEventFormDialog(currentSemester, null)
                .withDate(date).showAndWait();
        if (result.isEmpty()) return;
        try {
            int adminUserId = Session.current().getUser().getUserId();
            calendarEventService.create(result.get(), adminUserId);
            AlertUtil.success("Event added", "The calendar event was created.");
            reload(currentSemester);
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot save the event", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The event could not be created.", e);
        }
    }

    private void openEditDialog(CalendarEvent event) {
        Optional<CalendarEvent> result = new CalendarEventFormDialog(currentSemester, event).showAndWait();
        if (result.isEmpty()) return;
        try {
            calendarEventService.update(result.get());
            AlertUtil.success("Saved", "The event's details were updated.");
            reload(currentSemester);
        } catch (ServiceException se) {
            AlertUtil.warn("Cannot save the event", se.getMessage());
        } catch (Exception e) {
            AlertUtil.error("Could not save", "The event could not be updated.", e);
        }
    }
}
