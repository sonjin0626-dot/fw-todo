package com.example.todoapp;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditMapper {
    @Insert("INSERT INTO audit_logs (action, todo_id, actor_user_id) VALUES (#{action}, #{todoId}, #{actorId})")
    void insert(@Param("action") String action, @Param("todoId") Long todoId, @Param("actorId") Long actorId);
}
