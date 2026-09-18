package com.admin.equipment.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常映射：对象级/功能级越权一律 403。
 * 控制器对"存在但无权"的对象抛出 {@link ForbiddenException}，
 * 与列表过滤保持一致，不使用 404 掩盖越权。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("detail", e.getMessage() == null ? "无权访问该资源" : e.getMessage()));
    }
}
