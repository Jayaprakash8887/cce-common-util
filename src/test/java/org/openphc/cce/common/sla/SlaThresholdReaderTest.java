package org.openphc.cce.common.sla;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.SlaTransitionType;
import org.openphc.cce.common.repository.StepSlaStateTransitionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Reading a step's thresholds back. Shared by the Matcher and Compliance services, so this is the one
 * place the interpretation of a schedule is pinned down.
 */
@ExtendWith(MockitoExtension.class)
class SlaThresholdReaderTest {

    @Mock private StepSlaStateTransitionRepository transitionRepository;

    private SlaThresholdReader reader;

    @BeforeEach
    void setUp() {
        reader = new SlaThresholdReader(transitionRepository);
    }

    @Test
    void readsProcessByFromEachTransitionRow() {
        UUID stepId = UUID.randomUUID();
        OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
        OffsetDateTime missed = due.plusDays(3);
        when(transitionRepository.findByStepInstanceId(stepId)).thenReturn(List.of(
                row(stepId, SlaTransitionType.DUE_DATE_REACHED, due),
                row(stepId, SlaTransitionType.MISSED_DATE_REACHED, missed)));

        SlaThresholdReader.SlaThresholds thresholds = reader.getThresholds(stepId);

        assertEquals(due, thresholds.dueDate());
        assertEquals(missed, thresholds.missedDate());
    }

    @Test
    void readsThresholdEvenAfterTheTransitionWasApplied() {
        UUID stepId = UUID.randomUUID();
        OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        StepSlaStateTransition applied = row(stepId, SlaTransitionType.DUE_DATE_REACHED, due);
        applied.setProcessed(true);
        applied.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(transitionRepository.findByStepInstanceId(stepId)).thenReturn(List.of(applied));

        // process_by is never rewritten, so a settled SLA can still be judged against its deadline.
        assertEquals(due, reader.getThresholds(stepId).dueDate());
    }

    @Test
    void noRows_returnsBothNull() {
        UUID stepId = UUID.randomUUID();
        when(transitionRepository.findByStepInstanceId(stepId)).thenReturn(List.of());

        SlaThresholdReader.SlaThresholds thresholds = reader.getThresholds(stepId);

        assertNull(thresholds.dueDate());
        assertNull(thresholds.missedDate());
    }

    @Test
    void onlyMissedScheduled_leavesDueDateNull() {
        UUID stepId = UUID.randomUUID();
        OffsetDateTime missed = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3);
        when(transitionRepository.findByStepInstanceId(stepId))
                .thenReturn(List.of(row(stepId, SlaTransitionType.MISSED_DATE_REACHED, missed)));

        SlaThresholdReader.SlaThresholds thresholds = reader.getThresholds(stepId);

        assertNull(thresholds.dueDate());
        assertEquals(missed, thresholds.missedDate());
    }

    private StepSlaStateTransition row(UUID stepId, SlaTransitionType type, OffsetDateTime processBy) {
        return StepSlaStateTransition.builder()
                .id(UUID.randomUUID())
                .stepInstanceId(stepId)
                .transitionType(type)
                .processBy(processBy)
                .nextAttemptAt(processBy)
                .build();
    }
}
