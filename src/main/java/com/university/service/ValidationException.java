package com.university.service;

/**
 * Thrown when what was supplied is wrong, rather than when the rules forbid
 * an otherwise sensible request.
 *
 * <p>A mark of 120 out of 100 is a validation problem. Registering after the
 * deadline is not: the input was fine, the rules said no, and that is a plain
 * {@link ServiceException}.</p>
 */
public class ValidationException extends ServiceException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }

    /** Throws when {@code value} is null, naming the field in the message. */
    public static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new ValidationException(fieldName + " is required.");
        }
    }

    /** Throws when the text is null, empty or only spaces. */
    public static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(fieldName + " is required.");
        }
    }

    /** Throws when the identifier is not a usable key. */
    public static void requireId(int value, String fieldName) {
        if (value <= 0) {
            throw new ValidationException(fieldName + " is not valid.");
        }
    }
}
