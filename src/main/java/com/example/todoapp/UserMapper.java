package com.example.todoapp;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface UserMapper {
    @Select("SELECT id, username, password, role, enabled FROM users WHERE username = #{username}")
    User findByUsername(String username);

    @Select("SELECT id, username, role, enabled, created_at, updated_at FROM users ORDER BY id")
    List<User> findAll();

    @Select("SELECT id, username, role, enabled, created_at, updated_at FROM users WHERE id = #{id}")
    User findById(Long id);

    @Select("SELECT id, username, role, enabled, created_at, updated_at FROM users WHERE id = #{id} FOR UPDATE")
    User findByIdForUpdate(Long id);

    @Update("UPDATE users SET role = #{role}, enabled = #{enabled}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateAccess(User user);

    @Update({"<script>", "UPDATE users SET username = #{username}, role = #{role}, enabled = #{enabled},",
            "updated_at = CURRENT_TIMESTAMP",
            "<if test='password != null'>, password = #{password}</if>", "WHERE id = #{id}", "</script>"})
    int update(User user);

    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteById(Long id);

    @Insert("INSERT INTO users (username, password, role) VALUES (#{username}, #{password}, #{role})")
    void insert(User user);
}
