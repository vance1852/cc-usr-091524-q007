package com.admin.equipment.security;

import java.util.Collections;
import java.util.List;

/**
 * 数据可见范围（授权边界的"列表侧"）。
 *
 * <ul>
 *   <li>{@link Kind#ALL} 全平台（管理员、跨区域审计员）；</li>
 *   <li>{@link Kind#AREAS} 限定区域（计划员、维修员），按对象 location 匹配；</li>
 *   <li>{@link Kind#IDS} 限定对象主键集合（巡检员可见的设备/巡检点/计划/工单/任务）；</li>
 *   <li>{@link Kind#NONE} 无任何数据。</li>
 * </ul>
 *
 * 列表接口按此过滤，对象接口用 {@link AuthorizationService} 校验，
 * 二者使用同一份范围计算，保证"列表里没有的对象，直取 ID 返回 403 而非 404"。
 */
public record Scope(Kind kind, List<String> areas, List<Long> ids, String teamName) {

    public enum Kind { ALL, AREAS, IDS, NONE }

    public static Scope all() {
        return new Scope(Kind.ALL, List.of(), List.of(), "");
    }

    public static Scope none() {
        return new Scope(Kind.NONE, List.of(), List.of(), "");
    }

    public static Scope areas(List<String> areas) {
        return new Scope(Kind.AREAS, areas == null ? List.of() : List.copyOf(areas), List.of(), "");
    }

    public static Scope ids(List<Long> ids) {
        return new Scope(Kind.IDS, List.of(), ids == null ? List.of() : List.copyOf(ids), "");
    }

    public boolean isAll() { return kind == Kind.ALL; }
    public boolean isNone() { return kind == Kind.NONE; }

    public List<String> areas() {
        return areas == null ? Collections.emptyList() : areas;
    }

    public List<Long> ids() {
        return ids == null ? Collections.emptyList() : ids;
    }

    public boolean containsArea(String area) {
        if (isAll()) return true;
        if (area == null || kind != Kind.AREAS) return false;
        for (String a : areas) if (a.equalsIgnoreCase(area)) return true;
        return false;
    }

    public boolean containsId(Long id) {
        if (isAll()) return true;
        if (id == null || kind != Kind.IDS) return false;
        return ids.contains(id);
    }
}
