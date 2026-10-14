package com.lue.erp.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    public AuthService(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

    public AuthUser authenticate(String username, String password) {
        var rows = jdbc.query("SELECT id, username, password_hash, display_name, enabled FROM users WHERE username = ?", (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("display_name"), rs.getBoolean("enabled")}, username);
        if (rows.isEmpty()) throw new org.springframework.security.authentication.BadCredentialsException("用户名或密码错误");
        Object[] row = rows.get(0);
        if (!(Boolean) row[4] || !encoder.matches(password, (String) row[2])) throw new org.springframework.security.authentication.BadCredentialsException("用户名或密码错误");
        long id = (long) row[0];
        jdbc.update("UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        return loadUser(id);
    }

    public AuthUser loadUser(long id) {
        var row = jdbc.queryForMap("SELECT id, username, display_name FROM users WHERE id = ? AND enabled = TRUE", id);
        List<String> roles = jdbc.queryForList("SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=?", String.class, id);
        List<String> permissions = jdbc.queryForList("SELECT DISTINCT p.code FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id JOIN user_roles ur ON ur.role_id=rp.role_id WHERE ur.user_id=?", String.class, id);
        return new AuthUser(((Number) row.get("id")).longValue(), (String) row.get("username"), (String) row.get("display_name"), roles, permissions);
    }

    public boolean hasPermission(long userId, String permission) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id JOIN user_roles ur ON ur.role_id=rp.role_id WHERE ur.user_id=? AND p.code=?", Integer.class, userId, permission) > 0;
    }

    public void requirePermission(long userId, String permission) {
        if (!hasPermission(userId, permission)) throw new org.springframework.security.access.AccessDeniedException("没有执行此操作的权限");
    }
}
