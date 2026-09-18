package com.admin.equipment.web;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.JwtUtil;
import com.admin.equipment.security.PasswordUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("detail", "账号已被禁用"));
        }
        String token = jwtUtil.createToken(user);
        return ResponseEntity.ok(Map.of(
                "access_token", token,
                "token_type", "bearer",
                "role", user.getRole(),
                "team_name", user.getTeamName(),
                "managed_areas", user.getManagedAreas()
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@CurrentUser AppUser user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("display_name", user.getDisplayName());
        m.put("role", user.getRole());
        m.put("team_name", user.getTeamName());
        m.put("managed_areas", user.getManagedAreas());
        m.put("permission_version", user.getPermissionVersion());
        return ResponseEntity.ok(m);
    }
}
