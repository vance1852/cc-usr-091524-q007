package com.admin.equipment.web;

import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.security.AuthorizationService;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.CurrentUsers;
import com.admin.equipment.security.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private static final Set<String> TYPES = Set.of("inspection", "repair", "maintenance");
    private static final Set<String> PRIORITIES = Set.of("low", "medium", "high", "urgent");
    private static final Set<String> STATUSES = Set.of("open", "in_progress", "done");

    private final WorkOrderRepository repo;
    private final EquipmentRepository equipmentRepo;
    private final AuthorizationService authz;

    public WorkOrderController(WorkOrderRepository repo, EquipmentRepository equipmentRepo,
                                AuthorizationService authz) {
        this.repo = repo;
        this.equipmentRepo = equipmentRepo;
        this.authz = authz;
    }

    public record WorkOrderRequest(Long equipmentId, String title, String type, String priority,
                                   String description, String assignee) {}

    public record StatusRequest(String status) {}

    @GetMapping
    public List<WorkOrder> list(HttpServletRequest request,
                                @RequestParam(required = false) Long equipmentId,
                                @RequestParam(required = false) String status) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadWorkOrder(u)) throw new ForbiddenException("无权查看工单");
        // 无论带何筛选条件，都先收敛到当前用户可见范围，杜绝越权枚举
        return authz.visibleWorkOrders(u).stream()
                .filter(w -> equipmentId == null || equipmentId.equals(w.getEquipmentId()))
                .filter(w -> status == null || status.equals(w.getStatus()))
                .toList();
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody WorkOrderRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canCreateWorkOrder(u)) throw new ForbiddenException("无权创建工单");
        if (req.equipmentId() == null || req.title() == null || req.title().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "设备和标题必填"));
        }
        if (!equipmentRepo.existsById(req.equipmentId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        // 对象级：只能为本范围设备派单
        if (!authz.canAccessEquipment(u, req.equipmentId())) {
            throw new ForbiddenException("无权为该区域设备派单");
        }
        WorkOrder w = new WorkOrder();
        w.setEquipmentId(req.equipmentId());
        w.setTitle(req.title());
        w.setType(TYPES.contains(req.type()) ? req.type() : "inspection");
        w.setPriority(PRIORITIES.contains(req.priority()) ? req.priority() : "medium");
        w.setDescription(req.description() == null ? "" : req.description());
        w.setAssignee(req.assignee() == null ? "" : req.assignee());
        w.setStatus("open");
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(w));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(HttpServletRequest request, @PathVariable Long id,
                                           @RequestBody StatusRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canUpdateWorkOrder(u)) throw new ForbiddenException("无权流转工单状态");
        WorkOrder w = repo.findById(id).orElse(null);
        if (w == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "工单不存在"));
        }
        // 存在但不在获派/本范围：403，与列表一致
        if (!authz.canOperateWorkOrder(u, w)) throw new ForbiddenException("无权处理该工单");
        if (req.status() == null || !STATUSES.contains(req.status())) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "状态不合法"));
        }
        w.setStatus(req.status());
        if ("done".equals(req.status())) {
            w.setClosedAt(LocalDateTime.now());
        } else {
            w.setClosedAt(null);
        }
        return ResponseEntity.ok(repo.save(w));
    }
}
