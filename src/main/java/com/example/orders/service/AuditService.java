package com.example.orders.service;

import com.example.orders.model.AuditLog;
import com.example.orders.model.Order;
import com.example.orders.repository.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * Called asynchronously from the Wire Tap route to persist an audit log entry.
     */
    @Transactional
    public void recordAudit(Order order, String action, String details) {
        log.info("[WIRE TAP - AUDIT] Asynchronously recording audit log for Order {}: Action='{}'",
                order.getOrderId(), action);

        AuditLog auditLog = new AuditLog(
                order.getOrderId(),
                action,
                order.getStatus() != null ? order.getStatus().name() : "UNKNOWN",
                details != null ? details : "Order processed successfully"
        );

        auditRepository.save(auditLog);
        log.info("[WIRE TAP - AUDIT] Audit entry #{} saved successfully in database.", auditLog.getId());
    }
}
