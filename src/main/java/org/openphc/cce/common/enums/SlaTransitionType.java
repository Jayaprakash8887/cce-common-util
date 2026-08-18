package org.openphc.cce.common.enums;

/**
 * A time-driven SLA transition a step may undergo.
 *
 * <p>One value per threshold the SLA lifecycle crosses: {@code PENDING → OVERDUE} at the due date, and
 * {@code OVERDUE → MISSED} at the missed date. There is deliberately no value for reaching
 * {@link SlaStatus#MET} — that is settled by an event arriving (or an optional step being closed out),
 * not by time passing, so it is never scheduled.
 *
 * <p>These names are the contract with the service that evaluates and applies transitions: Matcher
 * writes them to {@code step_sla_state_transition.transition_type}, and that service reads them to know
 * which SLA status to move the step to and which deviation to record.
 *
 * @see org.openphc.cce.common.entity.StepSlaStateTransition
 */
public enum SlaTransitionType {

    /**
     * The due threshold passed without the event arriving. The evaluator records an
     * {@link org.openphc.cce.common.enums.DeviationType#OVERDUE} deviation.
     */
    PENDING_TO_OVERDUE(SlaStatus.PENDING, SlaStatus.OVERDUE),

    /**
     * The missed date passed without the event arriving. A mandatory step is written off as
     * {@link SlaStatus#MISSED} with a deviation; an optional ("could") step settles as
     * {@link SlaStatus#MET} instead, having breached nothing.
     */
    OVERDUE_TO_MISSED(SlaStatus.OVERDUE, SlaStatus.MISSED);

    private final SlaStatus fromStatus;
    private final SlaStatus toStatus;

    SlaTransitionType(SlaStatus fromStatus, SlaStatus toStatus) {
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    /** The SLA status a step must be in for this transition to apply. */
    public SlaStatus fromStatus() {
        return fromStatus;
    }

    /** The SLA status this transition moves the step to. */
    public SlaStatus toStatus() {
        return toStatus;
    }
}
