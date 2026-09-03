package org.openphc.cce.common.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourceTypeDetectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ResourceTypeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ResourceTypeDetector();
    }

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    class Recognized {

        @Test
        void readsTheResourceTypeField() {
            assertEquals(ResourceType.Encounter, detector.detect(json("{\"resourceType\":\"Encounter\"}")));
            assertEquals(ResourceType.Observation, detector.detect(json("{\"resourceType\":\"Observation\"}")));
        }

        @Test
        void ignoresEverythingElseInThePayload() {
            JsonNode data = json("""
                    {
                      "resourceType": "Procedure",
                      "status": "completed",
                      "code": {"coding": [{"system": "http://loinc.org", "code": "1234-5"}]}
                    }
                    """);
            assertEquals(ResourceType.Procedure, detector.detect(data));
        }
    }

    @Nested
    class NoUsableType {

        @Test
        void nullNode() {
            assertNull(detector.detect(null));
        }

        @Test
        void jsonNullNode() {
            assertNull(detector.detect(json("null")));
        }

        @Test
        void fieldAbsent() {
            assertNull(detector.detect(json("{\"status\":\"final\"}")));
        }

        @Test
        void fieldNotAString() {
            assertNull(detector.detect(json("{\"resourceType\":123}")));
        }

        @Test
        void fieldBlank() {
            assertNull(detector.detect(json("{\"resourceType\":\"\"}")));
            assertNull(detector.detect(json("{\"resourceType\":\"   \"}")));
        }

        @Test
        void unknownTypeIsNullRatherThanAFailure() {
            // Non-FHIR application/json events reach this same code path in the Matcher Service, so an
            // unrecognized type has to match no trigger rather than fail the consumer. The Collector
            // Service turns the same null into a validation error.
            assertNull(detector.detect(json("{\"resourceType\":\"NotAFhirResource\"}")));
        }

        @Test
        void wrongCaseIsNotAResourceType() {
            // FHIR resource type codes are case-sensitive, and HAPI's enum names match the codes
            // exactly, so a lowercase spelling is a payload defect rather than a variant to accept.
            assertNull(detector.detect(json("{\"resourceType\":\"observation\"}")));
        }
    }
}
