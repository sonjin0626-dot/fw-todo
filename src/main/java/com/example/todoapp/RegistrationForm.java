package com.example.todoapp;

import java.nio.charset.StandardCharsets;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegistrationForm {
    @NotBlank(message = "{validation.usernameRequired}")
    @Pattern(regexp = "[a-zA-Z0-9_-]{3,50}", message = "{validation.usernamePattern}")
    private String username;

    @NotBlank(message = "{validation.passwordRequired}")
    @Size(min = 8, max = 72, message = "{validation.passwordSize}")
    private String password;

    @NotBlank(message = "{validation.confirmationRequired}")
    private String passwordConfirmation;

    @AssertTrue(message = "{validation.passwordMatching}")
    public boolean isPasswordMatching() {
        return password == null || password.equals(passwordConfirmation);
    }

    @AssertTrue(message = "{validation.passwordBytes}")
    public boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
