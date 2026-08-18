package org.openphc.cce.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to cce.intelligence.triggers when an intelligence action fires.
 * Consumed by the CCE Intelligence Service for intelligence destination routing and delivery.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IntelligenceTriggerEvent {

    private UUID id;
    private String subject;
    private UUID intelligenceEventId;
    private UUID actionDefinitionId;
    private UUID protocolDefinitionId;
    private String actionType;
    private String severity;
    private String intelligenceDestination;
    private String stepStatus;

    private String slaStatus;
    private String actionId;
    private String protocolCanonical;
    private OffsetDateTime detectedAt;
    private JsonNode eventPayload;
}
