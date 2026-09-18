package com.admin.equipment.security;

/** 对象存在但当前用户无权访问：统一映射为 403（不得退化为 404）。 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
