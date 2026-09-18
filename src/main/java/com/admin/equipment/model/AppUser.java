package com.admin.equipment.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "app_users")
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", length = 64)
    private String displayName;

    /** 角色：ADMIN/PLANNER/INSPECTOR/MAINTAINER/AUDITOR。 */
    @Column(nullable = false, length = 32)
    private String role = "INSPECTOR";

    /** 所属班组。 */
    @Column(name = "team_name", length = 64)
    private String teamName = "";

    /** 可管理区域，逗号分隔，如 "动力站,电机房"；管理员留空即视为全部区域。 */
    @Column(name = "managed_areas", length = 512)
    private String managedAreas = "";

    /** 账号是否启用，禁用后旧令牌立即失效。 */
    @Column(nullable = false)
    private Boolean enabled = true;

    /**
     * 权限版本：任何角色/班组/区域/启用状态变更都自增；令牌签发时携带快照，
     * 每次请求与数据库核对，不一致即拒绝，实现撤销即时生效。
     */
    @Column(name = "permission_version", nullable = false)
    private Long permissionVersion = 0L;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
    public String getManagedAreas() { return managedAreas; }
    public void setManagedAreas(String managedAreas) { this.managedAreas = managedAreas; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Long getPermissionVersion() { return permissionVersion; }
    public void setPermissionVersion(Long permissionVersion) { this.permissionVersion = permissionVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
