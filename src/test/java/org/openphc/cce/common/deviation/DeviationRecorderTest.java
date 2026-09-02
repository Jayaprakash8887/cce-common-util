package org.openphc.cce.common.deviation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.repository.DeviationRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviationRecorderTest {

    @Mock
    private DeviationRepository deviationRepository;

    private DeviationRecorder service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new DeviationRecorder(deviationRepository, objectMapper);
    }

    @Test
    void recordDeviation_persistsCorrectly() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, SlaStatus.OVERDUE);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        DeviationRecorder.DeviationResult result =
                service.recordDeviation(step, DeviationType.ORDER_VIOLATION);

        assertTrue(result.created(), "A newly inserted deviation should signal created=true");
        assertNotNull(result.deviation().getId());
        assertEquals(DeviationType.ORDER_VIOLATION, result.deviation().getDeviationType());
        assertEquals(step, result.deviation().getStepInstance());
        assertNotNull(result.deviation().getDetectedAt());

        verify(deviationRepository).save(any(Deviation.class));
    }

    @Test
    void recordDeviation_linksTheStepAndNotTheEnrolmentSeparately() {
        // The enrolment is reachable through the step, so deviation carries no protocol_instance_id of
        // its own — one less column that could disagree with the step it hangs off.
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, SlaStatus.OVERDUE);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        service.recordDeviation(step, DeviationType.ORDER_VIOLATION);

        ArgumentCaptor<Deviation> saved = ArgumentCaptor.forClass(Deviation.class);
        verify(deviationRepository).save(saved.capture());
        assertSame(step, saved.getValue().getStepInstance());
        assertSame(protocolInstance, saved.getValue().getStepInstance().getProtocolInstance());
    }

    @Test
    void recordDeviation_withNoMetadata_storesNullMetadata() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, SlaStatus.OVERDUE);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        DeviationRecorder.DeviationResult result = service.recordDeviation(step, DeviationType.ORDER_VIOLATION);

        assertNotNull(result.deviation().getId());
        assertNull(result.deviation().getMetadata());
    }

    @Test
    void recordDeviation_withAnEmptyMetadataMap_storesNullRatherThanAnEmptyObject() {
        // A deviation recorded with an empty map has to read back the same as one recorded with no map
        // at all, or the same absence of detail appears as null in one row and {} in the next.
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, SlaStatus.OVERDUE);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        DeviationRecorder.DeviationResult result =
                service.recordDeviation(step, DeviationType.ORDER_VIOLATION, Map.of());

        assertNull(result.deviation().getMetadata());
    }

    @Test
    void recordDeviation_withAdditionalMetadata_mergesMetadata() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, SlaStatus.OVERDUE);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        Map<String, Object> additional = Map.of("incompletePrerequisites", java.util.List.of("step-a"));
        DeviationRecorder.DeviationResult result = service.recordDeviation(step, DeviationType.ORDER_VIOLATION, additional);

        assertNotNull(result.deviation().getId());
        assertNotNull(result.deviation().getMetadata());
    }

    @Test
    void recordDeviation_whenSameTypeAlreadyExists_returnsExistingWithoutInserting() {
        // Idempotency: a redelivered / concurrent trigger must not create a second
        // deviation of the same type for the same step.
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, SlaStatus.OVERDUE);

        Deviation existing = Deviation.builder()
                .id(UUID.randomUUID())
                .stepInstance(step)
                .deviationType(DeviationType.ORDER_VIOLATION)
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        when(deviationRepository.findByStepInstanceIdAndDeviationType(step.getId(), DeviationType.ORDER_VIOLATION))
                .thenReturn(java.util.Optional.of(existing));

        DeviationRecorder.DeviationResult result = service.recordDeviation(step, DeviationType.ORDER_VIOLATION);

        assertFalse(result.created(), "Should signal the deviation already existed");
        assertSame(existing, result.deviation(), "Should return the pre-existing deviation");
        verify(deviationRepository, never()).save(any(Deviation.class));
    }

    @Test
    void recordDeviation_doesNotPublishIntelligenceTrigger() {
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance step = buildStep(protocolInstance, SlaStatus.OVERDUE);

        when(deviationRepository.save(any(Deviation.class))).thenAnswer(invocation -> {
            Deviation d = invocation.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        DeviationRecorder.DeviationResult result = service.recordDeviation(step, DeviationType.ORDER_VIOLATION);

        // recordDeviation never links an intelligence event; the caller does that after evaluating
        assertNull(result.deviation().getIntelligenceEventId());
    }

    // --- Helpers ---

    private ProtocolInstance buildProtocolInstance() {
        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .build();

        ProtocolInstance instance = ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .protocolDefinition(protocolDef)
                .patientId("patient-123")
                .status(ProtocolInstanceStatus.ACTIVE)
                .enrolledAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        return instance;
    }

    private StepInstance buildStep(ProtocolInstance protocolInstance, SlaStatus slaStatus) {
        return StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .actionId("bp-check")
                .repeatIndex(0)
                .stepStatus(StepStatus.NOT_STARTED)
                .slaStatus(slaStatus)
                .build();
    }

}
