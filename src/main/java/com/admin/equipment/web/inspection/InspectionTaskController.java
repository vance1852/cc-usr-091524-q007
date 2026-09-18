package com.admin.equipment.web.inspection;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.security.AuthorizationService;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.ScopeService;
import com.admin.equipment.service.inspection.InspectionTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/tasks")
public class InspectionTaskController {

    private final InspectionTaskService service;
    private final ScopeService scope;
    private final AuthorizationService authz;
    private final WorkOrderRepository workOrderRepo;

    public InspectionTaskController(InspectionTaskService service, ScopeService scope,
                                     AuthorizationService authz, WorkOrderRepository workOrderRepo) {
        this.service = service;
        this.scope = scope;
        this.authz = authz;
        this.workOrderRepo = workOrderRepo;
    }

    public record GenerateRequest(Long planId, Long assigneeId, String assigneeName,
                                   Boolean useOptimizedRoute, Long startPointId) {}

    public record StartRequest(String inspectorName) {}

    public record ExecuteRequest(Long taskId, Long taskPointId, String inspectorName,
                                  List<InspectionTaskService.PointItemSpec> items, String remark) {}

    public record SkipRequest(Long taskId, Long taskPointId, String reason) {}

    public record ReportAbnormalRequest(Long taskId, Long taskPointId, Long recordId, Long equipmentId,
                                         String title, String description, String severity, String workOrderType) {}

    public record RecheckRequest(Long abnormalityId, String result, String recheckBy) {}

    /** 任务列表：任何筛选分支都先收敛到当前用户可见任务，杜绝跨范围列举。 */
    @GetMapping
    public List<InspectionTask> list(@CurrentUser AppUser user,
                                      @RequestParam(required = false) Long planId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Long assigneeId) {
        List<InspectionTask> visible = scope.visibleTasks(user);
        if (planId != null) visible = visible.stream().filter(t -> planId.equals(t.getPlanId())).toList();
        if (status != null && !status.isBlank())
            visible = visible.stream().filter(t -> status.equals(t.getStatus())).toList();
        if (assigneeId != null) visible = visible.stream().filter(t -> assigneeId.equals(t.getAssigneeId())).toList();
        return visible;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@CurrentUser AppUser user, @PathVariable Long id) {
        InspectionTask t = service.getById(id).orElse(null);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        authz.checkCanReadTask(user, t);
        return ResponseEntity.ok(t);
    }

    @GetMapping("/{id}/points")
    public ResponseEntity<?> listPoints(@CurrentUser AppUser user, @PathVariable Long id,
                                         @RequestParam(defaultValue = "planned") String order) {
        InspectionTask t = service.getById(id).orElse(null);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        authz.checkCanReadTask(user, t);
        List<InspectionTaskPoint> tps = "actual".equals(order)
                ? service.getTaskPointsByActualOrder(id)
                : service.getTaskPoints(id);
        return ResponseEntity.ok(tps);
    }

    @GetMapping("/{id}/records")
    public ResponseEntity<?> listRecords(@CurrentUser AppUser user, @PathVariable Long id) {
        InspectionTask t = service.getById(id).orElse(null);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        authz.checkCanReadTask(user, t);
        return ResponseEntity.ok(service.getTaskRecords(id));
    }

    @GetMapping("/points/{taskPointId}/records")
    public ResponseEntity<?> listPointRecords(@CurrentUser AppUser user, @PathVariable Long taskPointId) {
        InspectionTaskPoint tp = service.getTaskPointById(taskPointId).orElse(null);
        if (tp == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务巡检点不存在"));
        }
        InspectionTask t = service.getById(tp.getTaskId()).orElse(null);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        authz.checkCanReadTask(user, t);
        return ResponseEntity.ok(service.getPointRecords(taskPointId));
    }

    @GetMapping("/{id}/abnormalities")
    public ResponseEntity<?> listAbnormalities(@CurrentUser AppUser user, @PathVariable Long id) {
        InspectionTask t = service.getById(id).orElse(null);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        authz.checkCanReadTask(user, t);
        return ResponseEntity.ok(service.getTaskAbnormalities(id));
    }

