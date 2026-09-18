package com.admin.equipment.web;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.RequireRole;
import com.admin.equipment.security.Role;
import com.admin.equipment.service.UserAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 人员与授权管理接口，整体仅限 ADMIN：类级 {@link RequireRole} 构成单一接口边界，
 * 具体授权版本自增逻辑在 {@link UserAdminService}。不返回 password_hash。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequireRole(Role.ADMIN)
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    public record UserView(Long id, String username, String displayName, String role,
                            String teamName, String managedAreas, Boolean enabled,
                            Integer permissionVersion) {
        static UserView from(AppUser u) {
            return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(),
                    u.getTeamName(), u.getManagedAreas(), u.getEnabled(), u.getPermissionVersion());
        }
    }

    @GetMapping
    public List<UserView> list() {
        return service.listAll().stream().map(UserView::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        AppUser u = service.get(id);
        if (u == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "用户不存在"));
        }
        return ResponseEntity.ok(UserView.from(u));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UserAdminService.UserSpec spec) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(UserView.from(service.create(spec)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@CurrentUser AppUser operator, @PathVariable Long id,
                                     @RequestBody UserAdminService.UserSpec spec) {
        try {
            return ResponseEntity.ok(UserView.from(service.update(id, spec, operator)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@CurrentUser AppUser operator, @PathVariable Long id,
                                         @RequestBody Map<String, Boolean> body) {
        try {
            boolean enabled = body.getOrDefault("enabled", true);
            return ResponseEntity.ok(UserView.from(service.setEnabled(id, enabled, operator)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }
}
