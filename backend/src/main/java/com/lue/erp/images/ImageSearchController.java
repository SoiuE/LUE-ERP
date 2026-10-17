package com.lue.erp.images;

import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/image-search")
public class ImageSearchController {
    private final ImageSearchService service;
    public ImageSearchController(ImageSearchService service) { this.service = service; }

    @PostMapping(value="/search", consumes="multipart/form-data")
    public ApiResponse<Map<String,Object>> search(@RequestPart("file") MultipartFile file, @AuthenticationPrincipal AuthUser user) {
        return ApiResponse.ok(service.search(file, user));
    }
    @GetMapping("/manual")
    public ApiResponse<Map<String,Object>> manual(@RequestParam String q, @AuthenticationPrincipal AuthUser user) {
        return ApiResponse.ok(service.manual(q, user));
    }
    @GetMapping("/status")
    public ApiResponse<Map<String,Object>> status(@AuthenticationPrincipal AuthUser user) { return ApiResponse.ok(service.status(user)); }
    @PostMapping("/index/{assetId}/retry")
    public ApiResponse<Map<String,Object>> retry(@PathVariable long assetId, @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        return ApiResponse.ok(service.retry(assetId, user, request));
    }
    @PostMapping("/index/rebuild")
    public ApiResponse<Map<String,Object>> rebuild(@AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        return ApiResponse.ok(service.rebuild(user, request));
    }
    @PostMapping("/index/backup")
    public ApiResponse<Map<String,Object>> backup(@AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        return ApiResponse.ok(service.backup(user, request));
    }
}
