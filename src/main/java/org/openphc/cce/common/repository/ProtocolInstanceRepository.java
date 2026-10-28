package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProtocolInstanceRepository extends JpaRepository<ProtocolInstance, UUID> {

    Optional<ProtocolInstance> findByPatientIdAndProtocolDefinitionIdAndStatus(
            String patientId, UUID protocolDefinitionId, ProtocolInstanceStatus status);

    long countByStatus(ProtocolInstanceStatus status);

    /**
     * Lock an enrollment's row until the transaction ends. Step group decisions — opening an
     * instance, deciding it is complete — take it first, so two events for one patient processed at
     * once cannot each miss the other's step and leave a group never completed or opened twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProtocolInstance p WHERE p.id = :id")
    Optional<ProtocolInstance> findByIdForUpdate(@Param("id") UUID id);
}
