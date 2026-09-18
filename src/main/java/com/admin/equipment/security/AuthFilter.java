package com.admin.equipment.security;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.repo.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bearer Token 认证过滤器：保护 /api/** ，放行登录与健康检查。
 *
 * 令牌虽携带角色/版本快照，但服务端每次都以数据库为准：
 * 1. 签名/过期合法；
 * 2. 用户仍存在且 enabled=true（禁用账号旧令牌立即失效）；
 * 3. 令牌权限版本 == 数据库权限版本（角色/班组/区域等授权一旦变更，旧令牌立即失效）。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AppUserRepository userRepo;

    public AuthFilter(JwtUtil jwtUtil, AppUserRepository userRepo) {
        this.jwtUtil = jwtUtil;
        this.userRepo = userRepo;
    }

    private boolean isPublic(String path) {
        return path.equals("/api/health") || path.equals("/api/auth/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response, "未提供登录凭证");
            return;
        }
        JwtUtil.TokenSnapshot snapshot = jwtUtil.parse(header.substring(7));
        if (snapshot == null) {
            writeUnauthorized(response, "登录状态无效或已过期");
            return;
        }
        AppUser user = userRepo.findById(snapshot.userId()).orElse(null);
        if (user == null) {
            writeUnauthorized(response, "用户不存在");
            return;
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            writeUnauthorized(response, "账号已被禁用");
            return;
        }
        int dbVersion = user.getPermissionVersion() == null ? 1 : user.getPermissionVersion();
        if (dbVersion != snapshot.permissionVersion()) {
            writeUnauthorized(response, "登录权限已变更，请重新登录");
            return;
        }
        request.setAttribute(RoleAuthorizationInterceptor.CURRENT_USER_ATTR, user);
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"detail\":\"" + message + "\"}");
    }
}
