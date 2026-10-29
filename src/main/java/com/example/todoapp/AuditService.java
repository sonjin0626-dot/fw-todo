package com.example.todoapp;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 成功履歴とは分けて、操作の試行を本体の成否に関係なく残す。 */
@Service
public class AuditService {
    private final AuditMapper auditMapper;

    public AuditService(AuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    // 別Beanを経由することでREQUIRES_NEWを有効にする。
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void log(String action, Long todoId, Long actorId) {
        AuditLog log = new AuditLog();
        log.setAction(action);
        log.setEntityType("TODO");
        log.setEntityId(todoId);
        log.setUserId(actorId);
        log.setOutcome("ATTEMPTED");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) log.setUsername(truncate(authentication.getName(), 100));
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            // 転送ヘッダーは直接信用しない。信頼するプロキシの設定はサーバー側で行う。
            log.setIpAddress(truncate(request.getRemoteAddr(), 45));
            log.setUserAgent(truncate(request.getHeader("User-Agent"), 500));
        }
        AuditContext context = AuditContext.current();
        if (context != null) {
            log.setEntityType(context.annotation.entityType());
            log.setOldValue(context.before.get(todoId));
        }
        auditMapper.insert(log);
        if (context != null) context.entries.add(log);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void complete(AuditLog log) {
        auditMapper.complete(log);
    }

    private String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
