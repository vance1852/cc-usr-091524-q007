package com.admin.equipment.service;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.security.PasswordUtil;
import com.admin.equipment.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 人员与授权维护。任何影响鉴权的字段变更都自增 permission_version，
 * 使该用户旧令牌在下次请求时被 {@code AuthFilter} 判定失效。
 */
@Service
public class AppUserService {

    private final AppUserRepository userRepo;

    public AppUserService(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public record UserSpec(String username, String password, String displayName,
                            String role, String teamName, String managedAreas, Boolean enabled) {}

    @Transactional
    public AppUser create(UserSpec spec) {
        if (spec.username() == null || spec.username().isBlank()) {
            throw new IllegalArgumentException("用户名必填");
        }
        if (spec.password() == null || spec.password().isBlank()) {
            throw new IllegalArgumentException("密码必填");
        }
        if (userRepo.existsByUsername(spec.username())) {
            throw new IllegalArgumentException("用户名已存在");
        }
        Role role = Role.from(spec.role());
        if (role == null) throw new IllegalArgumentException("角色不合法");
        AppUser u = new AppUser();
        u.setUsername(spec.username().trim());
        u.setPasswordHash(PasswordUtil.hash(spec.password()));
        u.setDisplayName(spec.displayName() == null ? "" : spec.displayName());
        u.setRole(role.name());
        u.setTeamName(spec.teamName() == null ? "" : spec.teamName().trim());
        u.setManagedAreas(normalizeAreas(spec.managedAreas()));
        u.setEnabled(spec.enabled() == null || spec.enabled());
        u.setPermissionVersion(0L);
        return userRepo.save(u);
    }

    @Transactional
    public AppUser update(Long id, UserSpec spec) {
        AppUser u = userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        boolean authChanged = false;
        if (spec.password() != null && !spec.password().isBlank()) {
            u.setPasswordHash(PasswordUtil.hash(spec.password()));
        }
        if (spec.displayName() != null) u.setDisplayName(spec.displayName());
        if (spec.role() != null) {
            Role role = Role.from(spec.role());
            if (role == null) throw new IllegalArgumentException("角色不合法");
            if (!role.name().equals(u.getRole())) authChanged = true;
            u.setRole(role.name());
        }
        if (spec.teamName() != null) {
            String team = spec.teamName().trim();
            if (!team.equals(u.getTeamName() == null ? "" : u.getTeamName())) authChanged = true;
            u.setTeamName(team);
        }
        if (spec.managedAreas() != null) {
            String areas = normalizeAreas(spec.managedAreas());
            if (!areas.equals(u.getManagedAreas() == null ? "" : u.getManagedAreas())) authChanged = true;
            u.setManagedAreas(areas);
        }
        if (spec.enabled() != null) {
            if (!spec.enabled().equals(u.getEnabled())) authChanged = true;
            u.setEnabled(spec.enabled());
        }
        if (authChanged) bumpVersion(u);
        return userRepo.save(u);
    }

    /** 显式撤销/重发授权：自增版本，立即使旧令牌失效。 */
    @Transactional
    public AppUser revoke(Long id) {
        AppUser u = userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        bumpVersion(u);
        return userRepo.save(u);
    }

    private void bumpVersion(AppUser u) {
        long v = u.getPermissionVersion() == null ? 0L : u.getPermissionVersion();
        u.setPermissionVersion(v + 1);
    }

    private String normalizeAreas(String csv) {
        List<String> areas = new ArrayList<>();
        if (csv != null) {
            for (String p : csv.split(",")) {
                String a = p.trim();
                if (!a.isEmpty() && !areas.contains(a)) areas.add(a);
            }
        }
        return String.join(",", areas);
    }
}
