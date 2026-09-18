package com.admin.equipment.security;

/**
 * 平台五类角色。
 * ADMIN 管理员：维护人员与授权，全量数据；
 * PLANNER 计划员：管理本区域范围的计划、模板与巡检点；
 * INSPECTOR 巡检员：只执行分配给自己或本班组的任务；
 * MAINTAINER 维修员：处理获派或本区域工单；
 * AUDITOR 审计员：跨区域只读查看事件。
 */
public enum Role {
    ADMIN,
    PLANNER,
    INSPECTOR,
    MAINTAINER,
    AUDITOR;

    public static Role fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
