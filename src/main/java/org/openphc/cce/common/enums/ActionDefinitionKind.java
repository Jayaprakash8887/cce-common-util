package org.openphc.cce.common.enums;

/**
 * FHIR R4 ActivityDefinition.kind values used as the action definition type.
 * Values use PascalCase to match FHIR RequestResourceType codes.
 */
public enum ActionDefinitionKind {
    CommunicationRequest,
    Task,
    ServiceRequest
}
