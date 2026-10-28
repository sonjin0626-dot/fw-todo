package com.example.todoapp;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** ToDoの変更と同じトランザクションで保存する操作履歴。 */
@Mapper
public interface HistoryMapper {
    @Insert("INSERT INTO todo_history (todo_id, actor_user_id, action) VALUES (#{todoId}, #{actorId}, #{action})")
    void insert(@Param("todoId") Long todoId, @Param("actorId") Long actorId, @Param("action") String action);

    @Insert({"<script>",
            "INSERT INTO todo_history (todo_id, actor_user_id, action)",
            "SELECT id, #{actorId}, 'BULK_DELETE' FROM todos WHERE deleted_at IS NULL",
            "<if test='userId != null'>AND user_id = #{userId}</if>",
            "<choose><when test='ids != null and !ids.isEmpty()'>AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</when><otherwise>AND 1 = 0</otherwise></choose>", "</script>"})
    int insertBulkDelete(@Param("userId") Long userId, @Param("actorId") Long actorId,
            @Param("ids") List<Long> ids);
}
