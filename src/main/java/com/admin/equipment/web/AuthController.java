package com.admin.equipment.web;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.CurrentUsers;
import com.admin.equipment.security.JwtUtil;
import com.admin.equipment.security.PasswordUtil;
import com.admin.equipment.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository userRepo;
    private final JwtUtil jwtUtil;

    public AuthController(AppUserRepository userRepo, JwtUtil jwtUtil) {
        this.userRepo = userRepo;
        this.jwtUtil = jwtUtil;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req.username() == null || req.password() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "请求参数不合法"));
        }
        AppUser user = userRepo.findByUsername(req.username()).orElse(null);
        if (user == null || !PasswordUtil.verify(req.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("detail", "用户名或密码错误"));
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("detail", "账号已被禁用，请联系管理员"));
        }
        Role role = Role.from(user.getRole());
        if (role == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("detail", "账号角色异常，请联系管理员"));
        }
        String token = jwtUtil.createToken(user.getId(), user.getUsername(), role,
                user.getTeamName(), user.getManagedAreas(),
                user.getPermissionVersion() == null ? 0L : user.getPermissionVersion());
        return ResponseEntity.ok(Map.of(
                "access_token", token,
                "token_type", "bearer",
                "role", role.name(),
                "team_name", user.getTeamName() == null ? "" : user.getTeamName(),
                "managed_areas", parseAreas(user.getManagedAreas())
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        CurrentUser user = CurrentUsers.from(request);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.id());
        m.put("username", user.username());
        m.put("display_name", user.displayName());
        m.put("role", user.role().name());
        m.put("team_name", user.team());
        m.put("managed_areas", user.managedAreas());
        m.put("permission_version", user.permissionVersion());
        return ResponseEntity.ok(m);
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
}
