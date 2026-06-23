package com.commitgotchi.user.domain;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface UserMapper {

    @Results(id = "UserResult", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "email", column = "email"),
            @Result(property = "passwordHash", column = "password_hash"),
            @Result(property = "role", column = "role"),
            @Result(property = "createdAt", column = "created_at")
    })
    @Select("""
            SELECT id, email, password_hash, role, created_at
            FROM users
            WHERE id = #{id}
            """)
    User findById(Long id);

    @Select("""
            SELECT id, email, password_hash, role, created_at
            FROM users
            WHERE id = #{id}
            FOR UPDATE
            """)
    @org.apache.ibatis.annotations.ResultMap("UserResult")
    User findByIdForUpdate(Long id);

    @Select("""
            SELECT id, email, password_hash, role, created_at
            FROM users
            WHERE email = #{email}
            """)
    @org.apache.ibatis.annotations.ResultMap("UserResult")
    User findByEmail(String email);

    @Select("""
            SELECT id, email, password_hash, role, created_at
            FROM users
            ORDER BY id
            """)
    @org.apache.ibatis.annotations.ResultMap("UserResult")
    List<User> findAll();

    @Select("SELECT EXISTS (SELECT 1 FROM users WHERE email = #{email})")
    boolean existsByEmail(String email);

    @Select("SELECT COUNT(*) FROM users")
    long count();

    @Insert("""
            INSERT INTO users (email, password_hash, role, created_at)
            VALUES (#{email}, #{passwordHash}, #{role}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(User user);

    @Update("""
            UPDATE users
            SET email = #{email},
                password_hash = #{passwordHash},
                role = #{role}
            WHERE id = #{id}
            """)
    int update(User user);

    @Delete("DELETE FROM users")
    int deleteAll();
}
