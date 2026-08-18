package org.openphc.cce.common.repository;

import org.openphc.cce.common.entity.ActionDefinition;
import org.openphc.cce.common.enums.ActionDefinitionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActionDefinitionRepository extends JpaRepository<ActionDefinition, UUID> {

    // Read-side finders backing the action-definition management API.
    List<ActionDefinition> findByStatus(ActionDefinitionStatus status);

    Page<ActionDefinition> findByStatus(ActionDefinitionStatus status, Pageable pageable);

    List<ActionDefinition> findByCanonicalUrl(String canonicalUrl);

    Optional<ActionDefinition> findByCanonicalUrlAndVersion(String canonicalUrl, String version);

    /** Backs the {@code cce.action.definitions.active} gauge. */
    long countByStatus(ActionDefinitionStatus status);
}
