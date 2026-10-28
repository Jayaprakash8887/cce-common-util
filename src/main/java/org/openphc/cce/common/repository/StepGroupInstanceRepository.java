package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.StepGroupInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StepGroupInstanceRepository extends JpaRepository<StepGroupInstance, UUID> {

    /** Every instance of one group in an enrollment, in any cycle and under any parent. */
    List<StepGroupInstance> findByProtocolInstanceIdAndGroupActionId(UUID protocolInstanceId, String groupActionId);
}
