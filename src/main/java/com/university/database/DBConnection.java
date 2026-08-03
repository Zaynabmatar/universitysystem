package com.university.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Single point of access to the UniversityManagementDB database.
 *
 * <p>Backed by a small connection pool (HikariCP) rather than opening a fresh
 * physical connection on every call. A new SQL Server connection means a TCP
 * handshake, a TLS negotiation and a login round trip every time, and the rest
 * of the code asks for one very often, so pooling is what keeps the UI from
 * stalling on ordinary screens.</p>
 */
public class DBConnection {

    /** The one and only place the connection string is defined. */
    private static final String CONNECTION_URL =
            "jdbc:sqlserver://localhost\\SQLEXPRESS"
            + ";databaseName=universitymanagementDB"
            + ";user=sa"
            + ";password=112345a"
            + ";encrypt=true"
            + ";trustServerCertificate=true";

    private static final HikariDataSource DATA_SOURCE = buildDataSource();

    private DBConnection() {
    }

    private static HikariDataSource buildDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(CONNECTION_URL);
        config.setPoolName("UniversitySystemPool");
        // A single-user desktop app never needs many connections at once;
        // this just bounds how many can be open if something misbehaves.
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000);
        return new HikariDataSource(config);
    }

    /**
     * Borrows a connection from the pool.
     *
     * @return an open connection the caller is responsible for closing (which
     *         returns it to the pool rather than tearing it down)
     * @throws SQLException if no connection can be obtained
     */
    public static Connection getConnection() throws SQLException {
        return DATA_SOURCE.getConnection();
    }

    /**
     * Opens a connection and reports the result on the console.
     */
    public static void testConnection() {
        try (Connection connection = getConnection()) {
            System.out.println("Database connected successfully");
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
    }

    /** Releases the pool's connections. Call once, when the application is shutting down. */
    public static void shutdown() {
        if (!DATA_SOURCE.isClosed()) {
            DATA_SOURCE.close();
        }
    }

    public static void main(String[] args) {
        testConnection();
        shutdown();
    }
}
