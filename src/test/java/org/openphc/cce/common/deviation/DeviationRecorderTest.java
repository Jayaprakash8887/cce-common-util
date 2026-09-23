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
import java.util.List;
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

        Deviation result = service.recordDeviation(step, DeviationType.ORDER_VIOLATION);

        assertNotNull(result.getId());
        assertEquals(DeviationType.ORDER_VIOLATION, result.getDeviationType());
        assertEquals(step, result.getStepInstance());
        assertNotNull(result.getDetectedAt());

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

        Deviation result = service.recordDeviation(step, DeviationType.ORDER_VIOLATION);

        assertNotNull(result.getId());
        assertNull(result.getMetadata());
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

        Deviation result = service.recordDeviation(step, DeviationType.ORDER_VIOLATION, Map.of());

        assertNull(result.getMetadata());
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
        Deviation result = service.recordDeviation(step, DeviationType.ORDER_VIOLATION, additional);

        assertNotNull(result.getId());
        assertNotNull(result.getMetadata());
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

        Deviation result = service.recordDeviation(step, DeviationType.ORDER_VIOLATION);

        // recordDeviation never links an intelligence event; the caller does that after evaluating
        assertNull(result.getIntelligenceEventId());
    }

    // --- recordDeviations: the batch form ---

    @Test
    void recordDeviations_insertsTheWholeBatchWithOneSaveAll_andLooksNothingUpFirst() {
        // One saveAll and no read: the unique constraint, not a lookup, is what keeps a (step, type)
        // to one row, and with nothing querying the table between inserts Hibernate is free to send
        // them as one JDBC batch at commit.
        ProtocolInstance protocolInstance = buildProtocolInstance();
        StepInstance first = buildStep(protocolInstance, SlaStatus.OVERDUE);
        StepInstance second = buildStep(protocolInstance, SlaStatus.MISSED);
        when(deviationRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<Deviation> results = service.recordDeviations(List.of(
                new DeviationRecorder.PendingDeviation(first, DeviationType.OVERDUE),
                new DeviationRecorder.PendingDeviation(second, DeviationType.MISSED)));

        assertEquals(2, results.size());
        assertEquals(first, results.get(0).getStepInstance());
        assertEquals(DeviationType.OVERDUE, results.get(0).getDeviationType());
        assertNotNull(results.get(0).getDetectedAt());
        assertEquals(second, results.get(1).getStepInstance());
        assertEquals(DeviationType.MISSED, results.get(1).getDeviationType());
        verify(deviationRepository, times(1)).saveAll(argThat(inserted ->
                inserted instanceof List<?> list && list.size() == 2));
        verifyNoMoreInteractions(deviationRepository);
    }

    @Test
    void recordDeviations_withNothingPending_touchesNothing() {
        assertEquals(List.of(), service.recordDeviations(List.of()));
        verifyNoInteractions(deviationRepository);
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
