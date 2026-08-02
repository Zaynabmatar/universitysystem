package com.university.dao;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Turns the current row of a {@link ResultSet} into one object.
 *
 * <p>The implementation must read the current row only. Advancing the cursor
 * is the caller's job.</p>
 *
 * @param <T> the type produced
 */
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * Reads the row the cursor is currently on.
     *
     * @param resultSet positioned on a valid row
     * @return the object built from that row
     * @throws SQLException if a column cannot be read
     */
    T map(ResultSet resultSet) throws SQLException;
}
