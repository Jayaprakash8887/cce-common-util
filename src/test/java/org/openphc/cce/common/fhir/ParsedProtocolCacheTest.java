package org.openphc.cce.common.fhir;

import org.hl7.fhir.r4.model.PlanDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParsedProtocolCacheTest {

    @Mock
    private PlanDefinitionParser planDefinitionParser;

    private static final String JSON = "{\"resourceType\":\"PlanDefinition\"}";

    private PlanDefinitionParser.StepMetadata step(String id) {
        return new PlanDefinitionParser.StepMetadata(
                id, id.toUpperCase(), List.of(), List.of(), null, null, null, List.of());
    }

    private void stubParse(PlanDefinitionParser.StepMetadata... steps) {
        PlanDefinition planDefinition = new PlanDefinition();
        when(planDefinitionParser.parse(anyString())).thenReturn(planDefinition);
        when(planDefinitionParser.extractSteps(planDefinition)).thenReturn(List.of(steps));
    }

    @Test
    void parsesOnFirstUseAndDerivesTheDependencyGraph() {
        stubParse(step("a"), step("b"));
        ParsedProtocolCache cache = new ParsedProtocolCache(planDefinitionParser, 256);

        ParsedProtocolCache.ParsedProtocol parsed = cache.get(UUID.randomUUID(), () -> JSON);

        assertEquals(2, parsed.steps().size());
        assertNotNull(parsed.dependencyGraph());
        assertEquals("a", parsed.step("a").id());
        assertNull(parsed.step("nosuch"), "an unknown action id resolves to null, not an exception");
    }

    @Test
    void aHitNeverAsksForTheJson() {
        // The point of the supplier: callers hold a JPA entity whose JSONB column costs a full
        // serialization to render, and the hot path would pay it on every lookup.
        stubParse(step("a"));
        ParsedProtocolCache cache = new ParsedProtocolCache(planDefinitionParser, 256);
        UUID id = UUID.randomUUID();
        AtomicInteger supplied = new AtomicInteger();

        cache.get(id, () -> { supplied.incrementAndGet(); return JSON; });
        cache.get(id, () -> { supplied.incrementAndGet(); return JSON; });
        cache.get(id, () -> { supplied.incrementAndGet(); return JSON; });

        assertEquals(1, supplied.get());
        verify(planDefinitionParser, times(1)).parse(anyString());
    }

    @Test
    void distinctProtocolsAreCachedSeparately() {
        stubParse(step("a"));
        ParsedProtocolCache cache = new ParsedProtocolCache(planDefinitionParser, 256);

        cache.get(UUID.randomUUID(), () -> JSON);
        cache.get(UUID.randomUUID(), () -> JSON);

        verify(planDefinitionParser, times(2)).parse(anyString());
    }

    @Test
    void evictionForcesAReparse() {
        stubParse(step("a"));
        ParsedProtocolCache cache = new ParsedProtocolCache(planDefinitionParser, 256);
        UUID id = UUID.randomUUID();

        cache.get(id, () -> JSON);
        cache.evict(id);
        cache.get(id, () -> JSON);

        verify(planDefinitionParser, times(2)).parse(anyString());
    }

    @Test
    void evictingSomethingAbsentIsANoOp() {
        ParsedProtocolCache cache = new ParsedProtocolCache(planDefinitionParser, 256);

        assertDoesNotThrow(() -> cache.evict(UUID.randomUUID()));
        verifyNoInteractions(planDefinitionParser);
    }

    @Test
    void reachingTheLimitClearsRatherThanGrowingWithoutBound() {
        stubParse(step("a"));
        ParsedProtocolCache cache = new ParsedProtocolCache(planDefinitionParser, 2);
        UUID first = UUID.randomUUID();

        cache.get(first, () -> JSON);
        cache.get(UUID.randomUUID(), () -> JSON);
        // the third arrival trips the limit and drops everything, including the first
        cache.get(UUID.randomUUID(), () -> JSON);
        cache.get(first, () -> JSON);

        assertEquals(4, mockingDetails(planDefinitionParser).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("parse")).count());
    }
}
