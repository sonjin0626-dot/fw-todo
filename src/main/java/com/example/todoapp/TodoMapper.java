package com.example.todoapp;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TodoMapper {

    List<Todo> search(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("category") String category,
            @Param("order") String order, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("includeCompleted") boolean includeCompleted);

    long count(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("category") String category,
            @Param("includeCompleted") boolean includeCompleted);

    List<Todo> searchPage(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("category") String category,
            @Param("order") String order, @Param("includeCompleted") boolean includeCompleted,
            @Param("limit") int limit, @Param("offset") long offset);

    List<Todo> searchForExport(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("category") String category,
            @Param("order") String order, @Param("includeCompleted") boolean includeCompleted);

    Todo findById(Long id);

    Todo findIncludingDeletedById(Long id);

    long countTrash(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("category") String category);

    List<Todo> searchTrashPage(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("category") String category,
            @Param("order") String order, @Param("limit") int limit, @Param("offset") long offset);

    void insert(Todo todo);

    void update(Todo todo);

    void deleteById(@Param("userId") Long userId, @Param("id") Long id);

    int bulkDeleteByIds(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    int restoreById(@Param("userId") Long userId, @Param("id") Long id);

    int togglePinned(@Param("userId") Long userId, @Param("id") Long id);

    int deleteByUserId(@Param("userId") Long userId);
}
