package com.university.service;

/**
 * Thrown when an operation cannot go ahead for a business reason.
 *
 * <p>The message is written to be shown to the person using the program, not
 * to a developer. "The registration period for Fall 2025 closed on 20
 * September" belongs here; a stack trace does not.</p>
 *
 * <p>This is separate from {@code DataAccessException}, which means the
 * database itself failed. A service exception means the database is fine and
 * the answer is simply no.</p>
 */
public class ServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
