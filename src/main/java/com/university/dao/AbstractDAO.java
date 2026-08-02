package com.university.dao;

import com.university.database.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The JDBC plumbing every data access object shares.
 *
 * <p>Each helper comes in two forms. The short form opens a connection, uses
 * it and closes it. The form whose first argument is a {@link Connection}
 * uses the one it is given and leaves it open, so several calls can run
 * inside one transaction.</p>
 *
 * <p>Connections come from {@link DBConnection}, which this class does not
 * modify in any way.</p>
 */
public abstract class AbstractDAO {

    /**
     * Opens a connection the caller is responsible for closing.
     *
     * @throws DataAccessException if the database cannot be reached
     */
    protected Connection openConnection() {
        try {
            return DBConnection.getConnection();
        } catch (SQLException e) {
            throw new DataAccessException("Could not open a database connection", e);
        }
    }

    /**
     * Starts a transaction on a fresh connection.
     *
     * <p>The caller must commit or roll back and then close it. A service
     * that changes several tables at once uses this, hands the connection to
     * each data access object, and commits only when every step worked.</p>
     */
    public Connection beginTransaction() {
        Connection connection = openConnection();
        try {
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new DataAccessException("Could not start a transaction", e);
        }
    }

    /**
     * Rolls a transaction back, swallowing any secondary failure.
     *
     * <p>Used in a catch block, where the original exception is the one worth
     * reporting and a failure to roll back would hide it.</p>
     */
    public void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The caller is already handling a more important failure.
        }
    }

    /** Closes a connection, swallowing any failure. */
    public void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful can be done at this point.
        }
    }

    /** Runs a query on its own connection and maps every row. */
    protected <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection connection = openConnection()) {
            return queryList(connection, sql, mapper, params);
        } catch (SQLException e) {
            throw new DataAccessException("Could not close the connection used by: " + sql, e);
        }
    }

    /** Runs a query on a caller-supplied connection and maps every row. */
    protected <T> List<T> queryList(Connection connection, String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(mapper.map(resultSet));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Query failed: " + sql, e);
        }
    }

    /** Runs a query on its own connection and returns the first row, if any. */
    protected <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection connection = openConnection()) {
            return queryOne(connection, sql, mapper, params);
        } catch (SQLException e) {
            throw new DataAccessException("Could not close the connection used by: " + sql, e);
        }
    }

    /** Runs a query on a caller-supplied connection and returns the first row. */
    protected <T> Optional<T> queryOne(Connection connection, String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapper.map(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Query failed: " + sql, e);
        }
    }

    /** Reads a single whole number, used for counts and totals. */
    protected int queryInt(String sql, Object... params) {
        return queryOne(sql, resultSet -> resultSet.getInt(1), params).orElse(0);
    }

    /** Reads a single whole number on a caller-supplied connection. */
    protected int queryInt(Connection connection, String sql, Object... params) {
        return queryOne(connection, sql, resultSet -> resultSet.getInt(1), params).orElse(0);
    }

    /**
     * Runs an INSERT, UPDATE or DELETE on its own connection.
     *
     * @return the number of rows affected
     */
    protected int executeUpdate(String sql, Object... params) {
        try (Connection connection = openConnection()) {
            return executeUpdate(connection, sql, params);
        } catch (SQLException e) {
            throw new DataAccessException("Could not close the connection used by: " + sql, e);
        }
    }

    /**
     * Runs an INSERT, UPDATE or DELETE on a caller-supplied connection.
     *
     * @return the number of rows affected
     */
    protected int executeUpdate(Connection connection, String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Statement failed: " + sql, e);
        }
    }

    /**
     * Runs an INSERT on its own connection and reads the identity value back.
     *
     * @return the generated primary key
     */
    protected int insertAndReturnKey(String sql, Object... params) {
        try (Connection connection = openConnection()) {
            return insertAndReturnKey(connection, sql, params);
        } catch (SQLException e) {
            throw new DataAccessException("Could not close the connection used by: " + sql, e);
        }
    }

    /**
     * Runs an INSERT on a caller-supplied connection and reads the identity
     * value back.
     *
     * @return the generated primary key
     * @throws DataAccessException if the database returned no key
     */
    protected int insertAndReturnKey(Connection connection, String sql, Object... params) {
        try (PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, params);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new DataAccessException("Insert returned no generated key: " + sql);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Insert failed: " + sql, e);
        }
    }

    /**
     * Fills the placeholders of a prepared statement.
     *
     * <p>An enum constant is written as its own name, which is the exact
     * string the CHECK constraints in the schema accept. Everything else,
     * including null and the {@code java.time} types, is handed to the driver
     * as is.</p>
     */
    protected void bind(PreparedStatement statement, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            if (value instanceof Enum<?> constant) {
                statement.setString(i + 1, constant.name());
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }
}
