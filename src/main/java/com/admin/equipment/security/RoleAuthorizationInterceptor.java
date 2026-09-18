package com.admin.equipment.security;

import com.admin.equipment.model.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口级角色门槛拦截：读取处理器方法/类上的 {@link RequireRole}。
 * 认证（令牌有效性、账号有效性、权限版本）已在 {@link AuthFilter} 完成。
 */
@Component
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER_ATTR = "currentUser";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole require = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (require == null) {
            require = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (require == null) {
            return true;
        }
        AppUser user = (AppUser) request.getAttribute(CURRENT_USER_ATTR);
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"detail\":\"未认证\"}");
            return false;
        }
        Role current = user.roleEnum();
        for (Role allowed : require.value()) {
            if (allowed == current) {
                return true;
            }
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"detail\":\"角色无权访问该接口\"}");
        return false;
    }
}
