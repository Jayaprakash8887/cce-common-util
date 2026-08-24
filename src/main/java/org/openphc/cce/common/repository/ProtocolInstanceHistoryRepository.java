package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.ProtocolInstanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProtocolInstanceHistoryRepository extends JpaRepository<ProtocolInstanceHistory, Long> {
}
