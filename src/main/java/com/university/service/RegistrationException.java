package com.university.service;

/**
 * A registration or drop request refused by one of the rules in
 * project_details.md Section 6.1 (R1-R8) or Section 6.4 (drop/withdraw).
 *
 * <p>Carries the rule code so the screen can distinguish which rule refused the request without
 * matching on the message text.</p>
 */
public class RegistrationException extends ServiceException {

    private static final long serialVersionUID = 1L;

    private final String ruleCode;
    private final int sectionId;

    public RegistrationException(String ruleCode, int sectionId, String message) {
        super(message);
        this.ruleCode = ruleCode;
        this.sectionId = sectionId;
    }

    /** Which rule refused the request, e.g. {@code "R3"}. */
    public String getRuleCode() {
        return ruleCode;
    }

    /** The section the student was trying to join. */
    public int getSectionId() {
        return sectionId;
    }
}
