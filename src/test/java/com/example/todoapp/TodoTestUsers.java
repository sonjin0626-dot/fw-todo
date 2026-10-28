package com.example.todoapp;

import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class TodoTestUsers {
    private TodoTestUsers() {
    }

    static UserMapper mapper() {
        User user = new User();
        user.setId(1L);
        user.setUsername("user");
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.findByUsername("user")).thenReturn(user);
        return mapper;
    }

    static UserMapper authenticatedMapper() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "", List.of()));
        return mapper();
    }
}
