package com.admin.equipment.security;

import java.util.List;

/**
 * 鉴权后的当前登录用户快照。
 *
 * <p>字段全部在 {@link AuthFilter} 中从数据库实时加载：JWT 只用来定位用户，
 * 角色以数据库为准（令牌内的角色快照仅用于快速失败/审计），并核对权限版本，
 * 因此角色撤销、账号禁用在旧令牌上立即生效。
 */
public record CurrentUser(Long id,
                          String username,
                          String displayName,
                          Role role,
                          String teamName,
                          List<String> managedAreas,
                          long permissionVersion) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean hasRole(Role r) {
        return role == r;
    }

    /** 用户所属班组（可能为空字符串）。 */
    public String team() {
        return teamName == null ? "" : teamName;
    }

    /** 是否管理某区域；管理员管理全部区域。 */
    public boolean managesArea(String area) {
        if (isAdmin()) return true;
        if (area == null || managedAreas == null) return false;
        for (String a : managedAreas) {
            if (a.equalsIgnoreCase(area)) return true;
        }
        return false;
    }

    /** 是否管理任一给定区域。 */
    public boolean managesAnyArea(List<String> areas) {
        if (isAdmin()) return true;
        if (areas == null || managedAreas == null) return false;
        for (String area : areas) {
            if (area != null && managesArea(area)) return true;
        }
        return false;
    }
}
