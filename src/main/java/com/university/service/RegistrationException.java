package com.university.service;

/**
 * A registration or drop request refused by one of the rules in
 * project_details.md Section 6.1 (R1-R8) or Section 6.4 (drop/withdraw).
 *
 * <p>Carries the rule code and, for a full section (R5), the queue position
 * the student would take — so the screen can distinguish "just tell the
 * student why" from "offer the waiting list" without matching on the
 * message text.</p>
 */
public class RegistrationException extends ServiceException {

    private static final long serialVersionUID = 1L;

    private final String ruleCode;
    private final int sectionId;
    private final int waitlistPosition;

    public RegistrationException(String ruleCode, int sectionId, String message) {
        this(ruleCode, sectionId, 0, message);
    }

    public RegistrationException(String ruleCode, int sectionId, int waitlistPosition, String message) {
        super(message);
        this.ruleCode = ruleCode;
        this.sectionId = sectionId;
        this.waitlistPosition = waitlistPosition;
    }

    /** Which rule refused the request, e.g. {@code "R3"}. */
    public String getRuleCode() {
        return ruleCode;
    }

    /** The section the student was trying to join. */
    public int getSectionId() {
        return sectionId;
    }

    /** The position the student would take in the queue. Zero unless this is R5. */
    public int getWaitlistPosition() {
        return waitlistPosition;
    }

    /** True only for R5 — the one refusal that comes with a waiting-list offer attached. */
    public boolean isWaitlistOffer() {
        return "R5".equals(ruleCode);
    }
}
