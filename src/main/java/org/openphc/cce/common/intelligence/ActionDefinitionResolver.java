package org.openphc.cce.common.intelligence;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.common.entity.ActionDefinition;
import org.openphc.cce.common.repository.ActionDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to action definitions.
 *
 * <p>Matcher resolves the {@code definitionCanonical} an intelligence action points at; creating and
 * retiring these rows belongs to the protocol-management service.
 */
@Service
@Transactional(readOnly = true)
public class ActionDefinitionResolver {

    private final ActionDefinitionRepository actionDefinitionRepository;

    public ActionDefinitionResolver(ActionDefinitionRepository actionDefinitionRepository) {
        this.actionDefinitionRepository = actionDefinitionRepository;
    }

    /**
     * Resolve an ActionDefinition by canonical reference (url|version).
     *
     * @param canonical the canonical reference in "url|version" format
     * @return the matching ActionDefinition
     * @throws IllegalArgumentException if the canonical format is invalid
     * @throws EntityNotFoundException if no matching ActionDefinition is found
     */
    public ActionDefinition resolveByCanonical(String canonical) {
        int separatorIndex = canonical.lastIndexOf('|');
        if (separatorIndex <= 0 || separatorIndex >= canonical.length() - 1) {
            throw new IllegalArgumentException("Invalid canonical format, expected 'url|version': " + canonical);
        }

        String url = canonical.substring(0, separatorIndex);
        String version = canonical.substring(separatorIndex + 1);

        return actionDefinitionRepository.findByCanonicalUrlAndVersion(url, version)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Action definition not found for canonical: " + canonical));
    }
}
