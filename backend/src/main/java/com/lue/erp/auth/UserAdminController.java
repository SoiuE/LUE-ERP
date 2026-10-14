package com.lue.erp.auth;

import com.lue.erp.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
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
        return ApiResponse.ok(jdbc.queryForList("SELECT u.id,u.username,u.display_name AS displayName,u.enabled,r.code AS roleCode,r.name AS roleName,u.created_at AS createdAt FROM users u LEFT JOIN user_roles ur ON ur.user_id=u.id LEFT JOIN roles r ON r.id=ur.role_id ORDER BY u.id"));
    }

    @PostMapping("/users") @PreAuthorize("hasAuthority('button.users.manage')")
    public ApiResponse<Map<String,Boolean>> create(@Valid @RequestBody CreateUser request, @AuthenticationPrincipal AuthUser actor) {
        Integer roles = jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE code=?", Integer.class, request.roleCode());
        if (roles == null || roles == 0) throw new IllegalArgumentException("角色不存在");
        jdbc.update("INSERT INTO users(username,password_hash,display_name) VALUES (?,?,?)", request.username(), encoder.encode(request.password()), request.displayName());
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u,roles r WHERE u.username=? AND r.code=?", request.username(), request.roleCode());
        jdbc.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,success) VALUES (?,?, 'user.create','user',?,JSON_OBJECT('roleCode',?),TRUE)", actor.id(), actor.username(), request.username(), request.roleCode());
        return ApiResponse.ok(Map.of("created", true));
    }

    @GetMapping("/roles") @PreAuthorize("hasAuthority('menu.roles')")
    public ApiResponse<List<Map<String,Object>>> roles() {
        return ApiResponse.ok(jdbc.queryForList("SELECT r.id,r.code,r.name,r.description,COUNT(DISTINCT ur.user_id) AS userCount,GROUP_CONCAT(DISTINCT p.code ORDER BY p.code) AS permissions FROM roles r LEFT JOIN user_roles ur ON ur.role_id=r.id LEFT JOIN role_permissions rp ON rp.role_id=r.id LEFT JOIN permissions p ON p.id=rp.permission_id GROUP BY r.id ORDER BY r.id"));
    }

    public record CreateUser(@NotBlank @Size(max=64) String username, @NotBlank @Size(min=8,max=100) String password, @NotBlank @Size(max=100) String displayName, @NotBlank String roleCode) {}
}
