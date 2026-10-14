package com.lue.erp.imports;

import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/import-batches")
public class ImportCenterController {
    private final ImportCenterService service;
    private final AuthService auth;
    public ImportCenterController(ImportCenterService service,AuthService auth){this.service=service;this.auth=auth;}

    @GetMapping
    public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@AuthenticationPrincipal AuthUser user){
        view(user);return ApiResponse.ok(service.list(page,pageSize));
    }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String,Object>> upload(@RequestParam String importType,@RequestParam(defaultValue="false")boolean forceReimport,@RequestPart("file")MultipartFile file,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){
        view(user);manage(user);return ApiResponse.ok(service.upload(importType,forceReimport,file,user,request));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> detail(@PathVariable long id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="50")int pageSize,@AuthenticationPrincipal AuthUser user){view(user);return ApiResponse.ok(service.detail(id,page,pageSize));}

    @PostMapping("/{id}/commit")
    public ApiResponse<Map<String,Object>> commit(@PathVariable long id,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){view(user);manage(user);return ApiResponse.ok(service.commit(id,user,request));}

    @GetMapping("/{id}/original")
    public ResponseEntity<ByteArrayResource> original(@PathVariable long id,@AuthenticationPrincipal AuthUser user){view(user);byte[] bytes=service.originalFile(id);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment").contentType(MediaType.APPLICATION_OCTET_STREAM).body(new ByteArrayResource(bytes));}

    @GetMapping("/{id}/failures")
    public ResponseEntity<ByteArrayResource> failures(@PathVariable long id,@AuthenticationPrincipal AuthUser user){view(user);byte[] bytes=service.failuresCsv(id);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''import-failures-"+id+".csv").contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(new ByteArrayResource(bytes));}

    @GetMapping("/templates/{importType}")
    public ResponseEntity<ByteArrayResource> template(@PathVariable String importType,@AuthenticationPrincipal AuthUser user){view(user);byte[] bytes=service.template(importType);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+service.templateFilename(importType)).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(new ByteArrayResource(bytes));}

    private void view(AuthUser user){auth.requirePermission(user.id(),"menu.imports");}
    private void manage(AuthUser user){auth.requirePermission(user.id(),"button.imports.manage");}
}
