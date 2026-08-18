package org.openphc.cce.common.service;

import org.openphc.cce.common.entity.AuditLog;
import org.openphc.cce.common.repository.AuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists an audit row in its own transaction.
 *
 * <p>A separate bean rather than a method on {@link AuditService} because {@code REQUIRES_NEW} only
 * takes effect through the Spring proxy — a self-invocation would bypass it. That propagation is
 * essential here: {@link AuditService} writes from an after-commit callback, at which point the
 * original transaction is committed but still bound to the thread, so a {@code REQUIRED} write would
 * silently join it and never be flushed.
 */
@Component
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    public AuditLogWriter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLog auditLog) {
        auditLogRepository.save(auditLog);
    }
}
