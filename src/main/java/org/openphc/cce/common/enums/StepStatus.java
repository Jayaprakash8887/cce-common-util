package org.openphc.cce.common.enums;

/**
 * Whether the clinical event a step expects has been received.
 *
 * <p>This is the <em>progress</em> half of a step's condition. It answers only "did the work get
 * recorded?" and says nothing about whether it was recorded on time — that is {@link SlaStatus}.
 * Keeping the two apart lets a single row state that a step's deadline was missed <em>and</em> that
 * its event eventually arrived.
 *
 * <p>The values come from the FHIR R4
 * <a href="https://hl7.org/fhir/R4/valueset-care-plan-activity-status.html">CarePlanActivityStatus</a>
 * value set, which governs occurrences of a plan's activities — what a {@code step_instance} is.
 * {@link #code()} returns the FHIR code and is what goes on the wire and into rule contexts; the
 * database column and Java code use the enum name.
 *
 * @see SlaStatus
 */
public enum StepStatus {

    /**
     * FHIR {@code not-started} — "Care plan activity is planned but no action has yet been taken."
     * No matching event has been received. The step remains completable.
     */
    NOT_STARTED("not-started"),

    /**
     * FHIR {@code completed} — "Care plan activity has been completed (more or less) as planned."
     * The expected event was received. Terminal.
     */
    COMPLETED("completed");

    private final String code;

    StepStatus(String code) {
        this.code = code;
    }

    /** The FHIR CarePlanActivityStatus code for this status. */
    public String code() {
        return code;
    }
}
