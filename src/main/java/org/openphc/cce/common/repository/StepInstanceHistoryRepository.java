package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.StepInstanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StepInstanceHistoryRepository extends JpaRepository<StepInstanceHistory, Long> {
}
