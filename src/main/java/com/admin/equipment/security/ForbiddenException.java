package com.admin.equipment.security;

/** 对象级或功能级越权异常，由全局处理器统一映射为 403。 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
