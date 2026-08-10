package com.university.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Properties;

/**
 * Single point of access to the university database.
 *
 * <p>Backed by a small connection pool (HikariCP) rather than opening a fresh
 * physical connection on every call. A new SQL Server connection means a TCP
 * handshake, a TLS negotiation and a login round trip every time, and the rest
 * of the code asks for one very often, so pooling is what keeps the UI from
 * stalling on ordinary screens.</p>
 *
 * <h2>Where the connection settings come from</h2>
 *
 * <p>Nothing about <em>which</em> server to reach is in this file, and nothing
 * about it is in Git. Every developer's SQL Server instance has a different
 * name, so a hardcoded one is not a setting — it is one machine's private
 * detail committed on top of everyone else's. That is not hypothetical here: a
 * commit changing this line from one instance name to another silently
 * repointed the application at a second, older copy of the database. The schema
 * matched, the application started, and the only symptom was that the data
 * looked wrong.</p>
 *
 * <p>The settings live in {@code ~/.universitysystem/db.properties}, outside
 * the project directory — which is where the password already was, for the same
 * reason. {@code db.properties.example} in the project root lists every key.
 * Any key can be overridden for a single run with {@code -Ddb.<key>=<value>},
 * and the file itself can be moved with {@code -Duniversity.db.config=<path>}.</p>
 */
public class DBConnection {

    /* ------------------------------------------------------------------ */
    /* configuration keys — see db.properties.example                      */
    /* ------------------------------------------------------------------ */

    /** A complete JDBC URL, for a setup the parts below cannot describe. */
    private static final String KEY_URL = "db.url";

    private static final String KEY_SERVER = "db.server";
    private static final String KEY_INSTANCE = "db.instance";
    private static final String KEY_PORT = "db.port";
    private static final String KEY_NAME = "db.name";
    private static final String KEY_USER = "db.user";
    private static final String KEY_PASSWORD = "db.password";
    private static final String KEY_ENCRYPT = "db.encrypt";
    private static final String KEY_TRUST_CERT = "db.trustServerCertificate";
    private static final String KEY_LOGIN_TIMEOUT = "db.loginTimeoutSeconds";
    private static final String KEY_INTEGRATED_SECURITY = "db.integratedSecurity";

    /**
     * The database name is a fact about this project rather than about anybody's
     * machine, so it is the one part of the target that may sensibly default.
     */
    private static final String DEFAULT_DATABASE = "universitymanagementDB";

    private static final String DEFAULT_SERVER = "localhost";

    /** Seconds the driver may spend on a single login attempt before giving up. */
    public static final int DEFAULT_LOGIN_TIMEOUT_SECONDS = 5;

