package com.university.controller;

import com.university.enums.CalendarEventType;
import com.university.enums.UserRole;
import com.university.model.Semester;
import com.university.service.AcademicCalendarService;
import com.university.service.AcademicCalendarService.Entry;
import com.university.service.SemesterService;
import com.university.service.Session;
import com.university.util.AlertUtil;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "My Calendar" — a strictly view-only semester calendar reached from the account dropdown
 * (My Account &gt; My Calendar). Every date shown comes straight from the database through
 * {@link AcademicCalendarService}: this screen only lays the week table out and paints it.
 */
public class InstructorAcademicCalendarController {

    private static final List<CalendarEventType> PRIORITY = AcademicCalendarService.DAY_PRIORITY;

    @FXML private ComboBox<Semester> semesterCombo;
    @FXML private VBox calendarBox;
    @FXML private VBox legendBox;
    @FXML private VBox notesBox;

    private final SemesterService semesterService = new SemesterService();
    private final AcademicCalendarService calendarService = new AcademicCalendarService();

    private List<Entry> currentEntries = List.of();

    @FXML
    private void initialize() {
        Session.current().requireRole(UserRole.INSTRUCTOR);

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
            AlertUtil.error("My Calendar", "The semester list could not be loaded.", e);
        }
    }

    private void reload(Semester semester) {
        try {
            currentEntries = calendarService.entriesForSemester(semester.getSemesterId(), null, false);
            buildCalendar(semester);
            buildLegend();
            buildNotes(calendarService.notesForSemester(semester.getSemesterId(), null));
        } catch (RuntimeException e) {
            currentEntries = List.of();
            AlertUtil.error("My Calendar", "Your calendar could not be loaded.", e);
        }
    }

    // ------------------------------------------------------------------ calendar table

    private void buildCalendar(Semester semester) {
        calendarBox.getChildren().clear();
        if (semester.getStartDate() == null || semester.getEndDate() == null) {
            return;
        }
        var weeks = calendarService.weeksForSemester(semester, currentEntries);
        calendarBox.getChildren().add(CalendarWeekTable.build(weeks, currentEntries, PRIORITY, null));
    }

    // ------------------------------------------------------------------ legend

    private void buildLegend() {
        legendBox.getChildren().clear();
        Label title = new Label("Legend");
        title.getStyleClass().add("legend-title");
        legendBox.getChildren().add(title);

        Set<CalendarEventType> used = new LinkedHashSet<>();
        for (CalendarEventType type : PRIORITY) {
            boolean present = currentEntries.stream().anyMatch(e -> e.type() == type);
            if (present) {
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
        for (String note : new ArrayList<>(notes)) {
            Label line = new Label("•  " + note);
            line.getStyleClass().add("calendar-note-line");
            line.setWrapText(true);
            notesBox.getChildren().add(line);
        }
    }
}
