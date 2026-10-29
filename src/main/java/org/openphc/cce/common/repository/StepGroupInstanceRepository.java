package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.StepGroupInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StepGroupInstanceRepository extends JpaRepository<StepGroupInstance, UUID> {

    /**
     * A group's latest cycle in an enrollment: its instance with the highest {@code cycle_index} under
     * {@code parentGroupInstanceId}, or at the top level when that is null. Empty when none has been opened.
     *
     * <p>The parent matters for a nested group: its cycles are numbered within each instance of its parent,
     * so the Consultation of the first visit and of the second visit both have a cycle 0. Served by the
     * unique key {@code (protocol_instance_id, group_action_id, parent_group_instance_id, cycle_index)}.
     */
    default Optional<StepGroupInstance> findLatestCycle(UUID protocolInstanceId, String groupActionId,
                                                        UUID parentGroupInstanceId) {
        return parentGroupInstanceId == null
                ? findFirstByProtocolInstanceIdAndGroupActionIdAndParentGroupInstanceIsNullOrderByCycleIndexDesc(
                        protocolInstanceId, groupActionId)
                : findFirstByProtocolInstanceIdAndGroupActionIdAndParentGroupInstanceIdOrderByCycleIndexDesc(
                        protocolInstanceId, groupActionId, parentGroupInstanceId);
    }

    /** {@link #findLatestCycle} for a top-level group. */
    Optional<StepGroupInstance> findFirstByProtocolInstanceIdAndGroupActionIdAndParentGroupInstanceIsNullOrderByCycleIndexDesc(
            UUID protocolInstanceId, String groupActionId);

    /** {@link #findLatestCycle} for a nested group, within one instance of its parent. */
    Optional<StepGroupInstance> findFirstByProtocolInstanceIdAndGroupActionIdAndParentGroupInstanceIdOrderByCycleIndexDesc(
            UUID protocolInstanceId, String groupActionId, UUID parentGroupInstanceId);
}
