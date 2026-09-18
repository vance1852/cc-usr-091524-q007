package com.admin.equipment.web.inspection;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.security.AuthorizationService;
import com.admin.equipment.security.CurrentUser;
import com.admin.equipment.security.ScopeService;
import com.admin.equipment.service.inspection.InspectionPointService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/points")
public class InspectionPointController {

    private final InspectionPointService service;
    private final ScopeService scope;
    private final AuthorizationService authz;

    public InspectionPointController(InspectionPointService service, ScopeService scope,
                                      AuthorizationService authz) {
        this.service = service;
        this.scope = scope;
        this.authz = authz;
    }

    public record PointRequest(String code, String name, String location, Double coordX,
                               Double coordY, String equipmentIds, String equipmentType, String area) {}

    @GetMapping
    public List<InspectionPoint> list(@CurrentUser AppUser user,
                                       @RequestParam(required = false) String equipmentType) {
        return scope.visiblePoints(user, equipmentType);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@CurrentUser AppUser user, @PathVariable Long id) {
        InspectionPoint p = service.getById(id).orElse(null);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "巡检点不存在"));
        }
        authz.checkCanReadPoint(user, p);
        return ResponseEntity.ok(p);
    }

    /** 巡检点维护：管理员不限，计划员限本区域（含拟写入区域）。 */
    @PostMapping
    public ResponseEntity<?> create(@CurrentUser AppUser user, @RequestBody PointRequest req) {
        String area = req.area() == null ? "" : req.area().trim();
        authz.checkCanManagePointArea(user, area);
        try {
            InspectionPoint p = service.create(req.code(), req.name(), req.location(),
                    req.coordX(), req.coordY(), req.equipmentIds(), req.equipmentType(), area);
            return ResponseEntity.status(HttpStatus.CREATED).body(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@CurrentUser AppUser user, @PathVariable Long id,
                                     @RequestBody PointRequest req) {
        InspectionPoint existing = service.getById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "巡检点不存在"));
        }
        authz.checkCanManagePoint(user, existing);
        // 跨区域调整也必须落在本人可管理区域
        if (req.area() != null) authz.checkCanManagePointArea(user, req.area().trim());
        try {
            InspectionPoint p = service.update(id, req.name(), req.location(),
                    req.coordX(), req.coordY(), req.equipmentIds(), req.equipmentType(), req.area());
            return ResponseEntity.ok(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@CurrentUser AppUser user, @PathVariable Long id) {
        InspectionPoint existing = service.getById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "巡检点不存在"));
        }
        authz.checkCanManagePoint(user, existing);
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }
}
