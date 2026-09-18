package com.admin.equipment.web;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.security.AuthorizationService;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.CurrentUsers;
import com.admin.equipment.security.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final AuthorizationService authz;

    public DashboardController(AuthorizationService authz) {
        this.authz = authz;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(HttpServletRequest request) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadStats(u)) throw new ForbiddenException("无权查看统计");
        // 仪表盘数字严格按当前用户数据范围统计，与列表口径一致
        List<Equipment> equipments = authz.visibleEquipment(u);
        List<WorkOrder> orders = authz.visibleWorkOrders(u);
        long fault = equipments.stream().filter(e -> "fault".equals(e.getStatus())).count();
        long maintenance = equipments.stream().filter(e -> "maintenance".equals(e.getStatus())).count();
        long open = orders.stream().filter(w -> "open".equals(w.getStatus())).count();
        long inProgress = orders.stream().filter(w -> "in_progress".equals(w.getStatus())).count();
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
