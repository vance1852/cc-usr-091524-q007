package com.admin.equipment.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级（粗粒度）角色门槛，由 {@link RoleAuthorizationInterceptor} 统一拦截。
 * 对象级（数据范围）判定在 {@link AuthorizationService}，两者职责分离。
 * 缺省（不标注）表示五类已登录角色均可访问。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    Role[] value();
}
