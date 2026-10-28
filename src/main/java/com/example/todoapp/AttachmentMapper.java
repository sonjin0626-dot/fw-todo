package com.example.todoapp;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AttachmentMapper {
    void insert(TodoAttachment attachment);
    List<TodoAttachment> findByTodoId(@Param("todoId") Long todoId);
    TodoAttachment findById(@Param("id") Long id);
    int delete(@Param("id") Long id);
    List<TodoAttachment> findByUserId(@Param("userId") Long userId);
}
