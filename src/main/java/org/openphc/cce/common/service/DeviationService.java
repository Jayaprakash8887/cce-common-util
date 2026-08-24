package org.openphc.cce.common.service;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class DeviationService {

    private static final Logger log = LoggerFactory.getLogger(DeviationService.class);

    private final DeviationRepository deviationRepository;
    private final ObjectMapper objectMapper;

    public DeviationService(DeviationRepository deviationRepository,
                            ObjectMapper objectMapper) {
        this.deviationRepository = deviationRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Outcome of a deviation creation request.
     *
     * @param deviation the deviation — the newly inserted row, or the pre-existing one
     *                  when a deviation of this type already existed for the step.
     * @param created   {@code true} if a new row was inserted; {@code false} if a
     *                  deviation of this type already existed (redelivered or concurrent
     *                  trigger). Callers must only fire one-time side effects — e.g.
     *                  intelligence evaluation — when this is {@code true}.
     */
    public record DeviationResult(Deviation deviation, boolean created) {}

    /**
     * Record a deviation for a step.
     *
     * <p>In this service that is only ever {@link DeviationType#ORDER_VIOLATION} — the time-driven
     * {@code OVERDUE} and {@code MISSED} deviations are recorded by the SLA transition evaluator, which
     * owns the thresholds that produce them.
     */
    public DeviationResult createDeviation(StepInstance step, DeviationType deviationType) {
        return createDeviation(step, deviationType, null);
    }

    /**
     * Record a deviation with caller-supplied metadata.
     */
    public DeviationResult createDeviation(StepInstance step, DeviationType deviationType,
                                     Map<String, Object> additionalMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (additionalMetadata != null) {
            metadata.putAll(additionalMetadata);
        }

        return recordDeviation(step, deviationType, metadata.isEmpty() ? null : metadata);
    }

    /**
     * Record a deviation and persist it.
     *
     * <p>Intelligence evaluation is deliberately not performed here: the caller fires it only for a
     * freshly created deviation (see {@code created}), which is what keeps a redelivered event from
     * publishing a duplicate intelligence event.
     *
     * @return a {@link DeviationResult} — {@code created=true} with the new row, or
     *         {@code created=false} with the pre-existing deviation for this (step, type).
     */
    private DeviationResult recordDeviation(StepInstance step, DeviationType deviationType,
                                     Map<String, Object> metadata) {
        // Idempotency guard: a step has at most one deviation per type. A redelivered inbound event
        // (Kafka is at-least-once) or a concurrent consumer thread can
        // reach this point for the same (step, type); return the existing deviation instead
        // of inserting a duplicate. The deviation_step_type_key unique constraint is the
        // ultimate backstop if two inserts genuinely race past this check.
        Optional<Deviation> existing = deviationRepository
                .findByStepInstanceIdAndDeviationType(step.getId(), deviationType);
        if (existing.isPresent()) {
            log.debug("Deviation {} already exists for step {} — skipping duplicate creation",
                    deviationType, step.getId());
            return new DeviationResult(existing.get(), false);
        }

        OffsetDateTime detectedAt = OffsetDateTime.now(ZoneOffset.UTC);

        Deviation deviation = Deviation.builder()
                .stepInstance(step)
                .deviationType(deviationType)
                .detectedAt(detectedAt)
                .metadata(metadata != null ? objectMapper.valueToTree(metadata) : null)
                .build();

        deviation = deviationRepository.save(deviation);

        log.info("Recorded {} deviation: deviationId={}, stepId={}, actionId={}",
                deviationType, deviation.getId(), step.getId(), step.getActionId());

        return new DeviationResult(deviation, true);
    }
}
