package com.university.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Null-safe readers for {@link ResultSet} columns.
 *
 * <p>These exist because {@code ResultSet.getInt} returns {@code 0} for a
 * NULL column rather than anything that looks empty. Reading
 * {@code sections.instructor_id} for a section published as TBA with
 * {@code getInt} yields instructor 0, which is a row that does not exist. The
 * nullable identifiers in the model layer are therefore {@code Integer}, and
 * {@link #getInteger} is how they are filled.</p>
 *
 * <p>The date and time readers use {@code getObject} with an explicit class,
 * which the JDBC driver maps straight to {@code java.time} and returns as
 * null when the column is NULL. That avoids the second-level truncation of
 * {@code java.sql.Time}.</p>
 */
public final class DaoUtils {

    private DaoUtils() {
    }

    /**
     * Reads an INT column that may be NULL.
     *
     * @return the value, or null when the column is NULL
     */
    public static Integer getInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    /** Reads a DATE column, returning null when it is NULL. */
    public static LocalDate getLocalDate(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, LocalDate.class);
    }

    /** Reads a DATETIME2 column, returning null when it is NULL. */
    public static LocalDateTime getLocalDateTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, LocalDateTime.class);
    }

    /** Reads a TIME column, returning null when it is NULL. */
    public static LocalTime getLocalTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, LocalTime.class);
    }
}
