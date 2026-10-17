package com.lue.erp.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import com.lue.erp.images.ImageSearchUnavailableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiResponse<>(false, "BAD_REQUEST", ex.getMessage(), null));
    }
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> unauthorized(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiResponse<>(false, "UNAUTHORIZED", ex.getMessage(), null));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiResponse<>(false, "FORBIDDEN", ex.getMessage(), null));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>(false, "CONFLICT", "编码或该店铺的平台 SKU 映射已存在，或记录仍被其他档案引用", null));
    }

    @ExceptionHandler(BadSqlGrammarException.class)
    public ResponseEntity<ApiResponse<Void>> databaseStructure(BadSqlGrammarException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiResponse<>(false, "DATABASE_STRUCTURE_ERROR", "数据库查询失败，请管理员检查数据库结构与当前程序版本是否一致；本次操作未提交", null));
    }
    @ExceptionHandler(ImageSearchUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> imageSearchUnavailable(ImageSearchUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiResponse<>(false, "IMAGE_SEARCH_UNAVAILABLE", ex.getMessage(), null));
    }
}
