package org.openphc.cce.common.history;

import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.ProtocolInstanceHistory;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.entity.StepInstanceHistory;
import org.openphc.cce.common.repository.ProtocolInstanceHistoryRepository;
import org.openphc.cce.common.repository.StepInstanceHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Writes append-only state-transition history for protocol and step instances.
 *
 * <p>Callers invoke a {@code record*} method immediately after each status/state change. Because
 * these methods run with {@link Propagation#MANDATORY} inside the caller's already-open transaction,
 * the history INSERT commits atomically with the base-row change — a no-gap, all-or-nothing
 * guarantee. Any code path that mutates a lifecycle column MUST call the matching method here, or
 * that transition will be missing from history.
 *
 * <p>Rows are only ever INSERTed; the history tables are never updated or deleted.
 *
 * <h2>Two writers, by design</h2>
 * This lives in the shared library rather than in one service because both write here. Matcher records
 * enrolment, step creation and completion; the Step SLA Service records each {@code sla_status} it
 * applies. Before it did, every time-driven transition was missing from the history — a step that went
 * overdue and was never completed had one row (its creation) instead of three, and the table's claim to
 * be point-in-time-reconstructible did not hold.
 *
 * <p>Append-only is what makes the second writer safe: the two services insert disjoint rows and never
 * update each other's, so this is not a shared mutable column.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StateTransitionHistoryWriter {

    private final ProtocolInstanceHistoryRepository protocolInstanceHistoryRepository;
    private final StepInstanceHistoryRepository stepInstanceHistoryRepository;

    public StateTransitionHistoryWriter(ProtocolInstanceHistoryRepository protocolInstanceHistoryRepository,
                               StepInstanceHistoryRepository stepInstanceHistoryRepository) {
        this.protocolInstanceHistoryRepository = protocolInstanceHistoryRepository;
        this.stepInstanceHistoryRepository = stepInstanceHistoryRepository;
    }

    /**
     * Record the current status of a protocol instance as a history row.
     * Call this right after persisting an enrollment or a status transition.
     *
     * @param protocolInstance the protocol instance whose status was just (re)set
     * @param changedAt        the moment the transition took effect
     */
    public void recordProtocolInstanceTransition(ProtocolInstance protocolInstance, OffsetDateTime changedAt) {
        protocolInstanceHistoryRepository.save(ProtocolInstanceHistory.builder()
                .protocolInstanceId(protocolInstance.getId())
                .status(protocolInstance.getStatus().name())
                .changedAt(changedAt)
                .build());
    }

    /**
     * Record the current step status and SLA status of a step instance as a history row.
     * Call this right after persisting a step creation or a transition of either status.
     *
     * @param stepInstance the step instance whose status was just (re)set
     * @param changedAt    the moment the transition took effect
     */
    public void recordStepInstanceTransition(StepInstance stepInstance, OffsetDateTime changedAt) {
        stepInstanceHistoryRepository.save(StepInstanceHistory.builder()
                .stepInstanceId(stepInstance.getId())
                .stepStatus(stepInstance.getStepStatus().name())
                // Null until a threshold falls due, and permanently null for a step with no SLA.
                .slaStatus(stepInstance.getSlaStatus() != null ? stepInstance.getSlaStatus().name() : null)
                .changedAt(changedAt)
                .build());
    }
}
