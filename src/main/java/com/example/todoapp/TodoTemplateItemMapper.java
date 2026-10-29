package com.example.todoapp;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TodoTemplateItemMapper {
    List<TodoTemplateItem> findByTemplateId(Long templateId);
    void insert(TodoTemplateItem item);
    void deleteByTemplateId(Long templateId);
}
