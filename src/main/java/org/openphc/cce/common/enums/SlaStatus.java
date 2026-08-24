package org.openphc.cce.common.enums;

/**
 * Whether a step's service-level agreement has been met.
 *
 * <p>This is the <em>timeliness</em> half of a step's condition, independent of whether the work was
 * ever recorded ({@link StepStatus}).
 *
 * <h2>Null means "not yet judged"</h2>
 * There is deliberately no {@code PENDING} constant, and {@code step_instance.sla_status} is nullable.
 * A null column is the initial state: no threshold has been reached, so there is nothing to say about
 * timeliness yet. Modelling that absence as an enum value made it look like a judgement that had been
 * made, when it is precisely the absence of one.
 *
 * <p>Null is also the resting state of a step that has <em>no</em> SLA. A step whose definition sets no
 * due date gets no {@code step_sla_state_transition} rows, so no threshold will ever fall due for it and
 * its {@code sla_status} stays null for good — correctly, because no SLA applies.
 *
 * <h2>One writer</h2>
 * Every value here is written by the <strong>Compliance Service</strong> alone, as it applies
 * {@code step_sla_state_transition} rows. Matcher schedules the thresholds and records the completion,
 * but never judges timeliness — so there is no window in which the two services disagree about a step's
 * SLA, and no rule about which of them may overwrite the other.
 *
 * <p>Paired with {@link StepStatus}, this classifies how timely a completion was:
 * {@code COMPLETED + MET} is on time, {@code COMPLETED + OVERDUE} is late, and
 * {@code COMPLETED + MISSED} is late past the point the step was written off.
 *
 * @see StepStatus
 * @see SlaTransitionType
 */
public enum SlaStatus {

    /**
     * The due threshold passed and the event had not arrived by then. Not terminal: the missed
     * threshold can still move it to {@link #MISSED}.
     */
    OVERDUE,

    /**
     * The missed threshold passed and the event had not arrived by then. Terminal: a later event still
     * sets {@link StepStatus#COMPLETED}, but the SLA stays missed.
     */
    MISSED,

    /**
     * The SLA was satisfied — the event arrived before the due threshold. Terminal.
     */
    MET
}
