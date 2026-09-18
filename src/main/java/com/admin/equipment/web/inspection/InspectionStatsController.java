package com.admin.equipment.web.inspection;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.security.AuthorizationService;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.service.inspection.InspectionStatsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 统计接口：全部按当前用户数据范围计算，口径与列表一致。
 * 审计员跨区域只读；其余角色只统计本范围对象。
 */
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
    public ResponseEntity<?> overview(@CurrentUser AppUser user) {
        return ResponseEntity.ok(service.getOverallStats(user));
    }

    @GetMapping("/date-range")
    public ResponseEntity<?> dateRange(
            @CurrentUser AppUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "开始日期不能晚于结束日期"));
        }
        List<InspectionStatsService.DateStats> stats = service.getDateRangeStats(user, startDate, endDate);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/tasks/completion")
    public ResponseEntity<?> taskCompletion(
            @CurrentUser AppUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "开始日期不能晚于结束日期"));
        }
        return ResponseEntity.ok(service.getTaskCompletionStats(user, startDate, endDate));
    }

    @GetMapping("/equipment/{equipmentId}/history")
    public ResponseEntity<?> equipmentHistory(@CurrentUser AppUser user, @PathVariable Long equipmentId) {
        Equipment eq = equipmentRepo.findById(equipmentId).orElse(null);
        if (eq == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        // 对象级越权统一 403（与 GET /api/equipments/{id} 同源）
        authz.checkCanReadEquipment(user, eq);
        return ResponseEntity.ok(service.getEquipmentHistory(user, equipmentId));
    }

    @GetMapping("/closed-loop")
    public ResponseEntity<?> closedLoop(@CurrentUser AppUser user,
                                         @RequestParam(required = false) String status) {
        return ResponseEntity.ok(service.getClosedLoopTraces(user, status));
    }

    @GetMapping("/tasks/{taskId}/trace")
    public ResponseEntity<?> executionTrace(@CurrentUser AppUser user, @PathVariable Long taskId) {
        InspectionTask t = service.findTaskRaw(taskId);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        // 对象级越权统一 403（与 GET /api/inspection/tasks/{id} 同源）
        authz.checkCanReadTask(user, t);
        return ResponseEntity.ok(service.getExecutionTrace(taskId));
    }
}
