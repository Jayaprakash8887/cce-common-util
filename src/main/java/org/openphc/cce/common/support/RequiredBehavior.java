package org.openphc.cce.common.support;

/**
 * The platform's one reading of a step's {@code requiredBehavior}.
 *
 * <p>Progressive instantiation, SLA scheduling and the SLA judgement all turn on whether a step was
 * required, and they have to agree: a step required by one rule and optional by the next would be
 * created but never judged, or judged against a deadline nothing enforces.
 */
public final class RequiredBehavior {

    /** FHIR's {@code ActionRequiredBehavior} code for work the protocol requires. */
    public static final String MUST = "must";

    private RequiredBehavior() {
    }

    /**
     * Whether the step is mandatory. Only {@code "must"} is: an absent value states no requirement, so
     * it is optional in exactly the way {@code "could"} is, and is treated as such everywhere.
     *
     * <p>What follows from being optional: the step is not pre-created when its predecessor completes,
     * it gets no {@code step_sla_state_transition} rows, and it can be neither {@code OVERDUE} nor
     * {@code MISSED} — there was nothing it was required to do, so there is nothing for it to breach.
     */
    public static boolean isMandatory(String requiredBehavior) {
        return MUST.equals(requiredBehavior);
    }
}