    /**
     * Settings file, deliberately outside the project directory so that neither
     * the password nor one developer's server name can be committed by accident.
     */
    private static final Path DEFAULT_CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".universitysystem", "db.properties");

    private static final String CONFIG_FILE_PROPERTY = "university.db.config";

    /* ------------------------------------------------------------------ */

    /**
     * Built on first use rather than in a static initialiser. If the pool
     * cannot be constructed (for example the settings file is missing), a
     * static initialiser would throw {@code ExceptionInInitializerError} and
     * every later touch of this class would then fail with a bare
     * {@code NoClassDefFoundError} that says nothing about the real problem.
     */
    private static HikariDataSource dataSource;

    private static Properties settings;

    private DBConnection() {
    }

    private static synchronized HikariDataSource dataSource() {
        if (dataSource == null) {
            dataSource = buildDataSource();
        }
        return dataSource;
    }

    /* ------------------------------------------------------------------ */
    /* settings                                                            */
    /* ------------------------------------------------------------------ */

    private static Path configFile() {
        String override = System.getProperty(CONFIG_FILE_PROPERTY);
        return (override == null || override.isBlank())
                ? DEFAULT_CONFIG_FILE
                : Path.of(override);
    }

    /**
     * Reads the settings file once and keeps it.
     *
     * @throws IllegalStateException if the file is missing or unreadable, with
     *         a message saying exactly what to create
     */
    private static synchronized Properties settings() {
        if (settings != null) {
            return settings;
        }
        Path file = configFile();
        Properties loaded = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            loaded.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot read the database settings file at " + file + ". "
                    + "Copy db.properties.example from the project root to that path and fill it in. "
                    + "It lives outside the project so that it is never committed: every developer "
                    + "points at their own SQL Server instance.", e);
        }
        settings = loaded;
        return settings;
    }

    /**
     * One setting, with {@code -Ddb.<key>} winning over the file.
     *
     * @param fallback returned when neither is set
     */
    private static String setting(String key, String fallback) {
        String fromSystem = System.getProperty(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem.trim();
        }
        String fromFile = settings().getProperty(key);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile.trim();
        }
        return fallback;
    }

    private static String requiredSetting(String key) {
        String value = setting(key, null);
        if (value == null) {
            throw new IllegalStateException(
                    "No " + key + " set in " + configFile()
                    + ". See db.properties.example in the project root for the keys this file needs.");
        }
        return value;
    }

    private static boolean booleanSetting(String key, boolean fallback) {
        String value = setting(key, null);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int intSetting(String key, int fallback) {
        String value = setting(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    key + " in " + configFile() + " must be a whole number, but is \"" + value + "\".", e);
        }
    }

    /** True when the configuration asks for Windows authentication rather than a SQL login. */
    private static boolean usesIntegratedSecurity() {
        return booleanSetting(KEY_INTEGRATED_SECURITY, false);
    }

    private static String username() {
        return requiredSetting(KEY_USER);
    }

    private static String password() {
        return requiredSetting(KEY_PASSWORD);
    }

    /** Seconds allowed for one login attempt, as configured. */
    public static int loginTimeoutSeconds() {
        return intSetting(KEY_LOGIN_TIMEOUT, DEFAULT_LOGIN_TIMEOUT_SECONDS);
    }

    /**
     * Assembles the JDBC URL from the settings file.
     *
     * <p>{@code db.url} short-circuits all of it, for a setup these parts cannot
     * express (a named listener, a failover partner, Azure). The two driver
     * options this application depends on are appended to it even so, unless it
     * already sets them — they are bug fixes, not preferences.</p>
     */
    private static String connectionUrl() {
        String explicit = setting(KEY_URL, null);
        String url = explicit != null ? explicit : buildUrlFromParts();

        // sendTimeAsDatetime: without it the driver sends every TIME parameter
        // over the wire as datetime (a SQL Server 2005 compatibility default),
        // which makes SQL Server reject a bare "? < time_column" comparison with
        // "The data types datetime and time are incompatible in the less than
        // operator" — this is what broke the section instructor/room clash
        // checks on Add/Edit Section.
        url = appendIfAbsent(url, "sendTimeAsDatetime", "false");

        // Bound every login attempt. The driver's default is 30 seconds per try,
        // which is what let a misconfigured server turn startup into a long,
        // silent stall instead of a prompt error message.
        url = appendIfAbsent(url, "loginTimeout", String.valueOf(loginTimeoutSeconds()));

        if (explicit == null) {
            url = appendIfAbsent(url, "encrypt", String.valueOf(booleanSetting(KEY_ENCRYPT, true)));
            url = appendIfAbsent(url, "trustServerCertificate",
                    String.valueOf(booleanSetting(KEY_TRUST_CERT, true)));
            if (usesIntegratedSecurity()) {
                url = appendIfAbsent(url, "integratedSecurity", "true");
            }
        }
        return url;
    }

    private static String buildUrlFromParts() {
        String server = setting(KEY_SERVER, DEFAULT_SERVER);
        String instance = setting(KEY_INSTANCE, null);
        String port = setting(KEY_PORT, null);

        StringBuilder url = new StringBuilder("jdbc:sqlserver://").append(server);
        // A named instance and an explicit port are two ways of saying the same
        // thing. Given both, the port wins: it is the one that does not need the
        // SQL Server Browser service to be running.
        if (port != null) {
            url.append(':').append(port);
        } else if (instance != null) {
            url.append('\\').append(instance);
        }
        url.append(";databaseName=").append(setting(KEY_NAME, DEFAULT_DATABASE));
        return url.toString();
    }

    /** Adds {@code ;key=value} unless the URL already sets that key. */
    private static String appendIfAbsent(String url, String key, String value) {
        boolean alreadySet = url.toLowerCase(java.util.Locale.ROOT)
                .contains(";" + key.toLowerCase(java.util.Locale.ROOT) + "=");
        return alreadySet ? url : url + ";" + key + "=" + value;
    }

    /* ------------------------------------------------------------------ */
    /* the pool                                                            */
    /* ------------------------------------------------------------------ */

    private static HikariDataSource buildDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(connectionUrl());
        if (!usesIntegratedSecurity()) {
            config.setUsername(username());
            config.setPassword(password());
        }
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
        Connection connection = dataSource().getConnection();
        stampSessionContext(connection);
        return connection;
    }

    /**
     * Stamps the connection with the signed-in user's id, using SQL Server's
     * {@code SESSION_CONTEXT} rather than a table or a bind variable threaded
     * through every call.
     *
     * <p>The pool reuses physical connections across unrelated borrows, so this
     * runs on every single checkout — never only on first use — and always
     * writes an explicit value, including {@code NULL} when nobody is signed
     * in. Otherwise a connection last used for one administrator's session
     * could still carry that administrator's id the next time it is handed
     * out, mis-attributing whatever the new borrower's trigger-audited change
     * turns out to be.</p>
     *
     * <p>The {@code trg_*_Audit} triggers (migration
     * {@code 0014_audit_log_triggers.sql}) read this back with
     * {@code SESSION_CONTEXT(N'app_user_id')} for every audited table that has
     * no {@code submitted_by} / {@code recorded_by} column of its own to carry
     * the actor explicitly.</p>
     */
    private static void stampSessionContext(Connection connection) throws SQLException {
        Integer actorUserId = ActorContext.get();
        try (PreparedStatement statement = connection.prepareStatement(
                "EXEC sp_set_session_context @key = N'app_user_id', @value = ?")) {
            if (actorUserId == null) {
                statement.setNull(1, Types.INTEGER);
            } else {
                statement.setInt(1, actorUserId);
            }
            statement.execute();
        }
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
        DriverManager.setLoginTimeout(loginTimeoutSeconds());
        try (Connection connection = openDirectConnection()) {
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

    /** One connection straight from the driver, honouring the configured authentication. */
    private static Connection openDirectConnection() throws SQLException {
        return usesIntegratedSecurity()
                ? DriverManager.getConnection(connectionUrl())
                : DriverManager.getConnection(connectionUrl(), username(), password());
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

    /**
     * The server and database this run is configured to talk to, for error
     * messages.
     *
     * <p>Reports what the settings file actually says rather than a constant,
     * so a startup failure names the machine it really tried — and says where
     * to change it.</p>
     */
    public static String describeTarget() {
        try {
            String login = usesIntegratedSecurity()
                    ? "Windows authentication"
                    : "login '" + username() + "'";
            return connectionUrlWithoutSecrets() + " (" + login + ", configured in " + configFile() + ")";
        } catch (RuntimeException e) {
            return "not configured — " + e.getMessage();
        }
    }

    /**
     * The URL with any embedded password removed.
     *
     * <p>{@link #describeTarget()} ends up in a dialog and in the log, and
     * {@code db.url} is free-form enough that somebody will eventually put a
     * password in it.</p>
     */
    private static String connectionUrlWithoutSecrets() {
        return connectionUrl().replaceAll("(?i);password=[^;]*", ";password=***");
    }

    /**
     * Opens a connection and reports what it actually reached.
     */
    public static void testConnection() {
        try (Connection connection = getConnection()) {
            System.out.println("Database connected successfully");
            System.out.println(describeConnected(connection));
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
        System.out.println("Configured target: " + describeTarget());
        testConnection();
        shutdown();
    }
}
