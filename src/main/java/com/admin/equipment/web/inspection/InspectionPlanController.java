package com.admin.equipment.web.inspection;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPlanPoint;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.security.AuthorizationService;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.ScopeService;
import com.admin.equipment.service.inspection.InspectionPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/plans")
public class InspectionPlanController {

    private final InspectionPlanService service;
    private final ScopeService scope;
    private final AuthorizationService authz;

    public InspectionPlanController(InspectionPlanService service, ScopeService scope,
                                     AuthorizationService authz) {
        this.service = service;
        this.scope = scope;
        this.authz = authz;
    }

    @GetMapping
    public List<InspectionPlan> list(@CurrentUser AppUser user,
                                      @RequestParam(required = false) Boolean enabled) {
        return scope.visiblePlans(user, Boolean.TRUE.equals(enabled));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@CurrentUser AppUser user, @PathVariable Long id) {
        InspectionPlan plan = service.getById(id).orElse(null);
        if (plan == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        authz.checkCanReadPlan(user, plan);
        return ResponseEntity.ok(plan);
    }

    @GetMapping("/{id}/points")
    public ResponseEntity<?> listPoints(@CurrentUser AppUser user, @PathVariable Long id,
                                         @RequestParam(defaultValue = "false") boolean detail) {
        InspectionPlan plan = service.getById(id).orElse(null);
        if (plan == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        authz.checkCanReadPlan(user, plan);
        if (detail) {
            List<InspectionPoint> pts = service.getPlanPointsDetail(id);
            return ResponseEntity.ok(authz.filterPoints(user, pts));
        }
        List<InspectionPlanPoint> pps = service.getPlanPoints(id);
        return ResponseEntity.ok(pps);
    }

    @PostMapping
    public ResponseEntity<?> create(@CurrentUser AppUser user,
                                     @RequestBody InspectionPlanService.PlanSpec req) {
        String area = req.area() == null ? "" : req.area().trim();
        authz.checkCanManagePlanArea(user, area);
        try {
            InspectionPlan p = service.create(req);
            return ResponseEntity.status(HttpStatus.CREATED).body(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@CurrentUser AppUser user, @PathVariable Long id,
                                     @RequestBody InspectionPlanService.PlanSpec req) {
        InspectionPlan existing = service.getById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        authz.checkCanManagePlan(user, existing);
        if (req.area() != null) authz.checkCanManagePlanArea(user, req.area().trim());
        try {
            InspectionPlan p = service.update(id, req);
            return ResponseEntity.ok(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@CurrentUser AppUser user, @PathVariable Long id,
                                         @RequestBody Map<String, Boolean> body) {
        InspectionPlan existing = service.getById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        authz.checkCanManagePlan(user, existing);
        boolean enabled = body.getOrDefault("enabled", true);
        service.setEnabled(id, enabled);
        return ResponseEntity.ok(service.getById(id).orElseThrow());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@CurrentUser AppUser user, @PathVariable Long id) {
        InspectionPlan existing = service.getById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        authz.checkCanManagePlan(user, existing);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/route/compare")
    public ResponseEntity<?> compareRoutes(@CurrentUser AppUser user, @PathVariable Long id) {
        InspectionPlan plan = service.getById(id).orElse(null);
        if (plan == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        authz.checkCanReadPlan(user, plan);
        InspectionPlanService.RouteCompareResult r = service.compareRoutes(id);
        return ResponseEntity.ok(Map.of(
                "sequential", r.sequential(),
                "optimized", r.optimized(),
                "distanceSaved", r.distanceSaved(),
                "savedPercent", r.savedPercent(),
                "savedPercentStr", String.format("%.2f%%", r.savedPercent())
        ));
    }

    @PostMapping("/{id}/route/plan")
    public ResponseEntity<?> planRoute(@CurrentUser AppUser user, @PathVariable Long id,
                                        @RequestBody Map<String, Object> body) {
        InspectionPlan plan = service.getById(id).orElse(null);
        if (plan == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        authz.checkCanReadPlan(user, plan);
        Object spObj = body.get("startPointId");
        Long startPointId = spObj instanceof Number ? ((Number) spObj).longValue() : null;
        boolean useOptimized = !"sequential".equals(body.get("routeType"));
        return ResponseEntity.ok(service.planRouteForExecution(id, startPointId, useOptimized));
    }
}
