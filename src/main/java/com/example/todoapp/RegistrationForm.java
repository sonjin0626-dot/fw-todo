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
    @NotBlank(message = "ユーザー名を入力してください")
    @Pattern(regexp = "[a-zA-Z0-9_-]{3,50}", message = "ユーザー名は半角英数字・ハイフン・アンダースコアの3〜50文字で入力してください")
    private String username;

    @NotBlank(message = "パスワードを入力してください")
    @Size(min = 8, max = 72, message = "パスワードは8〜72文字で入力してください")
    private String password;

    @NotBlank(message = "確認用パスワードを入力してください")
    private String passwordConfirmation;

    @AssertTrue(message = "確認用パスワードが一致しません")
    public boolean isPasswordMatching() {
        return password == null || password.equals(passwordConfirmation);
    }

    @AssertTrue(message = "パスワードはUTF-8で72バイト以内にしてください（日本語は1文字あたり約3バイト）")
    public boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
