package com.example.todoapp;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class AuthController {
    private final MessageSource messages;

    private String message(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserMapper userMapper, PasswordEncoder passwordEncoder, MessageSource messages) {
        this.messages = messages;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String register(Model model) {
        model.addAttribute("registrationForm", new RegistrationForm());
        return "register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute RegistrationForm registrationForm, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return "register";
        }
        User user = new User();
        user.setUsername(registrationForm.getUsername());
        user.setEmail(registrationForm.getEmail());
        user.setPassword(passwordEncoder.encode(registrationForm.getPassword()));
        // フォームから権限は受け取らず、登録ユーザーは必ずUSERにする。
        user.setRole("USER");
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            bindingResult.rejectValue("username", "validation.duplicate", message("validation.duplicate"));
            return "register";
        }
        return "redirect:/login?registered=true";
    }
}
