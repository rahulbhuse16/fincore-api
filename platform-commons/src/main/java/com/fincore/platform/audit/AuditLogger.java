package com.fincore.platform.audit;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Append-only audit trail. record() joins the business transaction (audit and change commit together);
 * recordIndependent() uses its own transaction so that FAILED attempts (e.g. bad logins) survive a rollback.
 * Callers must never pass secrets, tokens or full personal data in "detail".
 */
public class AuditLogger {
    private final JdbcTemplate jdbc;

    public AuditLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String actor, String action, String resourceType, String resourceId, String outcome, String detail) {
        insert(actor, action, resourceType, resourceId, outcome, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(String actor, String action, String resourceType, String resourceId, String outcome, String detail) {
        insert(actor, action, resourceType, resourceId, outcome, detail);
    }

    private void insert(String actor, String action, String resourceType, String resourceId, String outcome, String detail) {
        String safeDetail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 480));
        jdbc.update("""
                INSERT INTO audit_events(id, actor, action, resource_type, resource_id, outcome, detail, correlation_id)
                VALUES (?,?,?,?,?,?,?,?)""",
                UUID.randomUUID(), actor, action, resourceType, resourceId, outcome, safeDetail, MDC.get("correlationId"));
    }
}
