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
 * Every value here is written by the <strong>Step SLA Service</strong> alone, as it applies
 * {@code step_sla_state_transition} rows. Matcher schedules the thresholds and records the completion,
 * but never judges timeliness — so there is no window in which the two services disagree about a step's
 * SLA, and no rule about which of them may overwrite the other.
 *
 * <p>Paired with {@link StepStatus}, this classifies how timely a completion was:
 * {@code COMPLETED + MET} is on time, {@code COMPLETED + OVERDUE} is late, and
 * {@code COMPLETED + MISSED} is late past the point the step was written off.
 *
 * <h2>Which status may replace which</h2>
 * Each constant carries its place in the forward-only order, so the rule is stated here, next to the
 * statuses it orders, and read through {@link #canReplace} rather than re-derived by each writer. A new
 * status is one constant with its rank: the ranks are spaced so an interim one fits between two
 * existing ones without renumbering, and the constructor arguments are mandatory, so a constant cannot
 * be added without being placed.
 *
 * @see StepStatus
 * @see SlaTransitionType
 */
public enum SlaStatus {

    /**
     * The due threshold passed and the event had not arrived by then. Not terminal: the missed
     * threshold can still move it to {@link #MISSED}.
     */
    OVERDUE(20, false),

    /**
     * The missed threshold passed and the event had not arrived by then. Terminal: a later event still
     * sets {@link StepStatus#COMPLETED}, but the SLA stays missed.
     */
    MISSED(30, false),

    /**
     * The SLA was satisfied — the event arrived before the step's {@code due_date}. Terminal.
     *
     * <p>Written only over null, when the step's {@code MET_CONDITION_REACHED} row is applied. It says
     * the step beat its due date, which a step some deadline has already judged cannot be told
     * retrospectively. Ranked with {@link #MISSED}, so no breach replaces it either.
     */
    MET(30, true);

    /** Position in the forward-only order. Null, "not yet judged", precedes every rank. */
    private final int rank;

    /** Whether this status may be written only over null, never over another verdict. */
    private final boolean onlyFromUnjudged;

    SlaStatus(int rank, boolean onlyFromUnjudged) {
        this.rank = rank;
        this.onlyFromUnjudged = onlyFromUnjudged;
    }

    /**
     * Whether this status may replace {@code current} on a step.
     *
     * <p>Forward-only: a status replaces null or a lower rank, never an equal or higher one — so
     * {@code OVERDUE} can never replace {@code MISSED}, which is what two rows for one step applied out
     * of order after a retry would otherwise do. A status marked only-from-unjudged ({@link #MET})
     * replaces null alone.
     *
     * @param current the step's status now; null when it has not been judged
     */
    public boolean canReplace(SlaStatus current) {
        if (current == null) {
            return true;
        }
        return !onlyFromUnjudged && rank > current.rank;
    }
}
