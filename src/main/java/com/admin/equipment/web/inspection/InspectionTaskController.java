package com.admin.equipment.web.inspection;

import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.service.inspection.InspectionTaskService;
import com.admin.equipment.security.AuthorizationService;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.CurrentUsers;
import com.admin.equipment.security.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/tasks")
public class InspectionTaskController {

    private final InspectionTaskService service;
    private final AuthorizationService authz;

    public InspectionTaskController(InspectionTaskService service, AuthorizationService authz) {
        this.service = service;
        this.authz = authz;
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

    @GetMapping
    public List<InspectionTask> list(HttpServletRequest request,
                                      @RequestParam(required = false) Long planId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Long assigneeId) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadTask(u)) throw new ForbiddenException("无权查看巡检任务");
        // 所有筛选都在当前用户可见范围内进行，杜绝用参数越权枚举
        return authz.visibleTasks(u).stream()
                .filter(t -> planId == null || planId.equals(t.getPlanId()))
                .filter(t -> status == null || status.isBlank() || status.equals(t.getStatus()))
                .filter(t -> assigneeId == null || assigneeId.equals(t.getAssigneeId()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadTask(u)) throw new ForbiddenException("无权查看巡检任务");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        if (!authz.canAccessTask(u, id)) throw new ForbiddenException("无权访问该任务");
        return service.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "任务不存在")));
    }

    @GetMapping("/{id}/points")
    public ResponseEntity<?> listPoints(HttpServletRequest request, @PathVariable Long id,
                                         @RequestParam(defaultValue = "planned") String order) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadTask(u)) throw new ForbiddenException("无权查看巡检任务");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        if (!authz.canAccessTask(u, id)) throw new ForbiddenException("无权访问该任务");
        List<InspectionTaskPoint> tps = "actual".equals(order)
                ? service.getTaskPointsByActualOrder(id)
                : service.getTaskPoints(id);
        return ResponseEntity.ok(tps);
    }

    @GetMapping("/{id}/records")
    public ResponseEntity<?> listRecords(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadTask(u)) throw new ForbiddenException("无权查看巡检任务");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        if (!authz.canAccessTask(u, id)) throw new ForbiddenException("无权访问该任务");
        return ResponseEntity.ok(service.getTaskRecords(id));
    }

    @GetMapping("/points/{taskPointId}/records")
    public ResponseEntity<?> listPointRecords(HttpServletRequest request, @PathVariable Long taskPointId) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadTask(u)) throw new ForbiddenException("无权查看巡检任务");
        if (!authz.canAccessTaskPoint(u, taskPointId)) {
            throw new ForbiddenException("无权访问该巡检点记录");
        }
        return ResponseEntity.ok(service.getPointRecords(taskPointId));
    }

    @GetMapping("/{id}/abnormalities")
    public ResponseEntity<?> listAbnormalities(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadTask(u)) throw new ForbiddenException("无权查看巡检任务");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        if (!authz.canAccessTask(u, id)) throw new ForbiddenException("无权访问该任务");
        return ResponseEntity.ok(service.getTaskAbnormalities(id));
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(HttpServletRequest request, @RequestBody GenerateRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canGenerateTask(u)) throw new ForbiddenException("无权生成巡检任务");
        try {
            if (req.planId() == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of("detail", "计划ID必填"));
            }
            InspectionPlan plan = service.getPlanOrThrow(req.planId());
            // 计划员只能为本范围计划派工
            if (!authz.canGenerateForPlan(u, plan)) {
                throw new ForbiddenException("无权为该计划生成任务");
            }
            boolean useOpt = req.useOptimizedRoute() == null || Boolean.TRUE.equals(req.useOptimizedRoute());
            InspectionTask t = service.generateTask(req.planId(), req.assigneeId(),
                    req.assigneeName(), useOpt, req.startPointId());
            return ResponseEntity.status(HttpStatus.CREATED).body(t);
        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(HttpServletRequest request, @PathVariable Long id,
                                    @RequestBody(required = false) StartRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canExecuteTask(u)) throw new ForbiddenException("巡检员才能执行任务");
        InspectionTask task = service.getById(id).orElse(null);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        if (!authz.canOperateTask(u, task)) throw new ForbiddenException("该任务未分配给你或所在班组");
        try {
            String name = req != null ? req.inspectorName() : null;
            return ResponseEntity.ok(service.startTask(id, name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/execute")
    public ResponseEntity<?> executePoint(HttpServletRequest request, @RequestBody ExecuteRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canExecuteTask(u)) throw new ForbiddenException("巡检员才能执行巡检");
        if (req.taskId() == null || req.taskPointId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "任务ID和巡检点ID必填"));
        }
        // 对象级：该巡检点必须属于分配给当前巡检员（或本班组）的任务
        if (!authz.canOperateTaskPoint(u, req.taskId(), req.taskPointId())) {
            throw new ForbiddenException("无权执行该巡检点（跨班组/跨任务）");
        }
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

    @PostMapping("/skip")
    public ResponseEntity<?> skipPoint(HttpServletRequest request, @RequestBody SkipRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canExecuteTask(u)) throw new ForbiddenException("巡检员才能跳过巡检点");
        if (req.taskId() == null || req.taskPointId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "任务ID和巡检点ID必填"));
        }
        if (!authz.canOperateTaskPoint(u, req.taskId(), req.taskPointId())) {
            throw new ForbiddenException("无权跳过该巡检点（跨班组/跨任务）");
        }
        try {
            return ResponseEntity.ok(service.skipPoint(req.taskId(), req.taskPointId(), req.reason()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canExecuteTask(u)) throw new ForbiddenException("巡检员才能取消任务");
        InspectionTask task = service.getById(id).orElse(null);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        if (!authz.canOperateTask(u, task)) throw new ForbiddenException("该任务未分配给你或所在班组");
        try {
            return ResponseEntity.ok(service.cancelTask(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/detect-missed-timeout")
    public ResponseEntity<?> detectMissedAndTimeout(HttpServletRequest request) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canRunSystemJob(u)) throw new ForbiddenException("仅管理员可执行漏检/超时检测");
        service.detectMissedAndTimeout();
        return ResponseEntity.ok(Map.of("result", "已执行漏检与超时检测"));
    }

    @PostMapping("/abnormality/report")
    public ResponseEntity<?> reportAbnormality(HttpServletRequest request,
                                                @RequestBody ReportAbnormalRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReportAbnormality(u)) throw new ForbiddenException("无权上报巡检异常");
        if (req.taskId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "任务ID必填"));
        }
        // 对象级：只能对自己有权访问的任务上报异常
        if (!authz.canAccessTask(u, req.taskId())) {
            throw new ForbiddenException("无权对该任务上报异常");
        }
        try {
            InspectionAbnormality ab = service.reportAbnormality(req.taskId(), req.taskPointId(),
                    req.recordId(), req.equipmentId(), req.title(), req.description(),
                    req.severity(), req.workOrderType());
            return ResponseEntity.status(HttpStatus.CREATED).body(ab);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/abnormality/recheck")
    public ResponseEntity<?> recheckAbnormality(HttpServletRequest request,
                                                 @RequestBody RecheckRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canRecheckAbnormality(u)) throw new ForbiddenException("无权复检异常");
        if (req.abnormalityId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "异常ID必填"));
        }
        InspectionAbnormality ab = service.getAbnormalityOrThrow(req.abnormalityId());
        if (!authz.canAccessAbnormality(u, ab)) throw new ForbiddenException("无权复检该异常");
        try {
            return ResponseEntity.ok(service.recheckAbnormality(req.abnormalityId(),
                    req.result(), req.recheckBy()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }
}
