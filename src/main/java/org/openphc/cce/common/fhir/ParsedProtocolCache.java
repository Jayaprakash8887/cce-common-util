package org.openphc.cce.common.fhir;

import org.hl7.fhir.r4.model.PlanDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the derived form of each protocol definition — its flattened steps and the normalized
 * dependency graph — keyed by protocol definition id.
 *
 * <p>Deriving that form costs a FHIR JSON parse plus a full tree flatten, and it is needed
 * repeatedly for the same protocol: once per inbound event to resolve matched step metadata, again
 * on every completion for order checks, progressive instantiation and backfill, and again for each
 * intelligence evaluation.
 *
 * <p>Entries are bounded by {@code cce.protocol.parsed-cache-size}. No consumer of this cache writes
 * protocol definitions, so it has no local change to invalidate on; instead
 * {@code ProtocolDefinitionService#refreshProtocolCaches()} polls for
 * definitions the protocol-management service has added, changed or retired and calls {@link #evict}
 * for each. Staleness is therefore bounded by that poll interval rather than by process lifetime.
 */
@Component
public class ParsedProtocolCache {

    private static final Logger log = LoggerFactory.getLogger(ParsedProtocolCache.class);

    /**
     * A protocol definition in the shape its consumers actually use.
     *
     * @param steps           every step, with nested sub-steps flattened to peers
     * @param dependencyGraph the {@code relatedAction} edges normalized into one directed graph
     */
    public record ParsedProtocol(
            List<PlanDefinitionParser.StepMetadata> steps,
            PlanDefinitionParser.DependencyGraph dependencyGraph
    ) {
        /** The metadata for one step, or null when the definition declares no such action. */
        public PlanDefinitionParser.StepMetadata step(String actionId) {
            return PlanDefinitionParser.findStep(steps, actionId);
        }
    }

    private final PlanDefinitionParser planDefinitionParser;
    private final int maxSize;
    private final Map<UUID, ParsedProtocol> cache = new ConcurrentHashMap<>();

    public ParsedProtocolCache(PlanDefinitionParser planDefinitionParser,
                               @Value("${cce.protocol.parsed-cache-size:256}") int maxSize) {
        this.planDefinitionParser = planDefinitionParser;
        this.maxSize = maxSize;
    }

    /**
     * The derived form of a protocol definition, parsing it on first use.
     *
     * <p>The JSON arrives as a supplier so a cache hit never pays for it. Callers typically hold a
     * JPA entity whose JSONB column costs a full serialization to render as a string, and on the hot
     * path — once per inbound event, again per completion, again per intelligence evaluation — that
     * would dominate the lookup it is meant to avoid.
     *
     * @param protocolDefinitionId the definition's id — the cache key
     * @param definitionJson       supplies the stored FHIR PlanDefinition JSON, called only on a miss
     */
    public ParsedProtocol get(UUID protocolDefinitionId, java.util.function.Supplier<String> definitionJson) {
        UUID id = protocolDefinitionId;
        ParsedProtocol cached = cache.get(id);
        if (cached != null) {
            return cached;
        }

        // Bound the cache here rather than inside the mapping function below: ConcurrentHashMap
        // forbids a mapping function from modifying the map it is computing on, and clear() would
        // contend for the bin lock computeIfAbsent already holds.
        if (cache.size() >= maxSize) {
            log.info("Parsed-protocol cache reached its {}-entry limit — clearing", maxSize);
            cache.clear();
        }

        return cache.computeIfAbsent(id, key -> parse(definitionJson.get()));
    }

    /** Drop a protocol's derived form, so the next read re-derives it from the stored JSONB. */
    public void evict(UUID protocolDefinitionId) {
        if (cache.remove(protocolDefinitionId) != null) {
            log.debug("Evicted parsed protocol {}", protocolDefinitionId);
        }
    }

    private ParsedProtocol parse(String definitionJson) {
        PlanDefinition planDefinition = planDefinitionParser.parse(definitionJson);
        List<PlanDefinitionParser.StepMetadata> steps = planDefinitionParser.extractSteps(planDefinition);
        return new ParsedProtocol(steps, PlanDefinitionParser.buildDependencyGraph(steps));
    }
}
