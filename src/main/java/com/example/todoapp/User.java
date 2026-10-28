package com.example.todoapp;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class User {
    private Long id;
    private String username;
    private String email;
    private String password;
    private String role;
    private boolean enabled = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
