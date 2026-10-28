package com.example.todoapp;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
        auditMapper.insert(action, todoId, actorId);
    }
}
