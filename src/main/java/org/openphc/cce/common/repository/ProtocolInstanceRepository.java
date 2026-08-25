package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProtocolInstanceRepository extends JpaRepository<ProtocolInstance, UUID> {

    Optional<ProtocolInstance> findByPatientIdAndProtocolDefinitionIdAndStatus(
            String patientId, UUID protocolDefinitionId, ProtocolInstanceStatus status);

    long countByStatus(ProtocolInstanceStatus status);
}
