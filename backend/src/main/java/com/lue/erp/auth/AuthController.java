package com.lue.erp.auth;

import com.lue.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final TokenService tokens;
    private final JdbcTemplate jdbc;
    public AuthController(AuthService auth, TokenService tokens, JdbcTemplate jdbc) { this.auth = auth; this.tokens = tokens; this.jdbc = jdbc; }

    @PostMapping("/login")
    public ApiResponse<?> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        try {
            AuthUser user = auth.authenticate(body.username(), body.password());
            log(user, "auth.login", request, true);
            return ApiResponse.ok(Map.of("token", tokens.issue(user.id()), "user", user));
        } catch (org.springframework.security.authentication.BadCredentialsException ex) {
            jdbc.update("INSERT INTO operation_logs(username, action, ip_address, success) VALUES (?, 'auth.login', ?, FALSE)", body.username(), request.getRemoteAddr());
            throw ex;
        }
    }

    @GetMapping("/me") public ApiResponse<AuthUser> me(@AuthenticationPrincipal AuthUser user) { return ApiResponse.ok(user); }

    @PostMapping("/logout") public ApiResponse<Map<String, Boolean>> logout(@AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        log(user, "auth.logout", request, true);
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    private void log(AuthUser user, String action, HttpServletRequest req, boolean success) {
        jdbc.update("INSERT INTO operation_logs(user_id, username, action, resource_type, ip_address, success) VALUES (?, ?, ?, 'session', ?, ?)", user.id(), user.username(), action, req.getRemoteAddr(), success);
    }
    public record LoginRequest(String username, String password) {}
}
