package com.lue.erp.images;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageAssetAuthorizationTest {
    @Test void referenceListRejectsUsersWithoutObjectViewPermissionBeforeQuery() {
        AtomicBoolean queried = new AtomicBoolean();
        JdbcTemplate db = new JdbcTemplate() {
            @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                queried.set(true);
                throw new AssertionError("database must not be queried");
            }
        };
        AuthService auth = new AuthService(null, null) {
            @Override public boolean hasPermission(long userId, String permission) { return false; }
        };
        var service = new ImageAssetService(db, auth, new ObjectMapper(), null, null);
        AuthUser user = new AuthUser(27, "warehouse", "Warehouse", List.of(), List.of());

        assertThrows(AccessDeniedException.class, () -> service.references("SKU", "1,2", user));
        assertEquals(false, queried.get());
    }

    @Test void referenceListReturnsFirstOrderedAssetForEachObject() {
        JdbcTemplate db = new JdbcTemplate() {
            @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                return List.of(
                    Map.of("objectId", 1L, "id", 10L, "sourceType", "UPLOAD"),
                    Map.of("objectId", 1L, "id", 11L, "sourceType", "UPLOAD"),
                    Map.of("objectId", 2L, "id", 12L, "sourceType", "EXTERNAL_URL", "externalUrl", "https://example.com/2.jpg")
                );
            }
        };
        AuthService auth = new AuthService(null, null) {
            @Override public boolean hasPermission(long userId, String permission) { return permission.equals("menu.mobile"); }
        };
        var service = new ImageAssetService(db, auth, new ObjectMapper(), null, null);
        AuthUser user = new AuthUser(27, "warehouse", "Warehouse", List.of(), List.of());

        @SuppressWarnings("unchecked")
        Map<String,Object> items = (Map<String,Object>) service.references("SKU", "1,2", user).get("items");
        assertEquals(10L, ((Map<?,?>) items.get("1")).get("id"));
        assertEquals("https://example.com/2.jpg", ((Map<?,?>) items.get("2")).get("externalUrl"));
    }

    @Test void uploadRequiresBothImageReadAndManagePermissionsBeforeStorageOrDatabaseAccess() {
        var checked = new ArrayList<String>();
        AuthService auth = new AuthService(null, null) {
            @Override public void requirePermission(long userId, String permission) {
                checked.add(permission);
                if (permission.equals("button.images.manage")) throw new AccessDeniedException("denied");
            }
        };
        ImageStorage storage = new ImageStorage() {
            @Override public void put(String key, byte[] data, String type) throws IOException { throw new AssertionError("storage must not be called"); }
            @Override public byte[] get(String key) throws IOException { throw new AssertionError("storage must not be called"); }
            @Override public void delete(String key) throws IOException { throw new AssertionError("storage must not be called"); }
        };
        var validator = new ImageContentValidator(1024, 100, 10);
        var service = new ImageAssetService(null, auth, new ObjectMapper(), storage, validator);
        AuthUser user = new AuthUser(27, "warehouse", "Warehouse", List.of(), List.of());

        assertThrows(AccessDeniedException.class, () -> service.upload(null, user, new MockHttpServletRequest()));

        assertEquals(List.of("menu.images", "button.images.manage"), checked);
    }
}
