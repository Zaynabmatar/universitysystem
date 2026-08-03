package com.university.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Single point of access to the UniversityManagementDB database.
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

    private DBConnection() {
    }

    /**
     * Opens a new connection to the database.
     *
     * @return an open connection the caller is responsible for closing
     * @throws SQLException if the connection cannot be established
     */
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(CONNECTION_URL);
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

    public static void main(String[] args) {
        testConnection();
    }
}
