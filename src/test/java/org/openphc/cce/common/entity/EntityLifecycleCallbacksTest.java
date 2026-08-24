package org.openphc.cce.common.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code @PrePersist} / {@code @PreUpdate} callbacks stamp timestamps the schema declares NOT
 * NULL, so a missed default surfaces as a constraint violation at insert time rather than as a test
 * failure. They also preserve any value the caller set — services backdate these when replaying.
 */
class EntityLifecycleCallbacksTest {

    private static final OffsetDateTime EXPLICIT =
            OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void protocolDefinition_stampsLoadedAndUpdatedThenPreservesThem() {
        ProtocolDefinition def = ProtocolDefinition.builder().build();
        def.onCreate();

        assertNotNull(def.getLoadedAt());
        assertNotNull(def.getUpdatedAt());

        ProtocolDefinition explicit = ProtocolDefinition.builder().loadedAt(EXPLICIT).build();
        explicit.onCreate();
        assertEquals(EXPLICIT, explicit.getLoadedAt(), "a caller-supplied load time is not overwritten");

        explicit.onUpdate();
        assertTrue(explicit.getUpdatedAt().isAfter(EXPLICIT));
    }

    @Test
    void protocolDefinition_canonicalJoinsUrlAndVersion() {
        ProtocolDefinition def = ProtocolDefinition.builder()
                .url("http://openphc.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .build();

        assertEquals("http://openphc.org/PlanDefinition/anc-high-risk|1.0.0", def.getCanonical());
    }

    @Test
    void actionDefinition_canonicalMatchesTheFormatTheResolverParses() {
        ActionDefinition def = ActionDefinition.builder()
                .canonicalUrl("http://openphc.org/ActivityDefinition/escalation-alert")
                .version("1.0")
                .build();

        assertEquals("http://openphc.org/ActivityDefinition/escalation-alert|1.0", def.getCanonical());

        def.onCreate();
        assertNotNull(def.getCreatedAt());
        assertNotNull(def.getUpdatedAt());
        def.onUpdate();
        assertNotNull(def.getUpdatedAt());
    }

    @Test
    void deviation_stampsDetectionTime() {
        Deviation deviation = Deviation.builder().build();
        deviation.onCreate();

        assertNotNull(deviation.getDetectedAt());
        assertNotNull(deviation.getUpdatedAt());

        Deviation backdated = Deviation.builder().detectedAt(EXPLICIT).build();
        backdated.onCreate();
        assertEquals(EXPLICIT, backdated.getDetectedAt(),
                "a deviation detected during replay keeps the time it actually occurred");

        backdated.onUpdate();
        assertTrue(backdated.getUpdatedAt().isAfter(EXPLICIT));
    }

    @Test
    void protocolInstance_stampsCreatedAndUpdated() {
        ProtocolInstance instance = ProtocolInstance.builder().build();
        instance.onCreate();

        assertNotNull(instance.getCreatedAt());
        assertNotNull(instance.getUpdatedAt());

        instance.onUpdate();
        assertNotNull(instance.getUpdatedAt());
    }

    @Test
    void stepInstance_stampsCreatedAndUpdated() {
        StepInstance step = StepInstance.builder().build();
        step.onCreate();

        assertNotNull(step.getCreatedAt());
        assertNotNull(step.getUpdatedAt());

        StepInstance explicit = StepInstance.builder().createdAt(EXPLICIT).build();
        explicit.onCreate();
        assertEquals(EXPLICIT, explicit.getCreatedAt());

        explicit.onUpdate();
        assertTrue(explicit.getUpdatedAt().isAfter(EXPLICIT));
    }

    @Test
    void intelligenceEventLog_stampsCreatedAt() {
        IntelligenceEventLog entry = IntelligenceEventLog.builder().build();
        entry.onCreate();
        assertNotNull(entry.getCreatedAt());

        IntelligenceEventLog explicit = IntelligenceEventLog.builder().createdAt(EXPLICIT).build();
        explicit.onCreate();
        assertEquals(EXPLICIT, explicit.getCreatedAt());
    }

    @Test
    void slaTransition_becomesDueAtItsProcessByTime() {
        // next_attempt_at drives the claim query, so a transition inserted without one must still be
        // picked up — at the deadline it was scheduled for, not immediately and not never.
        StepSlaStateTransition transition = StepSlaStateTransition.builder()
                .processBy(EXPLICIT)
                .build();

        transition.onCreate();

        assertEquals(EXPLICIT, transition.getNextAttemptAt());
        assertNotNull(transition.getCreatedAt());
    }

    @Test
    void slaTransition_keepsAnExplicitNextAttempt() {
        // A backed-off transition already carries its retry time; re-deriving it from process_by
        // would undo the backoff.
        OffsetDateTime retryAt = EXPLICIT.plusMinutes(30);
        StepSlaStateTransition transition = StepSlaStateTransition.builder()
                .processBy(EXPLICIT)
                .nextAttemptAt(retryAt)
                .build();

        transition.onCreate();

        assertEquals(retryAt, transition.getNextAttemptAt());
    }
}
