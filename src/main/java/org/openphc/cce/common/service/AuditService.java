package org.openphc.cce.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.common.entity.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Writes the audit trail.
 *
 * <p>An audit row is only written once the transaction that produced the audited fact has committed.
 * Deferring to an after-commit callback rather than writing immediately is what keeps the trail
 * truthful: a {@code STEP_COMPLETED} row must not survive a completion that later rolled back, which
 * is exactly what an independent (or asynchronous) transaction would allow.
 *
 * <p>Failures never propagate — by the time the callback runs the audited work is already committed,
 * so a failed audit write must not surface as a failure of that work. It is logged at ERROR instead.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogWriter auditLogWriter, ObjectMapper objectMapper) {
        this.auditLogWriter = auditLogWriter;
        this.objectMapper = objectMapper;
    }

    public void audit(String category, String type, String actor,
                      String resourceType, String resourceId,
                      Map<String, Object> details) {
        // Built now, while the caller's data is to hand, and timestamped now so the row reflects when
        // the audited event happened rather than when the commit callback ran.
        AuditLog auditLog = AuditLog.builder()
                .eventCategory(category)
                .eventType(type)
                .actor(actor)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .details(details != null ? objectMapper.valueToTree(details) : null)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Called outside a transaction — there is nothing that could roll back, so write now.
            write(auditLog);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                write(auditLog);
            }
        });
    }

    private void write(AuditLog auditLog) {
        try {
            auditLogWriter.write(auditLog);
        } catch (Exception e) {
            log.error("Failed to write audit log: category={}, type={}, resourceId={}",
                    auditLog.getEventCategory(), auditLog.getEventType(), auditLog.getResourceId(), e);
        }
    }
}
