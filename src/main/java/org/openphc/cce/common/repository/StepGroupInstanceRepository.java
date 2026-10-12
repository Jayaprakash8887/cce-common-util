package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.StepGroupInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StepGroupInstanceRepository extends JpaRepository<StepGroupInstance, UUID> {

    /** A top-level group's occurrence for one cycle. */
    Optional<StepGroupInstance> findByProtocolInstanceIdAndGroupActionIdAndParentGroupInstanceIsNullAndCycleIndex(
            UUID protocolInstanceId, String groupActionId, int cycleIndex);

    /** A nested group's occurrence for one cycle, within one occurrence of its parent. */
    Optional<StepGroupInstance> findByProtocolInstanceIdAndGroupActionIdAndParentGroupInstanceIdAndCycleIndex(
            UUID protocolInstanceId, String groupActionId, UUID parentGroupInstanceId, int cycleIndex);

    /** Every occurrence of one group in an enrollment, in any cycle and under any parent. */
    List<StepGroupInstance> findByProtocolInstanceIdAndGroupActionId(UUID protocolInstanceId, String groupActionId);

    /** The occurrences directly inside one occurrence. */
    List<StepGroupInstance> findByParentGroupInstanceId(UUID parentGroupInstanceId);
}