    /** 生成任务：管理员或本区域计划员。 */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@CurrentUser AppUser user, @RequestBody GenerateRequest req) {
        if (req.planId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "计划ID必填"));
        }
        InspectionPlan plan = service.getPlanById(req.planId()).orElse(null);
        if (plan == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "巡检计划不存在"));
        }
        authz.checkCanGenerateTask(user, plan);
        try {
            boolean useOpt = req.useOptimizedRoute() == null || Boolean.TRUE.equals(req.useOptimizedRoute());
            InspectionTask t = service.generateTask(req.planId(), req.assigneeId(),
                    req.assigneeName(), useOpt, req.startPointId());
            return ResponseEntity.status(HttpStatus.CREATED).body(t);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 开始执行：被分配人或本班组巡检员（管理员亦可）。 */
    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@CurrentUser AppUser user, @PathVariable Long id,
                                    @RequestBody(required = false) StartRequest req) {
        InspectionTask t = service.getById(id).orElse(null);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        authz.checkCanExecuteTask(user, t);
        try {
            String name = req != null ? req.inspectorName() : null;
            return ResponseEntity.ok(service.startTask(id, name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 执行巡检点：被分配人或本班组巡检员。 */
    @PostMapping("/execute")
    public ResponseEntity<?> executePoint(@CurrentUser AppUser user, @RequestBody ExecuteRequest req) {
        if (req.taskId() == null || req.taskPointId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "任务ID和巡检点ID必填"));
        }
        InspectionTask t = service.getById(req.taskId()).orElse(null);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        authz.checkCanExecuteTask(user, t);
        try {
            InspectionTaskService.PointExecuteResult r = service.executePoint(
                    req.taskId(), req.taskPointId(), req.inspectorName(), req.items(), req.remark());
            return ResponseEntity.ok(Map.of(
                    "taskPoint", r.taskPoint(),
                    "records", r.records(),
                    "abnormalities", r.abnormalities(),
                    "createdWorkOrders", r.createdWorkOrders()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 跳过巡检点：被分配人或本班组巡检员。 */
    @PostMapping("/skip")
    public ResponseEntity<?> skipPoint(@CurrentUser AppUser user, @RequestBody SkipRequest req) {
        if (req.taskId() == null || req.taskPointId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "任务ID和巡检点ID必填"));
        }
        InspectionTask t = service.getById(req.taskId()).orElse(null);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        authz.checkCanExecuteTask(user, t);
        try {
            return ResponseEntity.ok(service.skipPoint(req.taskId(), req.taskPointId(), req.reason()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@CurrentUser AppUser user, @PathVariable Long id) {
        InspectionTask t = service.getById(id).orElse(null);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        authz.checkCanManageTask(user, t);
        try {
            return ResponseEntity.ok(service.cancelTask(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/detect-missed-timeout")
    public ResponseEntity<?> detectMissedAndTimeout(@CurrentUser AppUser user) {
        authz.checkCanRunSystemJob(user);
        service.detectMissedAndTimeout();
        return ResponseEntity.ok(Map.of("result", "已执行漏检与超时检测"));
    }

    /** 巡检过程中上报异常：执行该任务的巡检员（或管理员）。 */
    @PostMapping("/abnormality/report")
    public ResponseEntity<?> reportAbnormality(@CurrentUser AppUser user,
                                                @RequestBody ReportAbnormalRequest req) {
        if (req.taskId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "任务ID必填"));
        }
        InspectionTask t = service.getById(req.taskId()).orElse(null);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        authz.checkCanExecuteTask(user, t);
        try {
            InspectionAbnormality ab = service.reportAbnormality(req.taskId(), req.taskPointId(),
                    req.recordId(), req.equipmentId(), req.title(), req.description(),
                    req.severity(), req.workOrderType());
            return ResponseEntity.status(HttpStatus.CREATED).body(ab);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 异常复核闭环：管理员、本区域计划员或处理该工单的维修员。 */
    @PostMapping("/abnormality/recheck")
    public ResponseEntity<?> recheckAbnormality(@CurrentUser AppUser user, @RequestBody RecheckRequest req) {
        if (req.abnormalityId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "异常ID必填"));
        }
        InspectionAbnormality ab = service.getAbnormalityById(req.abnormalityId()).orElse(null);
        if (ab == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "异常不存在"));
        }
        InspectionTask task = ab.getTaskId() == null ? null : service.getById(ab.getTaskId()).orElse(null);
        WorkOrder order = ab.getWorkOrderId() == null ? null
                : workOrderRepo.findById(ab.getWorkOrderId()).orElse(null);
        authz.checkCanManageAbnormality(user, ab, task, order);
        try {
            return ResponseEntity.ok(service.recheckAbnormality(req.abnormalityId(),
                    req.result(), req.recheckBy()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }
}
