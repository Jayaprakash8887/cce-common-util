package org.openphc.cce.common.fhir;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the Protocol Service accepts as a {@code codeFilter.path}. The Matcher Service infers a
 * top-level field's shape from the payload, so the contract is the form of the name, not a list.
 */
class TriggerPathTest {

    @Test
    void topLevelFieldNamesAreMatchable_whateverTheirShape() {
        for (String path : new String[] {"code", "class", "serviceType", "clinicalStatus",
                "verificationStatus", "type", "category", "identifier", "status", "bodySite", "priority"}) {
            assertTrue(TriggerPath.isMatchable(path), path);
        }
    }

    @Test
    void anEmptyPathIsMatchable_itIsTheResourceTypeOnlyTrigger() {
        // A trigger on resource type alone is indexed as a row with empty path, system and code, and the
        // matching query relies on it. Rejecting it as unmatchable would reject a whole trigger style.
        assertTrue(TriggerPath.isMatchable(""));
        assertTrue(TriggerPath.isMatchable(null));
    }

    @Test
    void pathsTheExtractorCannotFollowAreNotMatchable() {
        assertFalse(TriggerPath.isMatchable("Encounter.serviceType"),
                "paths are single field names, not dotted FHIRPath expressions");
        assertFalse(TriggerPath.isMatchable("participant.type"));
        assertFalse(TriggerPath.isMatchable("type[0]"));
        assertFalse(TriggerPath.isMatchable("value[x]"));
        assertFalse(TriggerPath.isMatchable("service type"));
    }
}
