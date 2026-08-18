package org.openphc.cce.common.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.AuditLog;
import org.openphc.cce.common.repository.AuditLogRepository;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogWriterTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Test
    void writesTheRow() {
        AuditLog entry = AuditLog.builder().eventCategory("PROTOCOL_MANAGEMENT").eventType("PROTOCOL_LOADED").build();

        new AuditLogWriter(auditLogRepository).write(entry);

        verify(auditLogRepository).save(entry);
    }

    @Test
    void writeRunsInItsOwnTransaction() throws Exception {
        // AuditService writes from an after-commit callback, where the original transaction is
        // committed but still thread-bound: a REQUIRED write would join it and never flush. This is
        // the reason the writer is a separate bean at all, so the propagation is asserted, not assumed.
        Method write = AuditLogWriter.class.getMethod("write", AuditLog.class);
        org.springframework.transaction.annotation.Transactional annotation =
                write.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertNotNull(annotation, "write must be transactional");
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                annotation.propagation());
    }
}
