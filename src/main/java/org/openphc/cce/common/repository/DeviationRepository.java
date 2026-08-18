package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.enums.DeviationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviationRepository extends JpaRepository<Deviation, UUID> {

    /**
     * A step has at most one deviation per type (enforced by the
     * {@code deviation_step_type_key} unique constraint). Used to make deviation
     * creation idempotent against redelivered / concurrent scheduler triggers.
     */
    Optional<Deviation> findByStepInstanceIdAndDeviationType(UUID stepInstanceId, DeviationType deviationType);
}
