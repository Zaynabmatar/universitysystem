package com.university.service;

import com.university.model.Semester;

import java.util.List;

/**
 * Thrown by {@link SemesterService#setCurrent(int)} when another semester's instructor evaluation
 * period ({@code evaluation_start}/{@code evaluation_end}) is still open at the moment an Admin
 * tries to make a different semester current.
 *
 * <p>Carries the still-open semesters so the screen can name them and offer to close their
 * evaluation windows automatically, then retry via {@link SemesterService#setCurrent(int, boolean)}
 * with {@code closeOpenEvaluationWindows = true} — the same "refuse silently, then let the Admin
 * confirm" shape {@link SemesterService#create} already uses for a semester that is still open.</p>
 */
public class EvaluationWindowOpenException extends ServiceException {

    private static final long serialVersionUID = 1L;

    private final List<Semester> openSemesters;

    public EvaluationWindowOpenException(List<Semester> openSemesters, String message) {
        super(message);
        this.openSemesters = openSemesters;
    }

    /** The semester(s) whose evaluation window is still open right now. */
    public List<Semester> getOpenSemesters() {
        return openSemesters;
    }
}
