package org.openphc.cce.common.fhir;

import java.util.regex.Pattern;

/**
 * What a {@code codeFilter.path} may look like: a single top-level field name of the inbound FHIR
 * payload, such as {@code code}, {@code class} or {@code serviceType}.
 *
 * <p>There is deliberately no list of allowed fields. The Matcher Service's {@code EventCodesExtractor}
 * infers how to read each top-level field from the JSON itself (CodeableConcept, bare Coding, array of
 * either, Identifier array, plain string), so a trigger on a new field needs no code change on either
 * side. What the extractor cannot do is follow a dotted or indexed expression, so those are the paths
 * that are rejected: they would be indexed and then never matched, and because Tier 1 requires
 * <em>every</em> codeFilter of an action to match, one such path disables the action outright.
 *
 * <p>The trade-off is that a misspelt field name is now well-formed and loads cleanly; it simply never
 * matches an event.
 */
public final class TriggerPath {

    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private TriggerPath() {
    }

    /**
     * Whether a trigger written against this path is one the extractor can read.
     *
     * <p>An empty path is matchable and means something specific: a trigger on resource type alone,
     * which the matching query represents as a row with empty path, system and code.
     */
    public static boolean isMatchable(String path) {
        return path == null || path.isEmpty() || FIELD_NAME.matcher(path).matches();
    }
}
