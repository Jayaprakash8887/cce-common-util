package org.openphc.cce.common.fhir;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The {@code codeFilter.path} values a trigger can be written against — the whole matchable surface of
 * an inbound FHIR payload.
 *
 * <p>This enum exists because the two halves of Tier 1 matching used to hold the list separately. The
 * Protocol Service indexes whatever path a PlanDefinition declares, while the Matcher Service reads a
 * fixed set of fields out of each event; a path in the first list and not the second was indexed and
 * then never matched. That is not a weaker trigger but a dead one — Tier 1 requires <em>every</em>
 * codeFilter of an action to match, so one unmatchable path disables the action outright. It happened:
 * the reference ANC protocol's enrolment trigger filtered on {@code serviceType}, which nothing
 * extracted, and so could never fire.
 *
 * <p>So the list lives here once, and both sides use it: {@code PlanDefinitionParser.validateTriggers}
 * rejects a protocol that names anything else, and the Matcher Service's {@code EventCodesExtractor}
 * drives its extraction from these members rather than repeating them.
 *
 * <p><strong>Adding a path</strong> means adding a member here. The extractor picks it up from
 * {@link #shape()} and the validator starts accepting it in the same change, which is the point.
 */
public enum TriggerPath {

    /** {@code Observation.code}, {@code Procedure.code}, … */
    CODE("code", Shape.CODEABLE_CONCEPT),

    /** {@code Encounter.class} — the encounter classification. */
    CLASS("class", Shape.CODEABLE_CONCEPT),

    /** {@code Encounter.serviceType} — the service the encounter delivered. */
    SERVICE_TYPE("serviceType", Shape.CODEABLE_CONCEPT),

    /** {@code Condition.clinicalStatus}. */
    CLINICAL_STATUS("clinicalStatus", Shape.CODEABLE_CONCEPT),

    /** {@code Condition.verificationStatus}. */
    VERIFICATION_STATUS("verificationStatus", Shape.CODEABLE_CONCEPT),

    /** {@code Encounter.type[]}, and resources where the field is 0..1. */
    TYPE("type", Shape.CODEABLE_CONCEPT_ARRAY),

    /** {@code Observation.category[]}, and resources where the field is 0..1. */
    CATEGORY("category", Shape.CODEABLE_CONCEPT_ARRAY),

    /** {@code identifier[]} — matched on {@code system} and {@code value} rather than a coding. */
    IDENTIFIER("identifier", Shape.IDENTIFIER_ARRAY),

    /** {@code status} — a plain code with no system, so it indexes with an empty {@code code_system}. */
    STATUS("status", Shape.PLAIN_STRING);

    /**
     * How the field is shaped in the payload, which is what decides how a value is read out of it. Each
     * shape is one branch of the extractor.
     */
    public enum Shape {
        /** A single CodeableConcept: read {@code coding[*]}. */
        CODEABLE_CONCEPT,
        /** An array of CodeableConcepts, falling back to a single one where the field is 0..1. */
        CODEABLE_CONCEPT_ARRAY,
        /** An array of Identifiers: read {@code system} and {@code value}. */
        IDENTIFIER_ARRAY,
        /** A bare string, indexed with an empty system. */
        PLAIN_STRING
    }

    private final String fhirPath;
    private final Shape shape;

    TriggerPath(String fhirPath, Shape shape) {
        this.fhirPath = fhirPath;
        this.shape = shape;
    }

    /** The path as it appears in a PlanDefinition's {@code codeFilter.path} and in {@code trigger_index}. */
    public String fhirPath() {
        return fhirPath;
    }

    public Shape shape() {
        return shape;
    }

    /**
     * Whether a trigger written against this path can ever match.
     *
     * <p>An empty path is matchable and means something specific: a trigger on resource type alone,
     * which the matching query represents as a row with empty path, system and code.
     */
    public static boolean isMatchable(String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        return Arrays.stream(values()).anyMatch(p -> p.fhirPath.equals(path));
    }

    /** The matchable paths, comma-separated, for error messages that have to say what was allowed. */
    public static String matchablePaths() {
        return Arrays.stream(values())
                .map(TriggerPath::fhirPath)
                .collect(Collectors.joining(", "));
    }
}
