package com.university.dao;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * The operations every table with a single-column identity key supports.
 *
 * <p>Each writing method comes in two forms. The short form opens and closes
 * its own connection and is what a screen showing one record uses. The form
 * taking a {@link Connection} joins work that is already in progress, which
 * is what makes a multi-table change atomic: enrolling a student inserts an
 * enrolment and raises {@code sections.enrolled_count}, and those two
 * statements have to succeed or fail together.</p>
 *
 * @param <T> the model type this data access object reads and writes
 */
public interface GenericDAO<T> {

    /**
     * Finds one record by its primary key.
     *
     * @return the record, or an empty optional when no row has that key
     */
    Optional<T> findById(int id);

    /** Returns every record in the table. */
    List<T> findAll();

    /**
     * Inserts a new record.
     *
     * @return the generated primary key
     */
    int insert(T entity);

    /**
     * Inserts a new record using a connection the caller controls.
     *
     * @return the generated primary key
     */
    int insert(Connection connection, T entity);

    /**
     * Overwrites an existing record, matched on its primary key.
     *
     * @return true when a row was changed
     */
    boolean update(T entity);

    /**
     * Overwrites an existing record using a connection the caller controls.
     *
     * @return true when a row was changed
     */
    boolean update(Connection connection, T entity);

    /**
     * Deletes the record with this primary key.
     *
     * @return true when a row was removed
     */
    boolean deleteById(int id);

    /**
     * Deletes the record using a connection the caller controls.
     *
     * @return true when a row was removed
     */
    boolean deleteById(Connection connection, int id);
}
