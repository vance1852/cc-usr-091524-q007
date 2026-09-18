package com.admin.equipment.service;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.security.PasswordUtil;
import com.admin.equipment.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 人员与授权管理（仅管理员）。
 * 任何影响授权的字段（角色/班组/可管理区域/启用状态）变更都会令 permissionVersion 自增，
 * AuthFilter 据此让该用户的旧令牌立即失效。
 */
@Service
public class UserAdminService {

    private final AppUserRepository userRepo;

    public UserAdminService(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public record UserSpec(String username, String password, String displayName, String role,
                            String teamName, String managedAreas, Boolean enabled) {}

    public List<AppUser> listAll() {
        return userRepo.findAllByOrderByIdAsc();
    }

    public AppUser get(Long id) {
        return userRepo.findById(id).orElse(null);
    }

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
        Role role = Role.fromString(spec.role());
        if (role == null) throw new IllegalArgumentException("角色不合法");

        AppUser u = new AppUser();
        u.setUsername(spec.username().trim());
        u.setPasswordHash(PasswordUtil.hash(spec.password()));
        u.setDisplayName(spec.displayName() == null ? "" : spec.displayName());
        u.setRole(role.name());
        u.setTeamName(spec.teamName());
        u.setManagedAreas(normalizeAreas(spec.managedAreas()));
        u.setEnabled(spec.enabled() == null || spec.enabled());
        u.setPermissionVersion(1);
        return userRepo.save(u);
    }

    @Transactional
    public AppUser update(Long id, UserSpec spec, AppUser operator) {
        AppUser u = userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        boolean authChanged = false;
        if (spec.displayName() != null) u.setDisplayName(spec.displayName());
        if (spec.password() != null && !spec.password().isBlank()) {
            u.setPasswordHash(PasswordUtil.hash(spec.password()));
            // 凭证轮换同样令旧令牌失效
            authChanged = true;
        }

        if (spec.role() != null) {
            Role role = Role.fromString(spec.role());
            if (role == null) throw new IllegalArgumentException("角色不合法");
            if (u.getId().equals(operator.getId()) && role != Role.ADMIN) {
                throw new IllegalArgumentException("不能撤销当前登录管理员自身的管理员角色");
            }
            if (!role.name().equals(u.getRole())) {
                u.setRole(role.name());
                authChanged = true;
            }
        }
        if (spec.teamName() != null) {
            String v = spec.teamName();
            if (!v.equals(u.getTeamName())) {
                u.setTeamName(v);
                authChanged = true;
            }
        }
        if (spec.managedAreas() != null) {
            String v = normalizeAreas(spec.managedAreas());
            if (!v.equals(u.getManagedAreas())) {
                u.setManagedAreas(v);
                authChanged = true;
            }
        }
        if (spec.enabled() != null) {
            if (u.getId().equals(operator.getId()) && !spec.enabled()) {
                throw new IllegalArgumentException("不能禁用当前登录管理员自身");
            }
            if (!spec.enabled().equals(u.getEnabled())) {
                u.setEnabled(spec.enabled());
                authChanged = true;
            }
        }
        if (authChanged) bumpVersion(u);
        return userRepo.save(u);
    }

    @Transactional
    public AppUser setEnabled(Long id, boolean enabled, AppUser operator) {
        AppUser u = userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (u.getId().equals(operator.getId()) && !enabled) {
            throw new IllegalArgumentException("不能禁用当前登录管理员自身");
        }
        if (!Boolean.valueOf(enabled).equals(u.getEnabled())) {
            u.setEnabled(enabled);
            bumpVersion(u);
        }
        return userRepo.save(u);
    }

    private void bumpVersion(AppUser u) {
        int v = u.getPermissionVersion() == null ? 1 : u.getPermissionVersion();
        u.setPermissionVersion(v + 1);
    }

    /** 去空格、去空段、去重，保持逗号分隔。 */
    private String normalizeAreas(String raw) {
        if (raw == null || raw.isBlank()) return "";
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String p : raw.split(",")) {
            String s = p.trim();
            if (!s.isEmpty()) set.add(s);
        }
        return String.join(",", set);
    }
}
