package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.security.AuthorizationService;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.CurrentUsers;
import com.admin.equipment.security.ForbiddenException;
import com.admin.equipment.service.inspection.InspectionStatsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/inspection/stats")
public class InspectionStatsController {

    private final InspectionStatsService service;
    private final AuthorizationService authz;
    private final EquipmentRepository equipmentRepo;

    public InspectionStatsController(InspectionStatsService service, AuthorizationService authz,
                                      EquipmentRepository equipmentRepo) {
        this.service = service;
        this.authz = authz;
        this.equipmentRepo = equipmentRepo;
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(HttpServletRequest request) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadStats(u)) throw new ForbiddenException("无权查看统计");
        // 统计口径与任务/异常列表完全一致
        List<InspectionTask> tasks = authz.visibleTasks(u);
        List<InspectionAbnormality> abs = authz.visibleAbnormalities(u);
        long planCount = authz.visiblePlans(u).size();
        return ResponseEntity.ok(service.getOverallStats(tasks, abs, planCount));
    }

    @GetMapping("/date-range")
    public ResponseEntity<?> dateRange(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadStats(u)) throw new ForbiddenException("无权查看统计");
        if (startDate.isAfter(endDate)) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "开始日期不能晚于结束日期"));
        }
        Set<Long> allowed = visibleTaskIdSet(u);
        List<InspectionStatsService.DateStats> stats = service.getDateRangeStats(startDate, endDate, allowed);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/tasks/completion")
    public ResponseEntity<?> taskCompletion(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadStats(u)) throw new ForbiddenException("无权查看统计");
        if (startDate.isAfter(endDate)) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "开始日期不能晚于结束日期"));
        }
        Set<Long> allowed = visibleTaskIdSet(u);
        return ResponseEntity.ok(service.getTaskCompletionStats(startDate, endDate, allowed));
    }

    @GetMapping("/equipment/{equipmentId}/history")
    public ResponseEntity<?> equipmentHistory(HttpServletRequest request, @PathVariable Long equipmentId) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadStats(u)) throw new ForbiddenException("无权查看统计");
        if (!equipmentRepo.existsById(equipmentId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        if (!authz.canAccessEquipment(u, equipmentId)) {
            throw new ForbiddenException("无权查看该设备巡检历史");
        }
        return ResponseEntity.ok(service.getEquipmentHistory(equipmentId));
    }

    @GetMapping("/closed-loop")
    public ResponseEntity<?> closedLoop(HttpServletRequest request,
                                         @RequestParam(required = false) String status) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadStats(u)) throw new ForbiddenException("无权查看统计");
        return ResponseEntity.ok(service.getClosedLoopTraces(status, authz.visibleAbnormalities(u)));
    }

    @GetMapping("/tasks/{taskId}/trace")
    public ResponseEntity<?> executionTrace(HttpServletRequest request, @PathVariable Long taskId) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadStats(u)) throw new ForbiddenException("无权查看统计");
        if (!authz.canAccessTask(u, taskId)) {
            // 不存在给 404，存在但越权给 403，二者不矛盾
            if (service.taskExists(taskId)) {
                throw new ForbiddenException("无权查看该任务执行轨迹");
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        return ResponseEntity.ok(service.getExecutionTrace(taskId));
    }

    /** 管理员/审计为全范围（null 表示不限），其余角色给出可见任务白名单。 */
    private Set<Long> visibleTaskIdSet(CurrentUser u) {
        if (u.isAdmin() || u.hasRole(com.admin.equipment.security.Role.AUDITOR)) return null;
        Set<Long> ids = new HashSet<>();
        for (InspectionTask t : authz.visibleTasks(u)) ids.add(t.getId());
        return ids;
    }
}
