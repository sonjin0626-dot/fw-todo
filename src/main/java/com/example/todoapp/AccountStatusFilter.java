package com.example.todoapp;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** ロール変更・無効化をログイン済みセッションにも次のリクエストから反映する。 */
public class AccountStatusFilter extends OncePerRequestFilter {
    private final UserMapper users;

    public AccountStatusFilter(UserMapper users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            User user = users.findByUsername(auth.getName());
            boolean differentAccount = auth.getPrincipal() instanceof CustomUserDetailsService.AccountPrincipal principal
                    && (user == null || !principal.getAccountId().equals(user.getId()));
            if (user == null || !user.isEnabled() || differentAccount) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "アカウントは無効です");
                return;
            }
            var refreshed = new UsernamePasswordAuthenticationToken(auth.getPrincipal(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
            refreshed.setDetails(auth.getDetails());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(refreshed);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
