package com.admin.equipment.security;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.repo.AppUserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bearer Token 鉴权过滤器：保护 /api/** ，放行登录与健康检查。
 *
 * <p>JWT 只用于定位用户并携带角色/权限版本快照；每一次请求都以数据库为准：
 * <ol>
 *   <li>用户必须仍存在且 {@code enabled=true}；</li>
 *   <li>令牌中的权限版本 {@code pv} 必须等于数据库 {@code permission_version}，
 *       任何授权变更（角色/班组/区域/启停）都会令旧令牌立即失效；</li>
 *   <li>角色、班组、区域一律取数据库当前值，不采信令牌内容。</li>
 * </ol>
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER_ATTR = "currentUser";

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
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "未提供登录凭证");
            return;
        }
        Claims claims = jwtUtil.parse(header.substring(7));
        if (claims == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录状态无效或已过期");
            return;
        }

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录状态无效");
            return;
        }
        AppUser user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "用户不存在");
            return;
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "账号已被禁用");
            return;
        }

        // 权限版本核对：授权信息一旦变更，旧令牌立即失效
        Long tokenPv = claims.get("pv", Long.class);
        if (tokenPv == null || !tokenPv.equals(user.getPermissionVersion())) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "权限已变更，请重新登录");
            return;
        }

        Role dbRole = Role.from(user.getRole());
        if (dbRole == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "账号角色异常，请联系管理员");
            return;
        }

        CurrentUser current = new CurrentUser(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                dbRole,
                user.getTeamName() == null ? "" : user.getTeamName(),
                parseAreas(user.getManagedAreas()),
                user.getPermissionVersion() == null ? 0L : user.getPermissionVersion());
        request.setAttribute(CURRENT_USER_ATTR, current);
        chain.doFilter(request, response);
    }

    private List<String> parseAreas(String csv) {
        List<String> areas = new ArrayList<>();
        if (csv == null || csv.isBlank()) return areas;
        for (String p : csv.split(",")) {
            String a = p.trim();
            if (!a.isEmpty()) areas.add(a);
        }
        return areas;
    }

    static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        String safe = message.replace("\"", "'");
        response.getWriter().write("{\"detail\":\"" + safe + "\"}");
    }
}
