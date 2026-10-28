package com.example.todoapp;

import java.nio.charset.StandardCharsets;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserEditForm {
    @NotBlank(message = "{validation.usernameRequired}")
    @Pattern(regexp = "[a-zA-Z0-9_-]{3,50}", message = "{validation.usernamePattern}")
    private String username;
    private String role;
    private boolean enabled;
    private String newPassword;

    @AssertTrue(message = "{validation.newPassword}")
    public boolean isPasswordValid() {
        return newPassword == null || newPassword.isEmpty()
                || (newPassword.length() >= 8 && newPassword.getBytes(StandardCharsets.UTF_8).length <= 72);
    }
}
