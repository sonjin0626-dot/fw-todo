package com.example.todoapp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface AuditMapper {
    void insert(AuditLog log);

    void complete(AuditLog log);

    List<AuditLog> search(AuditSearchCriteria criteria);

    long count(AuditSearchCriteria criteria);

    @Select("SELECT id, user_id, title, detail, category, priority, due_date, completed, completed_at, "
            + "deleted_at, pinned, created_at, updated_at FROM todos WHERE id = #{id}")
    Todo snapshot(@Param("id") Long id);
}
