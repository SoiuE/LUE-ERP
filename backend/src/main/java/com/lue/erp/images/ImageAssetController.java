package com.lue.erp.images;

import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/images")
public class ImageAssetController {
    private final ImageAssetService service;
    private final AuthService auth;
    private final ImageArchiveService archive;

    public ImageAssetController(ImageAssetService service, AuthService auth, ImageArchiveService archive) { this.service = service; this.auth = auth; this.archive = archive; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize, @RequestParam(required=false) String sourceType,
            @RequestParam(required=false) String status, @RequestParam(required=false) String objectType,
            @RequestParam(required=false) Long objectId, @RequestParam(required=false) String purpose,
            @RequestParam(required=false) String tag, @RequestParam(required=false) String q,
            @AuthenticationPrincipal AuthUser user) {
        auth.requirePermission(user.id(), "menu.images");
        return ApiResponse.ok(service.list(page, pageSize, sourceType, status, objectType, objectId, purpose, tag, q));
    }

    @GetMapping("/references")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> references(@RequestParam String objectType,
            @RequestParam String ids, @AuthenticationPrincipal AuthUser user) {
        return ApiResponse.ok(service.references(objectType, ids, user));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> detail(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        auth.requirePermission(user.id(), "menu.images");
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping(value="/uploads", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> upload(@RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        return ApiResponse.ok(service.upload(file, user, request));
    }

    @PostMapping("/external-links")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> external(@RequestBody Map<String,Object> body,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        return ApiResponse.ok(service.createExternal(body == null ? null : String.valueOf(body.get("url")), user, request));
    }

    @PostMapping("/links")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> link(@RequestBody Map<String,Object> body,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        return ApiResponse.ok(service.link(body, user, request));
    }

    @PatchMapping("/links/{linkId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> updateLink(@PathVariable long linkId, @RequestBody Map<String,Object> body,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        return ApiResponse.ok(service.updateLink(linkId, body, user, request));
    }

    @DeleteMapping("/links/{linkId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> unlink(@PathVariable long linkId,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        return ApiResponse.ok(service.unlink(linkId, user, request));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> deactivate(@PathVariable long id,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        return ApiResponse.ok(service.deactivate(id, user, request));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String,Object>> archive(@PathVariable long id,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        return ApiResponse.ok(archive.request(id, user, request));
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> content(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        service.authorizeReferenceRead(id, user);
        ImageAssetService.StoredImage image = service.read(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(image.originalFilename()).build().toString())
            .body(image.bytes());
    }


    @GetMapping("/{id}/thumbnail")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> thumbnail(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        service.authorizeReferenceRead(id, user);
        ImageAssetService.StoredImage image = service.thumbnail(id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(image.bytes());
    }
}
