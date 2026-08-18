package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StepSlaStateTransitionRepository extends JpaRepository<StepSlaStateTransition, UUID> {

    /**
     * A step's scheduled transitions — at most one per {@link
     * org.openphc.cce.common.enums.SlaTransitionType}.
     *
     * <p>Served by the leading column of the {@code (step_instance_id, transition_type)} unique index,
     * so no separate index on {@code step_instance_id} is needed.
     */
    List<StepSlaStateTransition> findByStepInstanceId(UUID stepInstanceId);
}
