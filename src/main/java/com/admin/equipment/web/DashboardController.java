package com.admin.equipment.web;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.ScopeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仪表盘：统计口径与设备/工单列表完全一致（同一数据范围）。 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ScopeService scope;

    public DashboardController(ScopeService scope) {
        this.scope = scope;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@CurrentUser AppUser user) {
        List<Equipment> equipments = scope.visibleEquipments(user);
        List<WorkOrder> orders = scope.visibleWorkOrders(user);
        long fault = equipments.stream().filter(e -> "fault".equals(e.getStatus())).count();
        long maintenance = equipments.stream().filter(e -> "maintenance".equals(e.getStatus())).count();
        long open = orders.stream().filter(o -> "open".equals(o.getStatus())).count();
        long inProgress = orders.stream().filter(o -> "in_progress".equals(o.getStatus())).count();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("equipment_total", equipments.size());
        m.put("equipment_fault", fault);
        m.put("equipment_maintenance", maintenance);
        m.put("work_order_total", orders.size());
        m.put("work_order_open", open);
        m.put("work_order_in_progress", inProgress);
        return m;
    }
}
