package com.example.todoapp;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserMapper userMapper;

    public CustomUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("ユーザーが見つかりません");
        }
        return new AccountPrincipal(user);
    }

    /** 同名で再登録された別アカウントへ、古いセッションが引き継がれないようIDを保持する。 */
    public static final class AccountPrincipal extends org.springframework.security.core.userdetails.User {
        private static final long serialVersionUID = 1L;
        private final Long accountId;

        public AccountPrincipal(User user) {
            super(user.getUsername(), user.getPassword(), user.isEnabled(), true, true, true,
                    java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + user.getRole())));
            this.accountId = user.getId();
        }

        public Long getAccountId() { return accountId; }
    }
}
