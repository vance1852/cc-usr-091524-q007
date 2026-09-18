package com.admin.equipment.web;

import com.admin.equipment.security.ForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 授权异常统一出口：对象级越权一律 403。
 * 资源不存在仍由各控制器返回 404，二者语义清晰、不会互相矛盾。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("detail", ex.getMessage()));
    }
}
