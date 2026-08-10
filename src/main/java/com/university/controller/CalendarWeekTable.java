package com.university.controller;

import com.university.enums.CalendarEventType;
import com.university.service.AcademicCalendarService.DayCell;
import com.university.service.AcademicCalendarService.Entry;
import com.university.service.AcademicCalendarService.WeekRow;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds the single semester week table both the Student "My Calendar" and Admin "Academic
 * Calendar" screens render: {@code Week | Mon | Tue | Wed | Thu | Fri | Sat | Remarks}, one row
 * per Monday-Saturday week of the semester (see {@link com.university.service.AcademicCalendarService#weeksForSemester}).
 *
 * <p>Every day cell is painted from the same {@link Entry} list both controllers already read
 * from {@code AcademicCalendarService}; an optional click handler is the only thing that differs
 * between the read-only Student screen ({@code onDayClick == null}) and the editable Admin one.</p>
 */
final class CalendarWeekTable {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("d MMM");
    private static final String[] HEADERS = {"Week", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Remarks"};

    private CalendarWeekTable() {
    }

    /**
     * @param weeks       the semester's week rows, from {@code AcademicCalendarService.weeksForSemester}
     * @param entries     every entry currently visible on the calendar, used to paint each day cell
     * @param priority    highest-priority type first; decides which type paints a day cell that has more than one
     * @param onDayClick  null for the read-only Student calendar; for Admin, called with the clicked
     *                    in-semester date so it can open the add/edit dialog
     */
    static Node build(List<WeekRow> weeks, List<Entry> entries, List<CalendarEventType> priority,
                       Consumer<LocalDate> onDayClick) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("week-table");
        grid.setMaxWidth(Double.MAX_VALUE);
        grid.getColumnConstraints().addAll(columnConstraints());

        RowConstraints headerRow = new RowConstraints();
        headerRow.setMinHeight(32);
        grid.getRowConstraints().add(headerRow);
        for (int c = 0; c < HEADERS.length; c++) {
            Label header = new Label(HEADERS[c]);
            header.getStyleClass().addAll("week-table-cell", "week-table-header",
                    c == 0 ? "week-table-header-week" : c == 7 ? "week-table-header-remarks" : "week-table-header-day");
            header.setMaxWidth(Double.MAX_VALUE);
            header.setAlignment(Pos.CENTER);
            grid.add(header, c, 0);
        }

        int row = 1;
        for (WeekRow week : weeks) {
            RowConstraints rc = new RowConstraints();
            rc.setMinHeight(46);
            grid.getRowConstraints().add(rc);

            Label weekLabel = new Label(String.valueOf(week.weekNumber()));
            weekLabel.getStyleClass().addAll("week-table-cell", "week-number-cell");
            weekLabel.setMaxWidth(Double.MAX_VALUE);
            weekLabel.setMaxHeight(Double.MAX_VALUE);
            weekLabel.setAlignment(Pos.CENTER);
            grid.add(weekLabel, 0, row);

            List<DayCell> days = week.days();
            for (int d = 0; d < days.size(); d++) {
                grid.add(buildDayCell(days.get(d), entries, priority, onDayClick), d + 1, row);
            }

            grid.add(buildRemarksCell(week.remarks()), 7, row);
            row++;
        }

        return grid;
    }

    private static List<ColumnConstraints> columnConstraints() {
        ColumnConstraints week = new ColumnConstraints();
        week.setPercentWidth(6);
        ColumnConstraints remarks = new ColumnConstraints();
        remarks.setPercentWidth(37);
        double dayWidth = (100 - 6 - 37) / 6.0;

        List<ColumnConstraints> cols = new ArrayList<>();
        cols.add(week);
        for (int i = 0; i < 6; i++) {
            ColumnConstraints day = new ColumnConstraints();
            day.setPercentWidth(dayWidth);
            cols.add(day);
        }
        cols.add(remarks);
        return cols;
    }

    private static Node buildDayCell(DayCell day, List<Entry> entries, List<CalendarEventType> priority,
                                      Consumer<LocalDate> onDayClick) {
        LocalDate date = day.date();
        List<Entry> onThisDay = entries.stream()
                .filter(e -> e.includes(date))
                .sorted(Comparator.comparing(e -> priority.indexOf(e.type())))
                .toList();

        Label dateLabel = new Label(DAY_FMT.format(date));
        dateLabel.getStyleClass().add("week-day-number");
        if (!day.inSemester()) {
            dateLabel.getStyleClass().add("week-day-out-of-range");
        }

        VBox cell = new VBox(2, dateLabel);
        cell.getStyleClass().addAll("week-table-cell", "week-day-cell");
        cell.setAlignment(Pos.TOP_CENTER);
        cell.setMaxWidth(Double.MAX_VALUE);
        cell.setMaxHeight(Double.MAX_VALUE);

        if (!onThisDay.isEmpty()) {
            dateLabel.getStyleClass().add(onThisDay.get(0).type().getCssClass());
            if (!onThisDay.get(0).active()) {
                dateLabel.getStyleClass().add("week-day-inactive");
            }

            Set<CalendarEventType> extraTypes = new LinkedHashSet<>();
            for (int i = 1; i < onThisDay.size(); i++) {
                extraTypes.add(onThisDay.get(i).type());
            }
            if (!extraTypes.isEmpty()) {
                HBox dots = new HBox(2);
                dots.getStyleClass().add("calendar-day-dot-row");
                for (CalendarEventType type : extraTypes) {
                    Region dot = new Region();
                    dot.getStyleClass().addAll("calendar-day-dot", type.getCssClass());
                    dots.getChildren().add(dot);
                }
                cell.getChildren().add(dots);
            }

            StringBuilder tip = new StringBuilder();
            for (Entry e : onThisDay) {
                if (tip.length() > 0) tip.append("\n");
                tip.append(e.type().getLabel()).append(": ").append(e.title());
            }
            if (onDayClick != null && day.inSemester()) {
                tip.append(onThisDay.stream().anyMatch(Entry::isCustom)
                        ? "\n\nClick to edit." : "\n\nClick to add a new event on this date.");
            }
            Tooltip.install(cell, new Tooltip(tip.toString()));
        } else if (onDayClick != null && day.inSemester()) {
            Tooltip.install(cell, new Tooltip("Click to add a new event on this date."));
        }

        if (onDayClick != null && day.inSemester()) {
            cell.getStyleClass().add("week-day-editable");
            cell.setOnMouseClicked(e -> onDayClick.accept(date));
        }

        return cell;
    }

    private static Node buildRemarksCell(List<Entry> remarks) {
        VBox box = new VBox(3);
        box.getStyleClass().addAll("week-table-cell", "week-remarks-cell");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);
        for (Entry remark : remarks) {
            Label line = new Label(remark.title());
            line.getStyleClass().add("week-remarks-line");
            line.setWrapText(true);
            box.getChildren().add(line);
        }
        return box;
    }
}
