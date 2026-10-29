package com.example.todoapp;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TodoTemplateMapper {
    List<TodoTemplate> findByUserId(Long userId);
    TodoTemplate findById(Long id);
    void insert(TodoTemplate template);
    void update(TodoTemplate template);
    void deleteById(Long id);
}
