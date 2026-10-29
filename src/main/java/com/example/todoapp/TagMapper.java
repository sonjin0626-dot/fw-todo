package com.example.todoapp;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface TagMapper {
    @Select("SELECT id, name FROM tags WHERE name LIKE CONCAT('%', #{keyword}, '%') ORDER BY name, id LIMIT 10")
    List<Tag> searchByName(@Param("keyword") String keyword);
    @Select("SELECT id, name FROM tags WHERE id = #{id}")
    Tag findById(Long id);
    @Select("SELECT tag_id FROM todo_tags WHERE todo_id = #{todoId} ORDER BY tag_id")
    List<Long> findTagIds(Long todoId);
    @Delete("DELETE FROM todo_tags WHERE todo_id = #{todoId}")
    void deleteTodoTags(Long todoId);
    @Insert("INSERT INTO todo_tags (todo_id, tag_id) VALUES (#{todoId}, #{tagId})")
    void insertTodoTag(@Param("todoId") Long todoId, @Param("tagId") Long tagId);
}