package org.openphc.cce.common.enums;

/**
 * A time-driven SLA threshold a step may cross.
 *
 * <p>One value per threshold in the SLA lifecycle: the due date, and the missed date. There is
 * deliberately no value for reaching {@link SlaStatus#MET} — that is not a threshold being crossed but
 * a statement about the step, settled by the Step SLA Service from {@code step_instance.due_date}
 * without a row, so it is never scheduled.
 *
 * <p>These names describe the <em>threshold</em>, not a from/to pair. An earlier revision called them
 * {@code DUE_DATE_REACHED} and {@code MISSED_DATE_REACHED}, which stopped being true once
 * {@code PENDING} was removed from {@link SlaStatus} and a crossed threshold stopped implying a single
 * destination — a step that beat its due date records nothing when the row is applied. What the row
 * records is which deadline fell, and only ever a breach of it; what being on time means for the step
 * is settled from the step itself.
 *
 * <p>These names are the contract with that service: Matcher writes them to
 * {@code step_sla_state_transition.transition_type}, and the Step SLA Service reads them to know which
 * deadline fell and which deviation a breach of it records.
 *
 * @see org.openphc.cce.common.entity.StepSlaStateTransition
 */
public enum SlaTransitionType {

    /**
     * The step's due date. A step not completed by then is {@link SlaStatus#OVERDUE} with an
     * {@link DeviationType#OVERDUE} deviation. One completed before it breached nothing, so this row
     * records nothing — {@link SlaStatus#MET} is settled from {@code step_instance.due_date} instead.
     */
    DUE_DATE_REACHED(SlaStatus.OVERDUE),

    /**
     * The step's missed date (due date plus tolerance). A mandatory step not completed by then is
     * {@link SlaStatus#MISSED} with a {@link DeviationType#MISSED} deviation. An optional
     * ({@code could}) step breaches nothing by missing it.
     */
    MISSED_DATE_REACHED(SlaStatus.MISSED);

    private final SlaStatus breachStatus;

    SlaTransitionType(SlaStatus breachStatus) {
        this.breachStatus = breachStatus;
    }

    /** The SLA status a step reaches if it had <em>not</em> been completed by this threshold. */
    public SlaStatus breachStatus() {
        return breachStatus;
    }
}
