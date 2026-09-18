package com.admin.equipment.security;

/**
 * 平台角色：
 * ADMIN 管理员 / PLANNER 计划员 / INSPECTOR 巡检员 / MAINTAINER 维修员 / AUDITOR 审计员。
 */
public enum Role {
    ADMIN,
    PLANNER,
    INSPECTOR,
    MAINTAINER,
    AUDITOR;

    /** 容错解析，非法值返回 null。 */
    public static Role from(String value) {
        if (value == null) return null;
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
