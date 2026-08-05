package com.university.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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

    /** Seconds the driver may spend on a single login attempt before giving up. */
    public static final int LOGIN_TIMEOUT_SECONDS = 5;

    /** The one and only place the connection string is defined. */
    private static final String CONNECTION_URL =
            "jdbc:sqlserver://localhost\\SQLEXPRESS"
            + ";databaseName=universitymanagementDB"
            + ";encrypt=true"
            + ";trustServerCertificate=true"
            // Without this, the driver sends every TIME parameter over the wire as
            // datetime (legacy SQL Server 2005 compatibility default), which makes SQL
            // Server reject a bare "? < time_column" comparison with "The data types
            // datetime and time are incompatible in the less than operator" — this is
            // what broke the section instructor/room clash checks on Add/Edit Section.
            + ";sendTimeAsDatetime=false"
            // Bound every login attempt. The driver's default is 30 seconds per try,
            // which is what let a misconfigured server turn startup into a long,
            // silent stall instead of a prompt error message.
            + ";loginTimeout=" + LOGIN_TIMEOUT_SECONDS;

    private static final String DB_USER = "sa";

    /**
     * Credentials file, deliberately outside the project directory so the
     * password cannot be committed by accident.
     */
    private static final Path CREDENTIALS_FILE =
            Path.of(System.getProperty("user.home"), ".universitysystem", "db.properties");

    private static final String PASSWORD_KEY = "db.password";

    /**
     * Built on first use rather than in a static initialiser. If the pool
     * cannot be constructed (for example the credentials file is missing), a
     * static initialiser would throw {@code ExceptionInInitializerError} and
     * every later touch of this class would then fail with a bare
     * {@code NoClassDefFoundError} that says nothing about the real problem.
     */
    private static HikariDataSource dataSource;

    private DBConnection() {
    }

    private static synchronized HikariDataSource dataSource() {
        if (dataSource == null) {
            dataSource = buildDataSource();
        }
        return dataSource;
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
        // SQL Server Express can still be recovering its databases well after
        // Windows reports the service as "Running" (measured ~2m24s gap on this
        // machine between OS boot and the engine actually accepting logins).
        // Without this, HikariCP validates a connection during construction and,
        // failing that, throws out of this static initialiser — which permanently
        // poisons the DBConnection class for the rest of the JVM's life, so no
        // later retry (see App.start()) could ever succeed. -1 defers all
        // connection attempts to getConnection() calls, which are retryable.
        config.setInitializationFailTimeout(-1);
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
        return dataSource().getConnection();
    }

    /**
     * Opens one un-pooled connection purely to find out whether the database is
     * reachable, and closes it again.
     *
     * <p>Deliberately bypasses the pool. {@link #getConnection()} goes through
     * HikariCP, which keeps retrying internally for the whole
     * {@code connectionTimeout} window before it reports anything — so a server
     * that is simply not listening still costs ten seconds per call and the
     * caller never sees the driver's actual complaint. Going straight to the
     * driver here fails in well under a second and hands back the real cause,
     * which is what makes a clear startup error possible.</p>
     *
     * @throws SQLException if the database cannot be reached or the login fails
     */
    public static void verifyConnectivity() throws SQLException {
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        try (Connection connection =
                     DriverManager.getConnection(CONNECTION_URL, DB_USER, readPassword())) {
            // Reaching here is the whole result: the server answered and accepted
            // the login. Which server and which database it actually reached is
            // worth saying out loud when asked, because a machine with more than
            // one SQL Server instance can hold more than one copy of this database
            // and every "the data is wrong" symptom starts there.
            if (Boolean.getBoolean("university.debug.login")) {
                System.out.println("[DB] " + describeConnected(connection));
            }
        }
    }

    /**
     * Asks the server which database this connection is actually in, rather
     * than repeating what the connection string asked for.
     *
     * <p>The two can differ — a restored copy under another name, a default
     * database on the login — and when they do, every "the data is not there"
     * symptom follows from it. This reports the answer the server gives.</p>
     */
    public static String describeConnected(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT DB_NAME(), @@SERVERNAME, SUSER_SNAME(), "
                     + "(SELECT COUNT(*) FROM dbo.users)")) {
            if (!rs.next()) {
                return "connected, but the server returned no identifying row";
            }
            return "database=" + rs.getString(1)
                    + "  server=" + rs.getString(2)
                    + "  login=" + rs.getString(3)
                    + "  dbo.users rows=" + rs.getInt(4);
        }
    }

    /** {@link #describeConnected(Connection)} on a pooled connection. */
    public static String describeConnected() {
        try (Connection connection = getConnection()) {
            return describeConnected(connection);
        } catch (SQLException e) {
            return "could not be determined: " + e.getMessage();
        }
    }

    /** The server and database this build talks to, for error messages. */
    public static String describeTarget() {
        return "localhost\\SQLEXPRESS / universitymanagementDB (login '" + DB_USER + "')";
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
    public static synchronized void shutdown() {
        // Never build the pool just to close it: if startup failed before the
        // pool was ever needed, there is nothing to release.
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public static void main(String[] args) {
        testConnection();
        shutdown();
    }
}
