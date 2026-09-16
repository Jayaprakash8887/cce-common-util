package org.openphc.cce.common.enums;

/**
 * A scheduled SLA transition: one row in {@code step_sla_state_transition} stands for one of these.
 *
 * <p>Two of them are deadlines the step may cross — the due date and the missed date — and one is a
 * condition the step has already satisfied: {@link #MET_CONDITION_REACHED}, written when the work is
 * recorded before the due date. All three are schedules in the same sense: a row says <em>there is a
 * verdict to reach here</em>, and the Step SLA Service reaches it when the row comes round.
 *
 * <p>The names describe what the row stands for, not a from/to pair. A crossed threshold implies no
 * single destination — a step that beat its due date records nothing when that row is applied — so what
 * a row records is which point in the SLA was reached, and the verdict is read from
 * {@code step_instance.sla_status}.
 *
 * <p>These names are the contract between the two services: Matcher writes them to
 * {@code step_sla_state_transition.transition_type}, and the Step SLA Service reads them to know what
 * the row is asking it to decide.
 *
 * @see org.openphc.cce.common.entity.StepSlaStateTransition
 */
public enum SlaTransitionType {

    /**
     * The step's due date. A step not completed by then is {@link SlaStatus#OVERDUE} with an
     * {@link DeviationType#OVERDUE} deviation. One completed before it breached nothing, so this row
     * records nothing — the step's own {@link #MET_CONDITION_REACHED} row carries that verdict.
     */
    DUE_DATE_REACHED(SlaStatus.OVERDUE, DeviationType.OVERDUE),

    /**
     * The step's missed date (due date plus tolerance). A step not completed by then is
     * {@link SlaStatus#MISSED} with a {@link DeviationType#MISSED} deviation.
     */
    MISSED_DATE_REACHED(SlaStatus.MISSED, DeviationType.MISSED),

    /**
     * The step's work was recorded before its due date. Unlike the other two this row is not scheduled
     * when the step is created — nothing is known then — but written by Matcher at the moment the
     * completing event lands, with a {@code process_by} of that {@code completed_at}. So it is due
     * immediately and the Step SLA Service records {@link SlaStatus#MET} on its next cycle, rather than
     * the step waiting unjudged until a due date that may be weeks away.
     *
     * <p>Carries no breach status and no deviation: nothing is breached by work arriving early, and
     * there is nothing deviant about it.
     */
    MET_CONDITION_REACHED(null, null);

    private final SlaStatus breachStatus;
    private final DeviationType breachDeviation;

    SlaTransitionType(SlaStatus breachStatus, DeviationType breachDeviation) {
        this.breachStatus = breachStatus;
        this.breachDeviation = breachDeviation;
    }

    /**
     * The SLA status a step reaches if it had <em>not</em> been completed by this threshold, or null
     * for {@link #MET_CONDITION_REACHED}, which is not a threshold and cannot be breached.
     */
    public SlaStatus breachStatus() {
        return breachStatus;
    }

    /** The deviation a breach of this threshold records, or null where there is no breach to record. */
    public DeviationType breachDeviation() {
        return breachDeviation;
    }
}
