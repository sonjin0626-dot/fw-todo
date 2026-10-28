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
    @NotBlank(message = "ユーザー名を入力してください")
    @Pattern(regexp = "[a-zA-Z0-9_-]{3,50}", message = "ユーザー名は半角英数字・ハイフン・アンダースコアの3〜50文字で入力してください")
    private String username;
    private String role;
    private boolean enabled;
    private String newPassword;

    @AssertTrue(message = "新しいパスワードは8文字以上、UTF-8で72バイト以内にしてください")
    public boolean isPasswordValid() {
        return newPassword == null || newPassword.isEmpty()
                || (newPassword.length() >= 8 && newPassword.getBytes(StandardCharsets.UTF_8).length <= 72);
    }
}
