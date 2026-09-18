package com.admin.equipment.security;

import jakarta.servlet.http.HttpServletRequest;

/** 从请求中取出 {@link AuthFilter} 注入的当前用户。 */
public final class CurrentUsers {

    private CurrentUsers() {}

    public static CurrentUser from(HttpServletRequest request) {
        Object attr = request.getAttribute(AuthFilter.CURRENT_USER_ATTR);
        if (attr instanceof CurrentUser cu) {
            return cu;
        }
        // 理论上不会发生：AuthFilter 已保证 /api/** 受保护接口必含当前用户
        throw new ForbiddenException("无法识别当前登录用户");
    }
}
