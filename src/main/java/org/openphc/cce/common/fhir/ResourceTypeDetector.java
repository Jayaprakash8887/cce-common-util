package org.openphc.cce.common.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code resourceType} discriminator off an inbound FHIR JSON payload and maps it to the
 * HAPI R4 {@link ResourceType} enum.
 *
 * <p>Small enough to inline, which is exactly why it had been inlined twice. The Collector Service
 * needs the type to validate the payload and to pick a clinical-time field; the Matcher Service needs
 * it as the first coordinate of a Tier 1 trigger lookup. Both had their own copy, and the copies did
 * not agree: one resolved through {@code ResourceType.fromCode}, the other through
 * {@code ResourceType.valueOf}, and only one tolerated a null node. They happen to answer alike for
 * every R4 type, so nothing was visibly broken — the drift was in what each service treated as
 * "unrecognized", which is the decision this class exists to make once.
 *
 * <p>It belongs here rather than in either service because the library already owns the value's
 * consumers: {@link ClinicalEventTimeExtractor#extract(ResourceType, JsonNode)} takes one, and
 * {@link PlanDefinitionParser} produces the {@code TriggerIndex} rows the type is matched against.
 * The library defined what a resource type means for this pipeline while each service decided
 * independently how to read one.
 *
 * <p>Never throws. A payload with no usable {@code resourceType} yields {@code null}, and the caller
 * decides what that means: the Collector turns it into a validation error, while the Matcher lets the
 * event match no trigger — non-FHIR {@code application/json} events reach the same code path and must
 * not fail it.
 */
@Component
public class ResourceTypeDetector {

    private static final Logger log = LoggerFactory.getLogger(ResourceTypeDetector.class);

    private static final String RESOURCE_TYPE_FIELD = "resourceType";

    /**
     * Detect the FHIR resource type of a payload.
     *
     * @param data the FHIR resource as a Jackson node; may be {@code null} or a JSON null
     * @return the {@link ResourceType}, or {@code null} when the {@code resourceType} field is
     *         absent, not a string, blank, or not a known FHIR R4 resource type
     */
    public ResourceType detect(JsonNode data) {
        if (data == null || data.isNull()) {
            return null;
        }
        JsonNode resourceType = data.get(RESOURCE_TYPE_FIELD);
        if (resourceType == null || !resourceType.isTextual()) {
            return null;
        }
        String code = resourceType.asText();
        try {
            return ResourceType.valueOf(code);
        } catch (IllegalArgumentException e) {
            log.debug("Unrecognized FHIR resourceType '{}'", code);
            return null;
        }
    }
}
