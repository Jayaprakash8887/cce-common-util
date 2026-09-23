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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    public DeviationResult recordDeviation(StepInstance step, DeviationType deviationType) {
        return recordDeviation(step, deviationType, null);
    }

    /**
     * Record a deviation and persist it, with caller-supplied metadata.
     *
     * <p>Intelligence evaluation is deliberately not performed here: the caller fires it only for a
     * freshly created deviation (see {@code created}), which is what keeps a redelivered event from
     * publishing a duplicate intelligence event.
     *
     * @param metadata deviation-type-specific detail, or null. An empty map is stored as null rather
     *                 than as an empty JSON object, so a metadata-less deviation reads the same however
     *                 it was recorded.
     * @return a {@link DeviationResult} — {@code created=true} with the new row, or
     *         {@code created=false} with the pre-existing deviation for this (step, type).
     */
    public DeviationResult recordDeviation(StepInstance step, DeviationType deviationType,
                                           Map<String, Object> metadata) {
        if (metadata != null && metadata.isEmpty()) {
            metadata = null;
        }
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

    /**
     * Record a batch of deviations, with no metadata — {@link #recordDeviation(StepInstance, DeviationType)}
     * for many steps at once.
     *
     * <p>The same one-per-(step, type) guarantee, checked with a single query for the whole batch rather
     * than one per deviation. That is what lets the inserts be batched: a per-deviation check queries the
     * {@code deviation} table while an earlier insert is still pending, which makes Hibernate flush it
     * first, so every insert went out on its own. Here the check runs once, before anything is queued,
     * and the new rows are flushed together at commit. The {@code deviation_step_type_key} unique
     * constraint remains the backstop against a concurrent insert racing past the check.
     *
     * <p>A (step, type) pair repeated within the batch is recorded once; the repeats get
     * {@code created=false} with the same deviation.
     *
     * @return one result per pending deviation, in the order given
     */
    public List<DeviationResult> recordDeviations(List<PendingDeviation> pendingDeviations) {
        if (pendingDeviations.isEmpty()) {
            return List.of();
        }

        Set<UUID> stepIds = pendingDeviations.stream()
                .map(pending -> pending.step().getId())
                .collect(Collectors.toSet());
        Map<StepAndType, Deviation> recorded = new HashMap<>();
        for (Deviation existing : deviationRepository.findByStepInstanceIdIn(stepIds)) {
            recorded.put(new StepAndType(existing.getStepInstance().getId(), existing.getDeviationType()),
                    existing);
        }

        OffsetDateTime detectedAt = OffsetDateTime.now(ZoneOffset.UTC);
        List<DeviationResult> results = new ArrayList<>(pendingDeviations.size());
        List<Deviation> toInsert = new ArrayList<>();
        for (PendingDeviation pending : pendingDeviations) {
            StepAndType key = new StepAndType(pending.step().getId(), pending.deviationType());
            Deviation existing = recorded.get(key);
            if (existing != null) {
                log.debug("Deviation {} already exists for step {} — skipping duplicate creation",
                        pending.deviationType(), pending.step().getId());
                results.add(new DeviationResult(existing, false));
                continue;
            }

            Deviation deviation = Deviation.builder()
                    .stepInstance(pending.step())
                    .deviationType(pending.deviationType())
                    .detectedAt(detectedAt)
                    .build();
            recorded.put(key, deviation);
            toInsert.add(deviation);
            results.add(new DeviationResult(deviation, true));
        }

        deviationRepository.saveAll(toInsert);
        for (Deviation deviation : toInsert) {
            log.info("Recorded {} deviation: deviationId={}, stepId={}, actionId={}",
                    deviation.getDeviationType(), deviation.getId(), deviation.getStepInstance().getId(),
                    deviation.getStepInstance().getActionId());
        }
        return results;
    }

    private record StepAndType(UUID stepInstanceId, DeviationType deviationType) {}
}
