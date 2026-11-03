package org.openphc.cce.common.enums;

import java.util.Optional;

/**
 * What starts a new cycle of a visit-driven step group: the value of the group's {@code cycle-boundary}
 * extension.
 */
public enum CycleBoundary {

    /** A new cycle for each clinical day, in the deployment's time zone, on which the group has events. */
    CALENDAR_DAY("calendar-day"),

    /**
     * A new cycle with each event for the group's entry step, named in {@code cycle-entry-step}. The first
     * cycle opens with an event for any of its steps.
     */
    ENTRY_STEP("entry-step");

    private final String code;

    CycleBoundary(String code) {
        this.code = code;
    }

    /** The code as written in the extension's {@code valueCode}. */
    public String code() {
        return code;
    }

    /** The boundary a code names, or empty for a code CCE does not read. */
    public static Optional<CycleBoundary> fromCode(String code) {
        for (CycleBoundary boundary : values()) {
            if (boundary.code.equals(code)) {
                return Optional.of(boundary);
            }
        }
        return Optional.empty();
    }
}
