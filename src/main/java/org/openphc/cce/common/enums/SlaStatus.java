package org.openphc.cce.common.enums;

/**
 * Whether a step's service-level agreement has been met.
 *
 * <p>This is the <em>timeliness</em> half of a step's condition, independent of whether the work was
 * ever recorded ({@link StepStatus}). Two things move it:
 *
 * <ul>
 *   <li><b>Time passing.</b> The service that evaluates {@code step_sla_state_transition} advances
 *       {@code PENDING} → {@code OVERDUE} at the due threshold and {@code OVERDUE} → {@code MISSED} at
 *       the missed threshold, and records the deviation. Matcher schedules those thresholds but does
 *       not apply them.</li>
 *   <li><b>The event arriving.</b> Matcher settles the SLA on completion — {@code MET} if the event
 *       beat the due threshold, otherwise whatever status had already been reached, so the row records
 *       both that the work was done and that it was late.</li>
 * </ul>
 *
 * <p>Only {@link #PENDING} and {@link #OVERDUE} are live — a step in {@link #MET} or
 * {@link #MISSED} has no threshold left to cross and is never advanced again.
 *
 * <p>Paired with {@link StepStatus}, this classifies how timely a completion was:
 * {@code COMPLETED + MET} is on time, {@code COMPLETED + OVERDUE} is late, and
 * {@code COMPLETED + MISSED} is late past the point the step was written off.
 *
 * @see StepStatus
 */
public enum SlaStatus {

    /** {@code due_date} has not been reached. Nothing is late. */
    PENDING,

    /**
     * The due threshold passed without the event arriving.
     */
    OVERDUE,

    /**
     * The missed threshold passed without the event arriving. Terminal as an SLA outcome: a later
     * event still sets {@link StepStatus#COMPLETED} but the SLA stays missed.
     */
    MISSED,

    /**
     * The SLA was satisfied. Either the event arrived before the due threshold, or the step was an
     * optional ({@code could}) one that was closed out without an event — nothing was breached in
     * either case. An optional step closed out this way keeps {@link StepStatus#NOT_STARTED}, which is
     * what distinguishes it from one that was actually completed.
     */
    MET
}
