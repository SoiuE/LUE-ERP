package com.lue.erp.auth;

import com.lue.erp.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class UserAdminController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    public UserAdminController(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

    @GetMapping("/users") @PreAuthorize("hasAuthority('menu.users')")
    public ApiResponse<List<Map<String,Object>>> users() {
        return ApiResponse.ok(jdbc.queryForList("SELECT u.id,u.username,u.display_name AS displayName,u.enabled,GROUP_CONCAT(DISTINCT r.code ORDER BY r.code) AS roleCodes,GROUP_CONCAT(DISTINCT r.name ORDER BY r.name SEPARATOR '、') AS roleNames,u.created_at AS createdAt FROM users u LEFT JOIN user_roles ur ON ur.user_id=u.id LEFT JOIN roles r ON r.id=ur.role_id GROUP BY u.id,u.username,u.display_name,u.enabled,u.created_at ORDER BY u.id"));
    }

    @PostMapping("/users") @PreAuthorize("hasAuthority('button.users.manage')")
    @Transactional
    public ApiResponse<Map<String,Boolean>> create(@Valid @RequestBody CreateUser request, @AuthenticationPrincipal AuthUser actor) {
        Integer roles = jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE code=?", Integer.class, request.roleCode());
        if (roles == null || roles == 0) throw new IllegalArgumentException("角色不存在");
        jdbc.update("INSERT INTO users(username,password_hash,display_name) VALUES (?,?,?)", request.username(), encoder.encode(request.password()), request.displayName());
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u,roles r WHERE u.username=? AND r.code=?", request.username(), request.roleCode());
        jdbc.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,success) VALUES (?,?, 'user.create','user',?,JSON_OBJECT('roleCode',?),TRUE)", actor.id(), actor.username(), request.username(), request.roleCode());
        return ApiResponse.ok(Map.of("created", true));
    }

    @PostMapping("/users/{id}/reset-password") @PreAuthorize("hasAuthority('button.users.manage')") @Transactional
    public ApiResponse<Map<String,Boolean>> resetPassword(@PathVariable long id, @Valid @RequestBody ResetPassword request,
                                                          @AuthenticationPrincipal AuthUser actor, HttpServletRequest http) {
        String username = username(id);
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?", encoder.encode(request.password()), id);
        audit(actor, "user.password.reset", id, Map.of("username", username), http);
        return ApiResponse.ok(Map.of("updated", true));
    }

    @PostMapping("/users/{id}/status") @PreAuthorize("hasAuthority('button.users.manage')") @Transactional
    public ApiResponse<Map<String,Boolean>> setEnabled(@PathVariable long id, @Valid @RequestBody SetUserStatus request,
                                                       @AuthenticationPrincipal AuthUser actor, HttpServletRequest http) {
        String username = username(id);
        if (!request.enabled()) {
            List<Long> admins = jdbc.queryForList("SELECT DISTINCT u.id FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.enabled=TRUE AND r.code='ADMIN' FOR UPDATE", Long.class);
            boolean isAdmin = jdbc.queryForObject("SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='ADMIN'", Integer.class, id) > 0;
            if (isAdmin && admins.size() <= 1) throw new IllegalArgumentException("不能禁用最后一个启用的系统管理员");
        }
        jdbc.update("UPDATE users SET enabled=? WHERE id=?", request.enabled(), id);
        audit(actor, request.enabled() ? "user.enable" : "user.disable", id, Map.of("username", username), http);
        return ApiResponse.ok(Map.of("updated", true));
    }

    private String username(long id) {
        List<String> rows = jdbc.queryForList("SELECT username FROM users WHERE id=?", String.class, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("用户不存在");
        return rows.get(0);
    }

    private void audit(AuthUser actor, String action, long id, Map<String,Object> detail, HttpServletRequest request) {
        jdbc.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,'user',?,JSON_OBJECT('username',?),?,TRUE)",
            actor.id(), actor.username(), action, String.valueOf(id), detail.get("username"), request.getRemoteAddr());
    }

    @GetMapping("/roles") @PreAuthorize("hasAnyAuthority('menu.roles','button.users.manage')")
    public ApiResponse<List<Map<String,Object>>> roles() {
        return ApiResponse.ok(jdbc.queryForList("SELECT r.id,r.code,r.name,r.description,COUNT(DISTINCT ur.user_id) AS userCount,GROUP_CONCAT(DISTINCT p.code ORDER BY p.code) AS permissions FROM roles r LEFT JOIN user_roles ur ON ur.role_id=r.id LEFT JOIN role_permissions rp ON rp.role_id=r.id LEFT JOIN permissions p ON p.id=rp.permission_id GROUP BY r.id ORDER BY r.id"));
    }

    public record CreateUser(@NotBlank @Size(max=64) String username, @NotBlank @Size(min=8,max=100) String password, @NotBlank @Size(max=100) String displayName, @NotBlank String roleCode) {}
    public record ResetPassword(@NotBlank @Size(min=8,max=100) String password) {}
    public record SetUserStatus(@NotNull Boolean enabled) {}
}
