package org.openphc.cce.common.sla;

import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.openphc.cce.common.repository.StepSlaStateTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reads a step's SLA thresholds back from its {@code step_sla_state_transition} rows.
 *
 * <p>Read-only, and shared: Step SLA needs a step's deadlines to build the {@code dueDate} and
 * {@code daysOverdue} of an intelligence rule context, and Matcher needs them when scheduling dependent
 * steps. Only the Matcher Service <em>writes</em> the schedule, so the write path deliberately lives
 * there and not here; nothing in this library can invent a deadline.
 *
 * <p>Read from {@code process_by}, which the evaluating service never rewrites, so this returns the
 * original deadline whether or not the transition has already been applied.
 */
@Service
@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
public class SlaThresholdReader {

    private final StepSlaStateTransitionRepository transitionRepository;

    public SlaThresholdReader(StepSlaStateTransitionRepository transitionRepository) {
        this.transitionRepository = transitionRepository;
    }

    /**
     * A step's SLA thresholds. Either may be null — a step created from its own trigger has no
     * tolerance window, and an event-driven step may have no missed date.
     *
     * @param dueDate    when the SLA goes {@code OVERDUE}, or null if it never does
     * @param missedDate when the SLA is settled as {@code MISSED}, or null if it never is
     */
    public record SlaThresholds(OffsetDateTime dueDate, OffsetDateTime missedDate) {

        public static final SlaThresholds NONE = new SlaThresholds(null, null);
    }

    /** The thresholds a step was scheduled with. */
    public SlaThresholds getThresholds(UUID stepInstanceId) {
        List<StepSlaStateTransition> rows = transitionRepository.findByStepInstanceId(stepInstanceId);
        if (rows.isEmpty()) {
            return SlaThresholds.NONE;
        }

        OffsetDateTime dueDate = null;
        OffsetDateTime missedDate = null;
        for (StepSlaStateTransition row : rows) {
            switch (row.getTransitionType()) {
                case DUE_DATE_REACHED -> dueDate = row.getProcessBy();
                case MISSED_DATE_REACHED -> missedDate = row.getProcessBy();
            }
        }
        return new SlaThresholds(dueDate, missedDate);
    }
}
