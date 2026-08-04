package com.university.util;

import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The ONE place in the project that writes a CSV file.
 *
 * <p>Two things matter and both are easy to get wrong. First, every text column in this
 * database is NVARCHAR so that Arabic works; Microsoft Excel assumes a CSV is in the local
 * Windows code page unless the file starts with a UTF-8 byte-order mark, so without it Arabic
 * names open as mojibake. Second, RFC 4180 quoting: a value containing a comma, a double quote
 * or a newline is wrapped in double quotes, and any internal double quote is doubled — without
 * this, one course title with a comma silently shifts every following column by one.</p>
 */
public final class CsvExporter {

    private CsvExporter() { }

    /**
     * Asks the user where to save, then writes the file.
     *
     * @return the file that was written, or null if the user cancelled or the write failed.
     *         Callers show their own success / failure popup.
     */
    public static File export(Window owner, String suggestedFileName,
                               List<String> headers, List<List<String>> rows) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export to CSV");
        fc.setInitialFileName(sanitiseFileName(suggestedFileName));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV file (*.csv)", "*.csv"));

        File file = fc.showSaveDialog(owner);
        if (file == null) {
            return null;
        }
        if (!file.getName().toLowerCase().endsWith(".csv")) {
            file = new File(file.getAbsolutePath() + ".csv");
        }

        try (PrintWriter out = new PrintWriter(file, StandardCharsets.UTF_8)) {
            out.print('﻿');
            out.println(joinRow(headers));
            for (List<String> row : rows) {
                out.println(joinRow(row));
            }
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Exports any TableView exactly as it appears on screen: the visible columns, in their
     * current order, with the same text the user can see.
     */
    public static <T> File exportTable(TableView<T> table, Window owner, String suggestedFileName) {
        List<String> headers = new ArrayList<>();
        List<TableColumn<T, ?>> columns = new ArrayList<>();
        for (TableColumn<T, ?> col : table.getColumns()) {
            if (!col.isVisible()) {
                continue;
            }
            columns.add(col);
            headers.add(col.getText() == null ? "" : col.getText());
        }

        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < table.getItems().size(); i++) {
            List<String> row = new ArrayList<>();
            for (TableColumn<T, ?> col : columns) {
                row.add(cell(col.getCellData(i)));
            }
            rows.add(row);
        }
        return export(owner, suggestedFileName, headers, rows);
    }

    /** null becomes an empty field — never the four letters "null". */
    public static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (value instanceof Double d) {
            return String.format("%.2f", d);
        }
        if (value instanceof LocalDate ld) {
            return ld.toString();
        }
        if (value instanceof LocalDateTime t) {
            return t.toString().replace('T', ' ');
        }
        return String.valueOf(value);
    }

    private static String joinRow(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(values.get(i)));
        }
        return sb.toString();
    }

    /** RFC 4180: quote when the value contains a comma, a quote, or a line break. */
    private static String quote(String v) {
        if (v == null) {
            return "";
        }
        boolean needs = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                     || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needs) {
            return v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    private static String sanitiseFileName(String name) {
        String base = (name == null || name.isBlank()) ? "export" : name;
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        return base.toLowerCase().endsWith(".csv") ? base : base + ".csv";
    }
}
