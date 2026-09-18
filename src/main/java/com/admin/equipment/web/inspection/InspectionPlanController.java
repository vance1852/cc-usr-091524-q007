package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPlanPoint;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.service.inspection.InspectionPlanService;
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
@RequestMapping("/api/inspection/plans")
public class InspectionPlanController {

    private final InspectionPlanService service;
    private final AuthorizationService authz;

    public InspectionPlanController(InspectionPlanService service, AuthorizationService authz) {
        this.service = service;
        this.authz = authz;
    }

    @GetMapping
    public List<InspectionPlan> list(HttpServletRequest request,
                                      @RequestParam(required = false) Boolean enabled) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadPlan(u)) throw new ForbiddenException("无权查看计划");
        return authz.visiblePlans(u).stream()
                .filter(p -> !Boolean.TRUE.equals(enabled) || Boolean.TRUE.equals(p.getEnabled()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadPlan(u)) throw new ForbiddenException("无权查看计划");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        if (!authz.canAccessPlan(u, id)) throw new ForbiddenException("无权访问该计划");
        return service.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "计划不存在")));
    }

    @GetMapping("/{id}/points")
    public ResponseEntity<?> listPoints(HttpServletRequest request, @PathVariable Long id,
                                         @RequestParam(defaultValue = "false") boolean detail) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadPlan(u)) throw new ForbiddenException("无权查看计划");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        if (!authz.canAccessPlan(u, id)) throw new ForbiddenException("无权访问该计划");
        if (detail) {
            List<InspectionPoint> pts = service.getPlanPointsDetail(id);
            return ResponseEntity.ok(pts);
        } else {
            List<InspectionPlanPoint> pps = service.getPlanPoints(id);
            return ResponseEntity.ok(pps);
        }
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request,
                                     @RequestBody InspectionPlanService.PlanSpec req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWritePlan(u)) throw new ForbiddenException("无权维护计划");
        // 计划员只能用本区域巡检点编排计划
        if (!u.isAdmin() && !planPointsWithinScope(u, req.pointIds())) {
            throw new ForbiddenException("计划只能包含本管理区域内的巡检点");
        }
        try {
            InspectionPlan p = service.create(req);
            return ResponseEntity.status(HttpStatus.CREATED).body(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest request, @PathVariable Long id,
                                     @RequestBody InspectionPlanService.PlanSpec req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWritePlan(u)) throw new ForbiddenException("无权维护计划");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        if (!u.isAdmin() && !authz.canAccessPlan(u, id)) throw new ForbiddenException("无权修改该计划");
        if (!u.isAdmin() && req.pointIds() != null && !req.pointIds().isEmpty()
                && !planPointsWithinScope(u, req.pointIds())) {
            throw new ForbiddenException("计划只能包含本管理区域内的巡检点");
        }
        try {
            InspectionPlan p = service.update(id, req);
            return ResponseEntity.ok(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(HttpServletRequest request, @PathVariable Long id,
                                         @RequestBody Map<String, Boolean> body) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWritePlan(u)) throw new ForbiddenException("无权维护计划");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        if (!u.isAdmin() && !authz.canAccessPlan(u, id)) throw new ForbiddenException("无权操作该计划");
        try {
            boolean enabled = body.getOrDefault("enabled", true);
            service.setEnabled(id, enabled);
            return service.getById(id)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWritePlan(u)) throw new ForbiddenException("无权维护计划");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        if (!u.isAdmin() && !authz.canAccessPlan(u, id)) throw new ForbiddenException("无权删除该计划");
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    @GetMapping("/{id}/route/compare")
    public ResponseEntity<?> compareRoutes(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadPlan(u)) throw new ForbiddenException("无权查看计划");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        if (!authz.canAccessPlan(u, id)) throw new ForbiddenException("无权访问该计划");
        try {
            InspectionPlanService.RouteCompareResult r = service.compareRoutes(id);
            return ResponseEntity.ok(Map.of(
                    "sequential", r.sequential(),
                    "optimized", r.optimized(),
                    "distanceSaved", r.distanceSaved(),
                    "savedPercent", r.savedPercent(),
                    "savedPercentStr", String.format("%.2f%%", r.savedPercent())
            ));
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/route/plan")
    public ResponseEntity<?> planRoute(HttpServletRequest request, @PathVariable Long id,
                                        @RequestBody Map<String, Object> body) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadPlan(u)) throw new ForbiddenException("无权查看计划");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        if (!authz.canAccessPlan(u, id)) throw new ForbiddenException("无权访问该计划");
        try {
            Object spObj = body.get("startPointId");
            Long startPointId;
            if (spObj instanceof Number) {
                startPointId = ((Number) spObj).longValue();
            } else {
                startPointId = null;
            }
            boolean useOptimized = !"sequential".equals(body.get("routeType"));
            var r = service.planRouteForExecution(id, startPointId, useOptimized);
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 校验计划所用巡检点是否都在当前用户管理区域内。 */
    private boolean planPointsWithinScope(CurrentUser u, List<Long> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) return false;
        for (Long pid : pointIds) {
            if (!authz.canAccessPoint(u, pid)) return false;
        }
        return true;
    }
}
