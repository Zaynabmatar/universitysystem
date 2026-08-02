package com.university.dao;

/**
 * Thrown when a database operation fails.
 *
 * <p>This is unchecked on purpose. A {@code SQLException} carries no
 * information a controller can act on, so forcing every caller to catch it
 * only spreads empty catch blocks through the user interface. The data access
 * layer wraps the original exception here and lets it travel to whichever
 * layer is able to show a message.</p>
 */
public class DataAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataAccessException(String message) {
        super(message);
    }

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
