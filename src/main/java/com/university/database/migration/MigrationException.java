package com.university.database.migration;

/**
 * A migration could not be applied.
 *
 * <p>Carries the filename because that is the one thing the person reading the
 * error actually needs: which file to open. The failed migration has already
 * been rolled back by the time this is thrown, and no later migration was
 * attempted.</p>
 */
public class MigrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The migration filename, or null when the failure happened before any file was opened. */
    private final String migrationName;

    public MigrationException(String migrationName, String message, Throwable cause) {
        super(message, cause);
        this.migrationName = migrationName;
    }

    public MigrationException(String message, Throwable cause) {
        this(null, message, cause);
    }

    /** The migration filename that failed, or null if the failure was not file-specific. */
    public String getMigrationName() {
        return migrationName;
    }
}
