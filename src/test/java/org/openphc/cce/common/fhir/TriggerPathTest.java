package org.openphc.cce.common.fhir;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The matchable surface of an inbound payload. These tests are the contract between the Protocol
 * Service, which rejects a trigger on anything not listed here, and the Matcher Service, which reads
 * exactly these fields out of an event.
 */
class TriggerPathTest {

    @Test
    void everyPathTheMatcherReadsIsMatchable() {
        for (TriggerPath path : TriggerPath.values()) {
            assertTrue(TriggerPath.isMatchable(path.fhirPath()),
                    path.fhirPath() + " is a member, so a trigger on it must be accepted");
        }
    }

    @Test
    void serviceTypeIsMatchable() {
        // The path the reference ANC protocol's enrolment trigger filters on. It was indexed but never
        // extracted, so that trigger could not fire; this is the regression guard.
        assertTrue(TriggerPath.isMatchable("serviceType"));
    }

    @Test
    void anEmptyPathIsMatchable_itIsTheResourceTypeOnlyTrigger() {
        // A trigger on resource type alone is indexed as a row with empty path, system and code, and the
        // matching query relies on it. Rejecting it as unmatchable would reject a whole trigger style.
        assertTrue(TriggerPath.isMatchable(""));
        assertTrue(TriggerPath.isMatchable(null));
    }

    @Test
    void aPathNoEventIsReadForIsNotMatchable() {
        assertFalse(TriggerPath.isMatchable("bodySite"));
        assertFalse(TriggerPath.isMatchable("performerType"));
        assertFalse(TriggerPath.isMatchable("Encounter.serviceType"),
                "paths are single field names, not dotted FHIRPath expressions");
    }

    @Test
    void pathMatchingIsCaseSensitive() {
        // FHIR element names are case-sensitive, and so is the index lookup that pairs a trigger row
        // with an extracted triple — accepting 'servicetype' at load would produce a row nothing matches.
        assertFalse(TriggerPath.isMatchable("servicetype"));
        assertFalse(TriggerPath.isMatchable("Status"));
    }

    @Test
    void everyMemberCarriesAShape_soTheExtractorCanReadIt() {
        for (TriggerPath path : TriggerPath.values()) {
            assertNotNull(path.shape(), path.fhirPath() + " needs a shape or the extractor skips it");
        }
    }

    @Test
    void matchablePathsNamesThemAllForErrorMessages() {
        String listed = TriggerPath.matchablePaths();
        for (TriggerPath path : TriggerPath.values()) {
            assertTrue(listed.contains(path.fhirPath()),
                    "a rejection message has to tell the author what was allowed: " + path.fhirPath());
        }
    }
}
