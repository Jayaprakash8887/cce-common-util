package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.StepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StepInstanceRepository extends JpaRepository<StepInstance, UUID> {

    List<StepInstance> findByProtocolInstanceId(UUID protocolInstanceId);

    /**
     * The steps for an action that are still awaiting their event — the rows a late-arriving event
     * should complete. Scoped by {@link StepStatus} alone, so a step whose SLA is already
     * {@code MISSED} is picked up rather than a duplicate row being created for it.
     */
    List<StepInstance> findByProtocolInstanceIdAndActionIdAndStepStatus(
            UUID protocolInstanceId, String actionId, StepStatus stepStatus);

    boolean existsByProtocolInstanceIdAndActionId(UUID protocolInstanceId, String actionId);
}
