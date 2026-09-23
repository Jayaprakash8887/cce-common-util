package org.openphc.cce.common.deviation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.repository.DeviationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class DeviationRecorder {

    private static final Logger log = LoggerFactory.getLogger(DeviationRecorder.class);

    private final DeviationRepository deviationRepository;
    private final ObjectMapper objectMapper;

    public DeviationRecorder(DeviationRepository deviationRepository,
                            ObjectMapper objectMapper) {
        this.deviationRepository = deviationRepository;
        this.objectMapper = objectMapper;
    }

    /** One deviation to record: this step, this type, no metadata. */
    public record PendingDeviation(StepInstance step, DeviationType deviationType) {}

    /**
     * Record a deviation for a step, with no metadata.
     *
     * <p>Which types reach here depends on the caller: the Matcher Service records only
     * {@link DeviationType#ORDER_VIOLATION}, detected from the event itself, while the time-driven
     * {@code OVERDUE} and {@code MISSED} deviations come from the Step SLA Service, which owns the
     * thresholds that produce them.
     */
    public Deviation recordDeviation(StepInstance step, DeviationType deviationType) {
        return recordDeviation(step, deviationType, null);
    }

    /**
     * Record a deviation and persist it, with caller-supplied metadata.
     *
     * <h2>No existence check</h2>
     * A step has at most one deviation per type, and every caller already guarantees it before calling:
     * an {@code OVERDUE} or {@code MISSED} is raised only when its {@code sla_status} write succeeds,
     * which {@link org.openphc.cce.common.enums.SlaStatus#canReplace} allows once, and an
     * {@code ORDER_VIOLATION} only when a step completes, which it does once. So nothing looks for an
     * existing row first. The {@code deviation_step_type_key} unique constraint is the guarantee: a
     * duplicate means one of those rules broke, and it fails the transaction rather than being quietly
     * skipped. A pre-check would not have stopped two concurrent inserts anyway — both would find
     * nothing.
     *
     * <p>Intelligence evaluation is deliberately not performed here; the caller fires it for the
     * deviation returned.
     *
     * @param metadata deviation-type-specific detail, or null. An empty map is stored as null rather
     *                 than as an empty JSON object, so a metadata-less deviation reads the same however
     *                 it was recorded.
     * @return the newly inserted deviation
     */
    public Deviation recordDeviation(StepInstance step, DeviationType deviationType,
                                     Map<String, Object> metadata) {
        if (metadata != null && metadata.isEmpty()) {
            metadata = null;
        }

        Deviation deviation = Deviation.builder()
                .stepInstance(step)
                .deviationType(deviationType)
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .metadata(metadata != null ? objectMapper.valueToTree(metadata) : null)
                .build();

        deviation = deviationRepository.save(deviation);

        log.info("Recorded {} deviation: deviationId={}, stepId={}, actionId={}",
                deviationType, deviation.getId(), step.getId(), step.getActionId());

        return deviation;
    }

    /**
     * Record a batch of deviations, with no metadata — {@link #recordDeviation(StepInstance, DeviationType)}
     * for many steps at once, and with the same reliance on the unique constraint.
     *
     * <p>One {@code saveAll}, so Hibernate sends the inserts as one JDBC batch at commit rather than
     * one statement each.
     *
     * @return the newly inserted deviations, in the order given
     */
    public List<Deviation> recordDeviations(List<PendingDeviation> pendingDeviations) {
        if (pendingDeviations.isEmpty()) {
            return List.of();
        }

        OffsetDateTime detectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        List<Deviation> toInsert = pendingDeviations.stream()
                .map(pending -> Deviation.builder()
                        .stepInstance(pending.step())
                        .deviationType(pending.deviationType())
                        .detectedAt(detectedAt)
                        .build())
                .toList();

        List<Deviation> inserted = deviationRepository.saveAll(toInsert);
        for (Deviation deviation : inserted) {
            log.info("Recorded {} deviation: deviationId={}, stepId={}, actionId={}",
                    deviation.getDeviationType(), deviation.getId(), deviation.getStepInstance().getId(),
                    deviation.getStepInstance().getActionId());
        }
        return inserted;
    }
}
