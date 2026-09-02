package org.openphc.cce.common.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.common.entity.*;
import org.openphc.cce.common.repository.DeviationRepository;
import org.openphc.cce.common.repository.IntelligenceEventLogRepository;

import org.openphc.cce.common.fhir.FhirExpressionEvaluator;
import org.openphc.cce.common.fhir.ParsedProtocolCache;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.common.event.IntelligenceTriggerEvent;
import org.openphc.cce.common.kafka.IntelligenceTriggerProducer;
import org.openphc.cce.common.sla.SlaThresholdReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class IntelligenceActionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceActionEvaluator.class);

    private final ParsedProtocolCache parsedProtocolCache;
    private final FhirExpressionEvaluator fhirExpressionEvaluator;
    private final ActionDefinitionResolver actionDefinitionService;
    private final IntelligenceTriggerProducer intelligenceTriggerProducer;
    private final IntelligenceEventLogRepository intelligenceEventLogRepository;
    private final DeviationRepository deviationRepository;
    private final ObjectMapper objectMapper;
    private final SlaThresholdReader slaThresholdReader;
    private final Counter actionsEvaluatedCounter;
    private final Counter actionsFiredCounter;

    public IntelligenceActionEvaluator(ParsedProtocolCache parsedProtocolCache,
                                     FhirExpressionEvaluator fhirExpressionEvaluator,
                                     ActionDefinitionResolver actionDefinitionService,
                                     IntelligenceTriggerProducer intelligenceTriggerProducer,
                                     IntelligenceEventLogRepository intelligenceEventLogRepository,
                                     DeviationRepository deviationRepository,
                                     ObjectMapper objectMapper,
                                     SlaThresholdReader slaThresholdReader,
                                     MeterRegistry meterRegistry) {
        this.parsedProtocolCache = parsedProtocolCache;
        this.fhirExpressionEvaluator = fhirExpressionEvaluator;
        this.actionDefinitionService = actionDefinitionService;
        this.intelligenceTriggerProducer = intelligenceTriggerProducer;
        this.intelligenceEventLogRepository = intelligenceEventLogRepository;
        this.deviationRepository = deviationRepository;
        this.objectMapper = objectMapper;
        this.slaThresholdReader = slaThresholdReader;
        this.actionsEvaluatedCounter = Counter.builder("cce.intelligence.actions.evaluated")
                .description("Total intelligence action conditions evaluated")
                .register(meterRegistry);
        this.actionsFiredCounter = Counter.builder("cce.intelligence.actions.fired")
                .description("Intelligence actions that matched and triggered")
                .register(meterRegistry);
    }

    /**
     * Evaluate intelligence actions when a deviation is detected (MISSED or ORDER_VIOLATION).
     *
     * PlanDefinition
     * └─ action (protocol step)        → match by step.actionId
     *    └─ action[] (intelligence actions)  → for each: check condition → resolve definition → record &amp; publish
     */
    public List<IntelligenceEventLog> evaluateOnDeviation(StepInstance step, Deviation deviation) {
        JsonNode context = objectMapper.valueToTree(buildDeviationContext(step, deviation));
        String triggerReason = deviation.getDeviationType().name().toLowerCase();

        List<IntelligenceEventLog> eventLogs = new ArrayList<>();

        // A deviation is raised by the scheduler crossing a deadline, not by an inbound event, so
        // there is no event payload to attach.
        for (PlanDefinitionParser.IntelligenceActionInfo intelligenceAction : findIntelligenceActions(step)) {
            IntelligenceEventLog eventLog = evaluateAction(intelligenceAction, step, deviation, context, triggerReason, null);
            if (eventLog != null) {
                eventLogs.add(eventLog);
            }
        }

        if (!eventLogs.isEmpty()) {
            log.info("Evaluated intelligence actions for deviation: stepId={}, deviationType={}, fired={}",
                    step.getId(), deviation.getDeviationType(), eventLogs.size());
        }

        return eventLogs;
    }

    /**
     * Evaluate intelligence actions when a step is completed.
     *
     * PlanDefinition
     * └─ action (protocol step)        → match by step.actionId
     *    └─ action[] (intelligence actions)  → for each: check condition → resolve definition → record &amp; publish
     */
    public List<IntelligenceEventLog> evaluateOnCompletion(StepInstance step, JsonNode eventPayload) {
        JsonNode context = objectMapper.valueToTree(buildCompletionContext(step));

        List<IntelligenceEventLog> eventLogs = new ArrayList<>();

        for (PlanDefinitionParser.IntelligenceActionInfo intelligenceAction : findIntelligenceActions(step)) {
            IntelligenceEventLog eventLog = evaluateAction(intelligenceAction, step, null, context, "completion", eventPayload);
            if (eventLog != null) {
                eventLogs.add(eventLog);
            }
        }

        if (!eventLogs.isEmpty()) {
            log.info("Evaluated intelligence actions for step completion: stepId={}, fired={}",
                    step.getId(), eventLogs.size());
        }

        return eventLogs;
    }

    // ── Intelligence action lookup ──

    /**
     * Find intelligence actions for a step by matching actionId in the flat step list.
     */
    private List<PlanDefinitionParser.IntelligenceActionInfo> findIntelligenceActions(StepInstance step) {
        PlanDefinitionParser.StepMetadata protocolStep = parsedProtocolCache
                .get(step.getProtocolInstance().getProtocolDefinition().getId(),
                        () -> step.getProtocolInstance().getProtocolDefinition().getDefinition().toString())
                .step(step.getActionId());

        if (protocolStep == null) {
            log.debug("No intelligence actions found for action: actionId={}", step.getActionId());
            return List.of();
        }
        return protocolStep.intelligenceActions();
    }

    // ── Per intelligence action evaluation ──

    private IntelligenceEventLog evaluateAction(PlanDefinitionParser.IntelligenceActionInfo action,
                                     StepInstance step, Deviation deviation,
                                     JsonNode context, String triggerReason, JsonNode eventPayload) {
        if (!conditionMatches(action, context)) return null;

        ActionDefinition definition = resolveActionDefinition(action);
        if (definition == null) return null;

        return recordAndPublish(action, step, deviation, definition, triggerReason, context, eventPayload);
    }

    private boolean conditionMatches(PlanDefinitionParser.IntelligenceActionInfo action,
                                     JsonNode context) {
        actionsEvaluatedCounter.increment();
        try {
            boolean matched = fhirExpressionEvaluator.evaluate(
                    action.conditionLanguage(), action.conditionExpression(), context);
            if (!matched) {
                log.debug("Condition not met for intelligence action: actionId={}", action.actionId());
            }
            return matched;
        } catch (Exception e) {
            log.warn("Failed to evaluate condition for intelligence action: actionId={}, error={}",
                    action.actionId(), e.getMessage());
            return false;
        }
    }

    private ActionDefinition resolveActionDefinition(PlanDefinitionParser.IntelligenceActionInfo action) {
        try {
            return actionDefinitionService.resolveByCanonical(action.definitionCanonical());
        } catch (EntityNotFoundException | IllegalArgumentException e) {
            log.warn("Cannot resolve ActionDefinition: actionId={}, canonical={}, error={}",
                    action.actionId(), action.definitionCanonical(), e.getMessage());
            return null;
        }
    }

    private IntelligenceEventLog recordAndPublish(PlanDefinitionParser.IntelligenceActionInfo action,
                                       StepInstance step, Deviation deviation,
                                       ActionDefinition definition, String triggerReason,
                                       JsonNode evaluationContext, JsonNode eventPayload) {
        actionsFiredCounter.increment();
        UUID eventId = UUID.randomUUID();
        ProtocolInstance protocol = step.getProtocolInstance();

        MDC.put("intelligenceEventId", eventId.toString());
        try {
            // Severity and destination are always present (required at PlanDefinition parse time)
            String intelligenceDestination = action.intelligenceDestination();
            String severity = action.severity();

            // intelligenceEventId is filled in once the event log has an id (below)
            IntelligenceTriggerEvent event = IntelligenceTriggerEvent.builder()
                    .id(eventId)
                    .subject(protocol.getPatientId())
                    .actionDefinitionId(definition.getId())
                    .protocolDefinitionId(protocol.getProtocolDefinition().getId())
                    .actionType(definition.getActionType().name())
                    .severity(severity)
                    .intelligenceDestination(intelligenceDestination)
                    .stepStatus(step.getStepStatus().code())
                    // Null while no threshold has fallen due, and for a step with no SLA at all.
                    .slaStatus(slaStatusCode(step))
                    .actionId(step.getActionId())
                    // Derived rather than stored: protocol_instance no longer denormalizes it.
                    .protocolCanonical(protocol.getProtocolDefinition().getCanonical())
                    .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .eventPayload(eventPayload)
                    .build();

            // Create event log record (published=false initially)
            IntelligenceEventLog eventLog = IntelligenceEventLog.builder()
                    .eventPayload(objectMapper.valueToTree(event))
                    .actionDefinitionId(definition.getId())
                    .protocolInstanceId(protocol.getId())
                    .stepInstanceId(step.getId())
                    .deviationId(deviation != null ? deviation.getId() : null)
                    .subject(protocol.getPatientId())
                    .actionType(definition.getActionType().name())
                    .intelligenceDestination(intelligenceDestination)
                    .stepStatus(step.getStepStatus().code())
                    // Null while no threshold has fallen due, and for a step with no SLA at all.
                    .slaStatus(slaStatusCode(step))
                    .triggerReason(triggerReason)
                    .stepActionId(action.actionId())
                    .evaluationExpression(action.conditionExpression())
                    .evaluationContext(evaluationContext)
                    .published(false)
                    .build();
            eventLog = intelligenceEventLogRepository.save(eventLog);

            // Set intelligenceEventId to the event log ID for cross-service correlation
            event.setIntelligenceEventId(eventLog.getId());
            eventLog.setEventPayload(objectMapper.valueToTree(event));

            // published only flips once the broker has acknowledged the record. Marking it
            // optimistically would leave a genuinely unpublished event invisible to the
            // published = FALSE partial index that reconciliation scans. A send that fails or
            // times out is not fatal: the row keeps the full payload and stays replayable.
            if (intelligenceTriggerProducer.publishAndConfirm(event)) {
                eventLog.setPublished(true);
                eventLog.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            } else {
                log.error("Intelligence trigger event {} was recorded but not published — it "
                        + "remains flagged unpublished for reconciliation", eventLog.getId());
            }
            eventLog = intelligenceEventLogRepository.save(eventLog);

            if (deviation != null && deviation.getIntelligenceEventId() == null) {
                deviation.setIntelligenceEventId(eventId);
                deviationRepository.save(deviation);
            }

            log.info("Intelligence action fired: actionId={}, definition={}, eventId={}, published={}",
                    action.actionId(), definition.getCanonical(), eventId, eventLog.isPublished());

            return eventLog;
        } finally {
            MDC.remove("intelligenceEventId");
        }
    }

    /** A step's SLA status as its lowercase code, or null while no threshold has been judged. */
    private static String slaStatusCode(StepInstance step) {
        return step.getSlaStatus() != null ? step.getSlaStatus().name().toLowerCase() : null;
    }

    // ── Context builders ──


    private Map<String, Object> buildDeviationContext(StepInstance step, Deviation deviation) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stepStatus", step.getStepStatus().code());
        // Null when no threshold has fallen due. A JSONLogic rule comparing slaStatus to a string
        // simply does not match, which is the right outcome: there is no judgement to react to.
        context.put("slaStatus", slaStatusCode(step));
        context.put("deviationType", deviation.getDeviationType().name().toLowerCase());
        context.put("actionId", step.getActionId());
        context.put("repeatIndex", step.getRepeatIndex());

        SlaThresholdReader.SlaThresholds thresholds = slaThresholdReader.getThresholds(step.getId());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (thresholds.dueDate() != null) {
            context.put("dueDate", thresholds.dueDate().toString());
            context.put("daysOverdue",
                    Math.max(0, ChronoUnit.DAYS.between(thresholds.dueDate(), now)));
        }

        if (thresholds.missedDate() != null) {
            context.put("daysPastMissedDate",
                    Math.max(0, ChronoUnit.DAYS.between(thresholds.missedDate(), now)));
        }

        return context;
    }

    private Map<String, Object> buildCompletionContext(StepInstance step) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stepStatus", step.getStepStatus().code());
        // Null when no threshold has fallen due. A JSONLogic rule comparing slaStatus to a string
        // simply does not match, which is the right outcome: there is no judgement to react to.
        context.put("slaStatus", slaStatusCode(step));
        context.put("actionId", step.getActionId());
        context.put("repeatIndex", step.getRepeatIndex());

        if (step.getCompletedAt() != null) {
            context.put("completedAt", step.getCompletedAt().toString());
        }
        OffsetDateTime dueDate = slaThresholdReader.getThresholds(step.getId()).dueDate();
        if (dueDate != null) {
            context.put("dueDate", dueDate.toString());
        }

        return context;
    }

}
