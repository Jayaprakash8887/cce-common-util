package org.openphc.cce.common.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These beans are shared by every service, so their configuration is a contract of this library:
 * a timestamp that serializes as an epoch number, or a FHIR context on the wrong version, would
 * change every service's wire format at once.
 */
class SharedConfigTest {

    @Test
    void objectMapperWritesTimestampsAsIso8601() throws Exception {
        ObjectMapper mapper = new AppConfig().objectMapper();

        String json = mapper.writeValueAsString(
                OffsetDateTime.of(2026, 4, 1, 10, 30, 0, 0, ZoneOffset.UTC));

        assertTrue(json.startsWith("\"2026-04-01T10:30"), json);
        assertFalse(json.matches("^\\d.*"), "must not fall back to an epoch number: " + json);
    }

    @Test
    void objectMapperRoundTripsAnOffsetDateTime() throws Exception {
        ObjectMapper mapper = new AppConfig().objectMapper();
        OffsetDateTime original = OffsetDateTime.of(2026, 4, 1, 10, 30, 0, 0, ZoneOffset.UTC);

        String json = mapper.writeValueAsString(original);

        assertEquals(original.toInstant(),
                mapper.readValue(json, OffsetDateTime.class).toInstant());
    }

    @Test
    void fhirContextIsR4() {
        FhirContext context = new FhirConfig().fhirContext();

        assertEquals(FhirVersionEnum.R4, context.getVersion().getVersion());
    }

    @Test
    void kafkaRetryDefaultsAreUsableWithoutConfiguration() {
        KafkaRetryProperties properties = new KafkaRetryProperties();

        assertEquals(3, properties.getMaxAttempts());
        assertEquals(1000, properties.getBackoffIntervalMs());

        properties.setMaxAttempts(5);
        properties.setBackoffIntervalMs(2000);
        assertEquals(5, properties.getMaxAttempts());
        assertEquals(2000, properties.getBackoffIntervalMs());
    }
}
