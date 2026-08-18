package org.openphc.cce.common.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnumValuesTest {

    @Test
    void protocolDefinitionStatus_hasCorrectValues() {
        assertEquals(2, ProtocolDefinitionStatus.values().length);
        assertNotNull(ProtocolDefinitionStatus.valueOf("ACTIVE"));
        assertNotNull(ProtocolDefinitionStatus.valueOf("RETIRED"));
    }

    @Test
    void protocolInstanceStatus_hasCorrectValues() {
        assertEquals(4, ProtocolInstanceStatus.values().length);
        assertNotNull(ProtocolInstanceStatus.valueOf("ACTIVE"));
        assertNotNull(ProtocolInstanceStatus.valueOf("COMPLETED"));
        assertNotNull(ProtocolInstanceStatus.valueOf("WITHDRAWN"));
        assertNotNull(ProtocolInstanceStatus.valueOf("EXPIRED"));
    }

    @Test
    void stepStatus_hasCorrectValues() {
        assertEquals(2, StepStatus.values().length);
        assertNotNull(StepStatus.valueOf("NOT_STARTED"));
        assertNotNull(StepStatus.valueOf("COMPLETED"));
    }

    @Test
    void slaStatus_hasCorrectValues() {
        assertEquals(4, SlaStatus.values().length);
        assertNotNull(SlaStatus.valueOf("PENDING"));
        assertNotNull(SlaStatus.valueOf("OVERDUE"));
        assertNotNull(SlaStatus.valueOf("MISSED"));
        assertNotNull(SlaStatus.valueOf("MET"));
    }

    @Test
    void deviationType_hasCorrectValues() {
        assertEquals(3, DeviationType.values().length);
        // OVERDUE and MISSED are recorded by the SLA transition evaluator; ORDER_VIOLATION here.
        assertNotNull(DeviationType.valueOf("OVERDUE"));
        assertNotNull(DeviationType.valueOf("MISSED"));
        assertNotNull(DeviationType.valueOf("ORDER_VIOLATION"));
    }

    @Test
    void processingStatus_hasCorrectValues() {
        assertEquals(3, ProcessingStatus.values().length);
        assertNotNull(ProcessingStatus.valueOf("MATCHED"));
        assertNotNull(ProcessingStatus.valueOf("ZERO_MATCH"));
        assertNotNull(ProcessingStatus.valueOf("DUPLICATE"));
    }


    @Test
    void actionDefinitionStatus_hasCorrectValues() {
        assertEquals(2, ActionDefinitionStatus.values().length);
        assertNotNull(ActionDefinitionStatus.valueOf("ACTIVE"));
        assertNotNull(ActionDefinitionStatus.valueOf("RETIRED"));
    }

    @Test
    void actionDefinitionKind_hasCorrectValues() {
        assertEquals(3, ActionDefinitionKind.values().length);
        assertNotNull(ActionDefinitionKind.valueOf("CommunicationRequest"));
        assertNotNull(ActionDefinitionKind.valueOf("Task"));
        assertNotNull(ActionDefinitionKind.valueOf("ServiceRequest"));
    }


    @Test
    void actionType_hasCorrectValues() {
        assertEquals(2, PlanDefinitionActionType.values().length);
        assertEquals("step", PlanDefinitionActionType.STEP.getCode());
        assertEquals("fire-event", PlanDefinitionActionType.FIRE_EVENT.getCode());
        assertEquals("http://openphc.org/fhir/CodeSystem/action-type",
                PlanDefinitionActionType.STEP.getSystem());
        assertEquals("http://terminology.hl7.org/CodeSystem/action-type",
                PlanDefinitionActionType.FIRE_EVENT.getSystem());
    }

    @Test
    void slaTransitionTypesCarryTheStatusPairTheyMoveBetween() {
        // The applier reads these rather than switching on the type, so the pair is the state
        // machine: pending advances to overdue, and overdue advances to missed.
        assertEquals(SlaStatus.PENDING, SlaTransitionType.PENDING_TO_OVERDUE.fromStatus());
        assertEquals(SlaStatus.OVERDUE, SlaTransitionType.PENDING_TO_OVERDUE.toStatus());
        assertEquals(SlaStatus.OVERDUE, SlaTransitionType.OVERDUE_TO_MISSED.fromStatus());
        assertEquals(SlaStatus.MISSED, SlaTransitionType.OVERDUE_TO_MISSED.toStatus());
        assertEquals(SlaTransitionType.PENDING_TO_OVERDUE.toStatus(),
                SlaTransitionType.OVERDUE_TO_MISSED.fromStatus(),
                "the two transitions chain, leaving no reachable gap");
    }
}
