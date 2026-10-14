package com.lue.erp.mapping;

import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/shop-sku-mappings")
public class ShopSkuMappingController {
    private final ShopSkuMappingService service;
    public ShopSkuMappingController(ShopSkuMappingService service){this.service=service;}

    @GetMapping
    public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,
        @RequestParam(required=false)Long shopId,@RequestParam(required=false)String platformSku,@RequestParam(required=false)String internalSku,
        @RequestParam(required=false)String q,@RequestParam(required=false)String status,@AuthenticationPrincipal AuthUser user){
        service.checkView(user);return ApiResponse.ok(service.list(page,pageSize,shopId,platformSku,internalSku,q,status));
    }

    @PostMapping
    public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){
        service.checkView(user);service.checkManage(user);return ApiResponse.ok(service.create(body,user,request));
    }
    @PutMapping("/{id}")
    public ApiResponse<Map<String,Boolean>> update(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){
        service.checkView(user);service.checkManage(user);service.update(id,body,user,request);return ApiResponse.ok(Map.of("updated",true));
    }
    @PostMapping("/{id}/deactivate")
    public ApiResponse<Map<String,Boolean>> deactivate(@PathVariable long id,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){
        service.checkView(user);service.checkManage(user);service.deactivate(id,user,request);return ApiResponse.ok(Map.of("deactivated",true));
    }
}
