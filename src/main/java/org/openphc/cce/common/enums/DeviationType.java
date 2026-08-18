package org.openphc.cce.common.enums;

public enum DeviationType {

    /**
     * The step's due threshold passed without its event arriving — its SLA reached
     * {@link SlaStatus#OVERDUE}.
     *
     * <p>Recorded by the service that evaluates {@code step_sla_state_transition}, not here. Matcher
     * neither creates nor reads it; the value exists so the shared {@code deviation} table — whose
     * schema this service owns — accepts it.
     */
    OVERDUE,

    /**
     * The step's missed threshold passed without its event arriving — its SLA was written off as
     * {@link SlaStatus#MISSED}. Raised only for mandatory steps: an optional ("could") step breaches
     * nothing by never arriving.
     *
     * <p>Also recorded by the evaluating service, for the same reason as {@link #OVERDUE}.
     */
    MISSED,

    /**
     * A step completed while one of its mandatory prerequisites was still outstanding. Detected and
     * recorded <em>here</em>, on completion — it is not time-driven, so it does not belong to the
     * evaluating service.
     */
    ORDER_VIOLATION
}
