package com.university.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

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
            + ";encrypt=true"
            + ";trustServerCertificate=true";

    private static final String DB_USER = "sa";

    /**
     * Credentials file, deliberately outside the project directory so the
     * password cannot be committed by accident.
     */
    private static final Path CREDENTIALS_FILE =
            Path.of(System.getProperty("user.home"), ".universitysystem", "db.properties");

    private static final String PASSWORD_KEY = "db.password";

    private static final HikariDataSource DATA_SOURCE = buildDataSource();

    private DBConnection() {
    }

    /**
     * Reads the database password from the local credentials file.
     *
     * <p>Kept out of the source tree on purpose: the password is specific to
     * one machine's SQL Server instance, and hardcoding it here would put it
     * into version control.</p>
     *
     * @return the configured password
     * @throws IllegalStateException if the file is missing, unreadable, or has
     *         no password set, with a message saying how to fix it
     */
    private static String readPassword() {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(CREDENTIALS_FILE)) {
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot read the database credentials file at " + CREDENTIALS_FILE
                    + ". Create it and add a line: " + PASSWORD_KEY + "=<password for the '"
                    + DB_USER + "' login>", e);
        }

        String password = properties.getProperty(PASSWORD_KEY);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "No " + PASSWORD_KEY + " set in " + CREDENTIALS_FILE
                    + ". Add a line: " + PASSWORD_KEY + "=<password for the '"
                    + DB_USER + "' login>");
        }
        return password;
    }

    private static HikariDataSource buildDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(CONNECTION_URL);
        config.setUsername(DB_USER);
        config.setPassword(readPassword());
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
    @SuppressWarnings("try")   // the connection is opened only to prove it can be
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
