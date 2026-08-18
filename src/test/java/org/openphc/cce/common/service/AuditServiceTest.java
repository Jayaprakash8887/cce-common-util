package org.openphc.cce.common.service;

import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.service.AuditService;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.service.AuditLogWriter;
import org.openphc.cce.common.entity.ProtocolInstance;
import org.openphc.cce.common.entity.ProtocolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.AuditLog;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogWriter auditLogWriter;

    private AuditService auditService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        auditService = new AuditService(auditLogWriter, objectMapper);
    }

    @Test
    void audit_persistsAllFields() {
        Map<String, Object> details = Map.of("actionCount", 6, "triggerIndexEntries", 7);

        auditService.audit("PROTOCOL_MANAGEMENT", "PROTOCOL_LOADED", "system",
                "ProtocolDefinition", "pd-001", details);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogWriter).write(captor.capture());

        AuditLog saved = captor.getValue();
        assertEquals("PROTOCOL_MANAGEMENT", saved.getEventCategory());
        assertEquals("PROTOCOL_LOADED", saved.getEventType());
        assertEquals("system", saved.getActor());
        assertEquals("ProtocolDefinition", saved.getResourceType());
        assertEquals("pd-001", saved.getResourceId());
        assertNotNull(saved.getTimestamp());

        JsonNode detailsNode = saved.getDetails();
        assertNotNull(detailsNode);
        assertEquals(6, detailsNode.get("actionCount").asInt());
        assertEquals(7, detailsNode.get("triggerIndexEntries").asInt());
    }

    @Test
    void audit_nullDetails_persistsWithoutDetails() {
        auditService.audit("MATCHER", "STEP_COMPLETED", "system",
                "StepInstance", "step-001", null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogWriter).write(captor.capture());

        assertNull(captor.getValue().getDetails());
    }

    @Test
    void audit_writeException_doesNotPropagate() {
        doThrow(new RuntimeException("DB error")).when(auditLogWriter).write(any());

        // Should not throw — audit failures are swallowed
        assertDoesNotThrow(() -> auditService.audit(
                "MATCHER", "DEVIATION_DETECTED", "system",
                "Deviation", "dev-001", Map.of("reason", "overdue")));

        verify(auditLogWriter).write(any());
    }

    @Test
    void audit_matcherCategory() {
        auditService.audit("MATCHER", "PROTOCOL_ENROLLED", "system",
                "ProtocolInstance", "pi-001", Map.of("patientId", "patient-123"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogWriter).write(captor.capture());
        assertEquals("MATCHER", captor.getValue().getEventCategory());
        assertEquals("PROTOCOL_ENROLLED", captor.getValue().getEventType());
    }

    @Test
    void audit_insideTransaction_defersWriteUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            auditService.audit("MATCHER", "STEP_COMPLETED", "system",
                    "StepInstance", "si-001", Map.of("actionId", "bp-check"));

            // Nothing written yet — the audited fact is not committed
            verify(auditLogWriter, never()).write(any());

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());

            synchronizations.get(0).afterCommit();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogWriter).write(captor.capture());
            assertEquals("STEP_COMPLETED", captor.getValue().getEventType());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void audit_insideTransactionThatRollsBack_writesNothing() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            auditService.audit("MATCHER", "STEP_COMPLETED", "system",
                    "StepInstance", "si-002", Map.of("actionId", "bp-check"));

            // The transaction rolls back: afterCommit never fires, so no row is written.
            TransactionSynchronizationManager.getSynchronizations()
                    .get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(auditLogWriter, never()).write(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
