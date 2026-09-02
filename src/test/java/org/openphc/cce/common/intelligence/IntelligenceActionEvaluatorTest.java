package org.openphc.cce.common.intelligence;

import org.openphc.cce.common.sla.SlaThresholdReader;
import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.ActionDefinition;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.IntelligenceEventLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.*;
import org.openphc.cce.common.enums.*;
import org.openphc.cce.common.repository.DeviationRepository;
import org.openphc.cce.common.repository.IntelligenceEventLogRepository;

import org.openphc.cce.common.fhir.FhirExpressionEvaluator;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.event.IntelligenceTriggerEvent;
import org.openphc.cce.common.kafka.IntelligenceTriggerProducer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntelligenceActionEvaluatorTest {

    @Mock private ParsedProtocolCache parsedProtocolCache;
    @Mock private FhirExpressionEvaluator fhirExpressionEvaluator;
    @Mock private ActionDefinitionResolver actionDefinitionService;
    @Mock private IntelligenceTriggerProducer intelligenceTriggerProducer;
    @Mock private IntelligenceEventLogRepository intelligenceEventLogRepository;
    @Mock private DeviationRepository deviationRepository;
    @Mock private SlaThresholdReader slaThresholdReader;

    private IntelligenceActionEvaluator evaluator;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        evaluator = new IntelligenceActionEvaluator(
                parsedProtocolCache, fhirExpressionEvaluator,
                actionDefinitionService, intelligenceTriggerProducer,
                intelligenceEventLogRepository,
                deviationRepository, objectMapper, slaThresholdReader, meterRegistry);

        // Default: no SLA thresholds. Tests that assert on dueDate/daysOverdue stub them per step.
        lenient().when(slaThresholdReader.getThresholds(any()))
                .thenReturn(new SlaThresholdReader.SlaThresholds(null, null));
    }

    // ── Deviation Tests ──

    @Nested
    class EvaluateOnDeviation {

        @Test
        void matchingAction_createsEventLogAndPublishesEvent() {
            StepInstance step = buildStep("bp-check", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);
            ActionDefinition actionDef = buildActionDefinition();
            String expr = "{\"==\": [1, 1]}";

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", expr,
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(fhirExpressionEvaluator.evaluate(eq("text/jsonlogic"), eq(expr), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical("http://openphc.org/ActivityDefinition/alert|1.0"))
                    .thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publishAndConfirm(any())).thenReturn(true);

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            // Event log created and published
            assertEquals(1, result.size());
            IntelligenceEventLog eventLog = result.get(0);
            assertTrue(eventLog.isPublished());
            assertNotNull(eventLog.getPublishedAt());

            // Audit context stored directly on the event log
            assertEquals("bp-high-alert", eventLog.getStepActionId());
            assertEquals("missed", eventLog.getTriggerReason());
            assertEquals(expr, eventLog.getEvaluationExpression());
            assertNotNull(eventLog.getEvaluationContext());
            assertEquals(deviation.getId(), eventLog.getDeviationId());

            // Event published correctly
            ArgumentCaptor<IntelligenceTriggerEvent> eventCaptor =
                    ArgumentCaptor.forClass(IntelligenceTriggerEvent.class);
            verify(intelligenceTriggerProducer).publishAndConfirm(eventCaptor.capture());
            IntelligenceTriggerEvent event = eventCaptor.getValue();
            assertEquals(actionDef.getId(), event.getActionDefinitionId());
            assertEquals(step.getProtocolInstance().getProtocolDefinition().getId(), event.getProtocolDefinitionId());
            assertEquals(actionDef.getActionType().name(), event.getActionType());
            assertNotNull(event.getIntelligenceEventId());
            assertEquals("not-started", event.getStepStatus());
            assertEquals("overdue", event.getSlaStatus());
            assertEquals("bp-check", event.getActionId());

            // Deviation.intelligenceEventId set
            verify(deviationRepository).save(deviation);
            assertNotNull(deviation.getIntelligenceEventId());

            // Event log saved twice (unpublished → published)
            verify(intelligenceEventLogRepository, times(2)).save(any(IntelligenceEventLog.class));
        }

        @Test
        void nonMatchingAction_noEventLogCreated() {
            StepInstance step = buildStep("bp-check", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic",
                            "{\">\": [{\"var\": \"daysOverdue\"}, 30]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(fhirExpressionEvaluator.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(intelligenceEventLogRepository, never()).save(any());
            verify(intelligenceTriggerProducer, never()).publishAndConfirm(any());
        }

        @Test
        void multipleActions_someMatchSomeDont() {
            StepInstance step = buildStep("bp-check", SlaStatus.MISSED);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0"),
                    buildIntelligenceAction("bp-normal", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/other|1.0"),
                    buildIntelligenceAction("bp-critical-escalation", "text/fhirpath", "true",
                            "http://openphc.org/ActivityDefinition/escalation|1.0")));

            when(fhirExpressionEvaluator.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 1]}"), any()))
                    .thenReturn(true);
            when(fhirExpressionEvaluator.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 0]}"), any()))
                    .thenReturn(false);
            when(fhirExpressionEvaluator.evaluate(eq("text/fhirpath"), eq("true"), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publishAndConfirm(any())).thenReturn(true);

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertEquals(2, result.size());

            // Audit context stored directly on each event log
            assertEquals("bp-high-alert", result.get(0).getStepActionId());
            assertEquals("bp-critical-escalation", result.get(1).getStepActionId());

            verify(intelligenceTriggerProducer, times(2)).publishAndConfirm(any());
        }

        @Test
        void missingActionDefinition_actionSkippedAndLogged() {
            StepInstance step = buildStep("bp-check", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/missing|1.0")));

            when(fhirExpressionEvaluator.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical("http://openphc.org/ActivityDefinition/missing|1.0"))
                    .thenThrow(new EntityNotFoundException("not found"));

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(intelligenceEventLogRepository, never()).save(any());
        }

        @Test
        void noIntelligenceActionsOnStep_returnsEmptyList() {
            StepInstance step = buildStep("simple-step", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);

            mockParserReturnsIntelligenceActions(step, List.of());

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(fhirExpressionEvaluator, never()).evaluate(anyString(), anyString(), any());
        }

        @Test
        void deviationContextContainsExpectedVariables() {
            StepInstance step = buildStep("bp-check", SlaStatus.OVERDUE);
            stubThresholds(step, OffsetDateTime.now(ZoneOffset.UTC).minusDays(5),
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(fhirExpressionEvaluator.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            evaluator.evaluateOnDeviation(step, deviation);

            ArgumentCaptor<JsonNode> contextCaptor = ArgumentCaptor.forClass(JsonNode.class);
            verify(fhirExpressionEvaluator).evaluate(anyString(), anyString(), contextCaptor.capture());

            JsonNode context = contextCaptor.getValue();
            assertEquals("not-started", context.get("stepStatus").asText());
            assertEquals("overdue", context.get("slaStatus").asText());
            assertEquals("missed", context.get("deviationType").asText());
            assertEquals("bp-check", context.get("actionId").asText());
            assertEquals(0, context.get("repeatIndex").asInt());
            assertTrue(context.has("daysOverdue"));
            assertTrue(context.get("daysOverdue").asLong() >= 4);
            assertTrue(context.has("dueDate"));
            assertTrue(context.has("daysPastMissedDate"));
        }
    }

    // ── Completion Tests ──

    @Nested
    class EvaluateOnCompletion {

        @Test
        void completionWithMatchingAction_createsEventLog() {
            StepInstance step = buildStep("bp-check", SlaStatus.OVERDUE);
            step.setStepStatus(StepStatus.COMPLETED);
            step.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("late-notify", "text/jsonlogic",
                            "{\"==\": [{\"var\": \"slaStatus\"}, \"overdue\"]}",
                            "http://openphc.org/ActivityDefinition/late-alert|1.0")));

            when(fhirExpressionEvaluator.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publishAndConfirm(any())).thenReturn(true);

            List<IntelligenceEventLog> result = evaluator.evaluateOnCompletion(step, null);

            assertEquals(1, result.size());

            // Audit context stored directly on the event log with correct trigger reason
            IntelligenceEventLog eventLog = result.get(0);
            assertEquals("completion", eventLog.getTriggerReason());
            assertEquals("late-notify", eventLog.getStepActionId());
            assertNull(eventLog.getDeviationId());

            // No deviation to update
            verify(deviationRepository, never()).save(any());
        }

        @Test
        void completionContextContainsExpectedVariables() {
            StepInstance step = buildStep("bp-check", SlaStatus.MET);
            step.setStepStatus(StepStatus.COMPLETED);
            step.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            stubThresholds(step, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), null);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("check-rule", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(fhirExpressionEvaluator.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            evaluator.evaluateOnCompletion(step, null);

            ArgumentCaptor<JsonNode> contextCaptor = ArgumentCaptor.forClass(JsonNode.class);
            verify(fhirExpressionEvaluator).evaluate(anyString(), anyString(), contextCaptor.capture());

            JsonNode context = contextCaptor.getValue();
            assertEquals("completed", context.get("stepStatus").asText());
            assertEquals("bp-check", context.get("actionId").asText());
            assertEquals("met", context.get("slaStatus").asText());
            assertFalse(context.has("completionStatus"));
            assertTrue(context.has("completedAt"));
            assertTrue(context.has("dueDate"));
            assertFalse(context.has("deviationType"));
        }

        @Test
        void completionNoIntelligenceActions_returnsEmpty() {
            StepInstance step = buildStep("simple-step", SlaStatus.MET);
            step.setStepStatus(StepStatus.COMPLETED);

            mockParserReturnsIntelligenceActions(step, List.of());

            List<IntelligenceEventLog> result = evaluator.evaluateOnCompletion(step, null);

            assertTrue(result.isEmpty());
        }
    }

    // ── Edge Cases ──

    @Nested
    class EdgeCases {

        @Test
        void conditionEvaluationError_actionSkipped() {
            StepInstance step = buildStep("bp-check", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("error-action", "text/jsonlogic", "invalid-expr",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(fhirExpressionEvaluator.evaluate(anyString(), anyString(), any()))
                    .thenThrow(new RuntimeException("parse error"));

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(intelligenceEventLogRepository, never()).save(any());
        }

        @Test
        void actionNotFoundInPlanDefinition_returnsEmpty() {
            StepInstance step = buildStep("nonexistent-action", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);

            stubProtocol(List.of(
                    new PlanDefinitionParser.StepMetadata("other-action", "Other",
                            List.of(), List.of(), null, null, null, List.of())));

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
        }

        @Test
        void deviationAlreadyHasIntelligenceEventId_notOverwritten() {
            StepInstance step = buildStep("bp-check", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);
            UUID existingEventId = UUID.randomUUID();
            deviation.setIntelligenceEventId(existingEventId);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(fhirExpressionEvaluator.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publishAndConfirm(any())).thenReturn(true);

            evaluator.evaluateOnDeviation(step, deviation);

            // Should not overwrite existing intelligenceEventId
            verify(deviationRepository, never()).save(any());
            assertEquals(existingEventId, deviation.getIntelligenceEventId());
        }

        @Test
        void evaluationContextStoredAsJsonNode() {
            StepInstance step = buildStep("bp-check", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("bp-high-alert", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(fhirExpressionEvaluator.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publishAndConfirm(any())).thenReturn(true);

            List<IntelligenceEventLog> result = evaluator.evaluateOnDeviation(step, deviation);

            assertEquals(1, result.size());
            JsonNode evalCtx = result.get(0).getEvaluationContext();
            assertNotNull(evalCtx);
            assertEquals("not-started", evalCtx.get("stepStatus").asText());
            assertEquals("overdue", evalCtx.get("slaStatus").asText());
            assertEquals("missed", evalCtx.get("deviationType").asText());
            assertEquals("bp-check", evalCtx.get("actionId").asText());
        }
    }

    // ── Metrics Tests ──

    @Nested
    class MetricsTracking {

        @Test
        void actionsEvaluatedCounter_incrementedForEachConditionCheck() {
            StepInstance step = buildStep("bp-check", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("action-1", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0"),
                    buildIntelligenceAction("action-2", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/other|1.0")));

            when(fhirExpressionEvaluator.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            evaluator.evaluateOnDeviation(step, deviation);

            Counter counter = meterRegistry.find("cce.intelligence.actions.evaluated").counter();
            assertNotNull(counter);
            assertEquals(2.0, counter.count());
        }

        @Test
        void actionsFiredCounter_incrementedOnlyForMatchingActions() {
            StepInstance step = buildStep("bp-check", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsIntelligenceActions(step, List.of(
                    buildIntelligenceAction("action-1", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0"),
                    buildIntelligenceAction("action-2", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/other|1.0")));

            when(fhirExpressionEvaluator.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 1]}"), any()))
                    .thenReturn(true);
            when(fhirExpressionEvaluator.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 0]}"), any()))
                    .thenReturn(false);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(intelligenceEventLogRepository.save(any(IntelligenceEventLog.class))).thenAnswer(i -> {
                IntelligenceEventLog log = i.getArgument(0);
                if (log.getId() == null) log.setId(UUID.randomUUID());
                return log;
            });
            when(intelligenceTriggerProducer.publishAndConfirm(any())).thenReturn(true);

            evaluator.evaluateOnDeviation(step, deviation);

            Counter evaluated = meterRegistry.find("cce.intelligence.actions.evaluated").counter();
            Counter fired = meterRegistry.find("cce.intelligence.actions.fired").counter();
            assertNotNull(evaluated);
            assertNotNull(fired);
            assertEquals(2.0, evaluated.count());
            assertEquals(1.0, fired.count());
        }

        @Test
        void noIntelligenceActions_countersNotIncremented() {
            StepInstance step = buildStep("simple-step", SlaStatus.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);

            mockParserReturnsIntelligenceActions(step, List.of());

            evaluator.evaluateOnDeviation(step, deviation);

            Counter evaluated = meterRegistry.find("cce.intelligence.actions.evaluated").counter();
            Counter fired = meterRegistry.find("cce.intelligence.actions.fired").counter();
            assertNotNull(evaluated);
            assertNotNull(fired);
            assertEquals(0.0, evaluated.count());
            assertEquals(0.0, fired.count());
        }
    }

    // ── Helpers ──

    private StepInstance buildStep(String actionId, SlaStatus slaStatus) {
        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/PlanDefinition/test")
                .version("1.0.0")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .definition(objectMapper.createObjectNode().put("resourceType", "PlanDefinition"))
                .loadedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        ProtocolInstance protocolInstance = ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-1")
                .protocolDefinition(protocolDef)
                .status(ProtocolInstanceStatus.ACTIVE)
                .enrolledAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        return StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(0)
                .stepStatus(StepStatus.NOT_STARTED)
                .slaStatus(slaStatus)
                .build();
    }

    private Deviation buildDeviation(StepInstance step, DeviationType type) {
        return Deviation.builder()
                .id(UUID.randomUUID())
                .stepInstance(step)
                .deviationType(type)
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private ActionDefinition buildActionDefinition() {
        return ActionDefinition.builder()
                .id(UUID.randomUUID())
                .canonicalUrl("http://openphc.org/ActivityDefinition/alert")
                .version("1.0")
                .name("alert")
                .title("Alert Action")
                .status(ActionDefinitionStatus.ACTIVE)
                .actionType(ActionDefinitionKind.CommunicationRequest)
                .definition(objectMapper.createObjectNode())
                .build();
    }

    private PlanDefinitionParser.IntelligenceActionInfo buildIntelligenceAction(
            String actionId, String language, String expression, String canonical) {
        return new PlanDefinitionParser.IntelligenceActionInfo(
                actionId, language, expression, canonical, null, null);
    }

    private void mockParserReturnsIntelligenceActions(StepInstance step,
                                                       List<PlanDefinitionParser.IntelligenceActionInfo> actions) {
        stubProtocol(List.of(
                new PlanDefinitionParser.StepMetadata(step.getActionId(), "Test Action",
                        List.of(), List.of(), null, null, null, actions)));
    }

    /** Stub the parsed-protocol cache with the given flattened steps. */
    private void stubProtocol(List<PlanDefinitionParser.StepMetadata> steps) {
        when(parsedProtocolCache.get(any(), any())).thenReturn(
                new ParsedProtocolCache.ParsedProtocol(
                        steps, PlanDefinitionParser.buildDependencyGraph(steps)));
    }

    /** Stand in for the step_sla_state_transition rows this step would have been scheduled with. */
    private void stubThresholds(StepInstance step, OffsetDateTime dueDate, OffsetDateTime missedDate) {
        lenient().when(slaThresholdReader.getThresholds(step.getId()))
                .thenReturn(new SlaThresholdReader.SlaThresholds(dueDate, missedDate));
    }
}
