package com.example.todoapp;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TodoMapper {

    List<Todo> search(@Param("keyword") String keyword, @Param("category") String category,
            @Param("order") String order);

    Todo findById(Long id);

    void insert(Todo todo);

    void update(Todo todo);

    void deleteById(Long id);
}
