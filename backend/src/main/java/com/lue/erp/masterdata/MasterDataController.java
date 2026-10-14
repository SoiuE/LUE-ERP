package com.lue.erp.masterdata;

import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/master/{type}")
public class MasterDataController {
    private final MasterDataService service;
    public MasterDataController(MasterDataService service) { this.service = service; }

    @GetMapping @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> list(@PathVariable String type,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) String q, @RequestParam(required=false) String status,
            @RequestParam(required=false) String category, @AuthenticationPrincipal AuthUser user) {
        service.checkView(type, user);
        return ApiResponse.ok(service.list(type, page, pageSize, q, status, category));
    }

    @GetMapping("/next-code") @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,String>> nextCode(@PathVariable String type, @AuthenticationPrincipal AuthUser user) {
        service.checkView(type, user); service.checkManage(user);
        return ApiResponse.ok(service.nextCode(type));
    }

    @PostMapping @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> create(@PathVariable String type, @RequestBody Map<String,Object> body,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        service.checkView(type, user); service.checkManage(user);
        return ApiResponse.ok(service.create(type, body, user, request));
    }

    @PutMapping("/{id}") @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Boolean>> update(@PathVariable String type, @PathVariable long id,
            @RequestBody Map<String,Object> body, @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        service.checkView(type, user); service.checkManage(user); service.update(type, id, body, user, request);
        return ApiResponse.ok(Map.of("updated", true));
    }

    @PostMapping("/{id}/deactivate") @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Boolean>> deactivate(@PathVariable String type, @PathVariable long id,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        service.checkView(type, user); service.checkManage(user); service.deactivate(type, id, user, request);
        return ApiResponse.ok(Map.of("deactivated", true));
    }

    @DeleteMapping("/{id}") @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Boolean>> softDelete(@PathVariable String type, @PathVariable long id,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        service.checkView(type, user); service.checkManage(user); service.softDelete(type, id, user, request);
        return ApiResponse.ok(Map.of("deleted", true));
    }
}
