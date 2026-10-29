package com.example.todoapp;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class AuditLogService {
    private final AuditMapper mapper;

    public AuditLogService(AuditMapper mapper) { this.mapper = mapper; }

    public List<AuditLog> search(AuditSearchCriteria criteria) { return mapper.search(criteria); }
    public long count(AuditSearchCriteria criteria) { return mapper.count(criteria); }
}
