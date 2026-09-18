package com.admin.equipment.web;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.security.AuthorizationService;
import com.admin.equipment.security.CurrentUser;
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
                                    String description, String assignee, Long assigneeId) {}

    public record StatusRequest(String status) {}

    public record AssignRequest(Long assigneeId, String assignee) {}

    /** 工单列表：在原有筛选基础上统一套用当前用户数据范围。 */
    @GetMapping
    public List<WorkOrder> list(@CurrentUser AppUser user,
                                 @RequestParam(required = false) Long equipmentId,
                                 @RequestParam(required = false) String status) {
        List<WorkOrder> base;
        if (equipmentId != null) {
            base = repo.findByEquipmentIdOrderByIdDesc(equipmentId);
        } else if (status != null) {
            base = repo.findByStatusOrderByIdDesc(status);
        } else {
            base = repo.findAllByOrderByIdDesc();
        }
        return authz.filterWorkOrders(user, base);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@CurrentUser AppUser user, @PathVariable Long id) {
        WorkOrder w = repo.findById(id).orElse(null);
        if (w == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "工单不存在"));
        }
        authz.checkCanReadWorkOrder(user, w);
        return ResponseEntity.ok(w);
    }

    /** 手工建单：管理员或本区域计划员。区域取自设备，保证后续隔离一致。 */
    @PostMapping
    public ResponseEntity<?> create(@CurrentUser AppUser user, @RequestBody WorkOrderRequest req) {
        if (req.equipmentId() == null || req.title() == null || req.title().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "设备和标题必填"));
        }
        Equipment equipment = equipmentRepo.findById(req.equipmentId()).orElse(null);
        if (equipment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        authz.checkCanCreateWorkOrder(user, equipment);
        WorkOrder w = new WorkOrder();
        w.setEquipmentId(req.equipmentId());
        w.setArea(equipment.getArea());
        w.setTitle(req.title());
        w.setType(TYPES.contains(req.type()) ? req.type() : "inspection");
        w.setPriority(PRIORITIES.contains(req.priority()) ? req.priority() : "medium");
        w.setDescription(req.description() == null ? "" : req.description());
        w.setAssignee(req.assignee() == null ? "" : req.assignee());
        w.setAssigneeId(req.assigneeId());
        w.setStatus("open");
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(w));
    }

    /** 派工：管理员或本区域计划员。 */
    @PatchMapping("/{id}/assign")
    public ResponseEntity<?> assign(@CurrentUser AppUser user, @PathVariable Long id,
                                     @RequestBody AssignRequest req) {
        WorkOrder w = repo.findById(id).orElse(null);
        if (w == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "工单不存在"));
        }
        authz.checkCanAssignWorkOrder(user, w);
        if (req.assigneeId() != null) w.setAssigneeId(req.assigneeId());
        if (req.assignee() != null) w.setAssignee(req.assignee());
        return ResponseEntity.ok(repo.save(w));
    }

    /** 状态流转：管理员，或获派/本区域维修员。 */
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@CurrentUser AppUser user, @PathVariable Long id,
                                           @RequestBody StatusRequest req) {
        WorkOrder w = repo.findById(id).orElse(null);
        if (w == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "工单不存在"));
        }
        authz.checkCanTransitionWorkOrder(user, w);
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
