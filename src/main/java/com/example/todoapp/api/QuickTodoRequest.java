package com.example.todoapp.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Title-only input; ownership and remaining defaults are assigned by the server. */
public record QuickTodoRequest(
        @NotBlank(message = "{validation.titleRequired}")
        @Size(max = 255, message = "{validation.titleSize}") String title) {
}
