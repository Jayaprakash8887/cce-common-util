package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.Deviation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DeviationRepository extends JpaRepository<Deviation, UUID> {
}
