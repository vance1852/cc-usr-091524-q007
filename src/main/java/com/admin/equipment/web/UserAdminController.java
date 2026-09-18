package com.admin.equipment.web;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.CurrentUsers;
import com.admin.equipment.security.ForbiddenException;
import com.admin.equipment.service.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理员维护人员与授权：仅 ADMIN 可用。 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final AppUserRepository userRepo;
    private final AppUserService userService;
    private final String adminUsername;

    public UserAdminController(AppUserRepository userRepo, AppUserService userService,
                                @Value("${app.admin-username}") String adminUsername) {
        this.userRepo = userRepo;
        this.userService = userService;
        this.adminUsername = adminUsername;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        requireAdmin(request);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppUser u : userRepo.findAll()) {
            out.add(view(u));
        }
        return out;
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody AppUserService.UserSpec spec) {
        requireAdmin(request);
        try {
            AppUser u = userService.create(spec);
            return ResponseEntity.status(HttpStatus.CREATED).body(view(u));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest request, @PathVariable Long id,
                                     @RequestBody AppUserService.UserSpec spec) {
        requireAdmin(request);
        AppUser existing = userRepo.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "用户不存在"));
        }
        // 保护内置管理员：角色不可降级、账号不可禁用
        if (adminUsername.equals(existing.getUsername())) {
            if (spec.role() != null && !"ADMIN".equalsIgnoreCase(spec.role())) {
                return ResponseEntity.unprocessableEntity()
                        .body(Map.of("detail", "内置管理员角色不可变更"));
            }
            if (Boolean.FALSE.equals(spec.enabled())) {
                return ResponseEntity.unprocessableEntity()
                        .body(Map.of("detail", "内置管理员账号不可禁用"));
            }
        }
        try {
            return ResponseEntity.ok(view(userService.update(id, spec)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 撤销授权（自增权限版本，旧令牌立即失效）。 */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<?> revoke(HttpServletRequest request, @PathVariable Long id) {
        requireAdmin(request);
        AppUser existing = userRepo.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "用户不存在"));
        }
        if (adminUsername.equals(existing.getUsername())) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("detail", "内置管理员授权不可撤销"));
        }
        return ResponseEntity.ok(view(userService.revoke(id)));
    }

    private void requireAdmin(HttpServletRequest request) {
        CurrentUser u = CurrentUsers.from(request);
        if (!u.isAdmin()) throw new ForbiddenException("仅管理员可维护人员与授权");
    }

    /** 脱敏视图：不返回密码哈希。 */
    private Map<String, Object> view(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("display_name", u.getDisplayName());
        m.put("role", u.getRole());
        m.put("team_name", u.getTeamName());
        m.put("managed_areas", u.getManagedAreas());
        m.put("enabled", u.getEnabled());
        m.put("permission_version", u.getPermissionVersion());
        return m;
    }
}
