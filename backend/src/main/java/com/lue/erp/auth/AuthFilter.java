package com.lue.erp.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.dao.EmptyResultDataAccessException;
import java.io.IOException;
import java.util.List;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final AuthService users;
    public AuthFilter(TokenService tokens, AuthService users) { this.tokens = tokens; this.users = users; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            long userId;
            try {
                userId = tokens.verify(header.substring(7));
            } catch (IllegalArgumentException invalidToken) {
                SecurityContextHolder.clearContext();
                chain.doFilter(request, response);
                return;
            }
            try {
                AuthUser user = users.loadUser(userId);
                var authorities = user.permissions().stream().map(SimpleGrantedAuthority::new).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
            } catch (EmptyResultDataAccessException disabledOrMissingUser) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
