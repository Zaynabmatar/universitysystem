package com.university.database;

/**
 * The signed-in application user's id, for the whole life of this run.
 *
 * <p>A desktop program has exactly one person in front of it, so this is a
 * single value rather than something threaded through every call. It exists
 * so {@link DBConnection#getConnection()} can stamp every connection it hands
 * out with {@code sp_set_session_context(N'app_user_id', ...)} — the value the
 * new {@code trg_*_Audit} triggers (migration {@code 0014_audit_log_triggers.sql})
 * read for tables that carry no {@code submitted_by} / {@code recorded_by}
 * column of their own, such as {@code dbo.students} and {@code dbo.courses}.
 * {@code com.university.service.Session} sets and clears it on sign-in and
 * sign-out; this class lives in {@code database} rather than {@code service}
 * so the connection layer never has to depend upward on it.</p>
 */
public final class ActorContext {

    private static volatile Integer currentUserId;

    private ActorContext() {
    }

    /** Called by {@code Session.begin} once a sign-in succeeds. */
    public static void set(Integer userId) {
        currentUserId = userId;
    }

    /** Called by {@code Session.end} on sign-out. */
    public static void clear() {
        currentUserId = null;
    }

    /** The signed-in user's id, or null when nobody is signed in. */
    public static Integer get() {
        return currentUserId;
    }
}
