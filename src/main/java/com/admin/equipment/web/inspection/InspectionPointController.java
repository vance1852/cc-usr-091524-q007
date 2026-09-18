package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.service.inspection.InspectionPointService;
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
@RequestMapping("/api/inspection/points")
public class InspectionPointController {

    private final InspectionPointService service;
    private final AuthorizationService authz;

    public InspectionPointController(InspectionPointService service, AuthorizationService authz) {
        this.service = service;
        this.authz = authz;
    }

    public record PointRequest(String code, String name, String location, Double coordX,
                               Double coordY, String equipmentIds, String equipmentType) {}

    @GetMapping
    public List<InspectionPoint> list(HttpServletRequest request,
                                      @RequestParam(required = false) String equipmentType) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadPoint(u)) throw new ForbiddenException("无权查看巡检点");
        return authz.visiblePoints(u).stream()
                .filter(p -> equipmentType == null || equipmentType.isBlank()
                        || equipmentType.equals(p.getEquipmentType()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadPoint(u)) throw new ForbiddenException("无权查看巡检点");
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "巡检点不存在"));
        }
        if (!authz.canAccessPoint(u, id)) throw new ForbiddenException("无权访问该巡检点");
        return service.getById(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody PointRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWritePoint(u)) throw new ForbiddenException("无权维护巡检点");
        // 计划员只能在本管理区域内新建巡检点
        if (!u.managesArea(req.location())) {
            throw new ForbiddenException("无权在该区域新建巡检点");
        }
        try {
            InspectionPoint p = service.create(req.code(), req.name(), req.location(),
                    req.coordX(), req.coordY(), req.equipmentIds(), req.equipmentType());
            return ResponseEntity.status(HttpStatus.CREATED).body(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest request, @PathVariable Long id,
                                     @RequestBody PointRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWritePoint(u)) throw new ForbiddenException("无权维护巡检点");
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "巡检点不存在"));
        }
        // 现有巡检点必须在本区域，且新区域也必须受其管理，防止跨区域挪动
        if (!authz.canAccessPoint(u, id)) throw new ForbiddenException("无权修改该巡检点");
        if (req.location() != null && !u.managesArea(req.location())) {
            throw new ForbiddenException("无权将巡检点移动到该区域");
        }
        try {
            InspectionPoint p = service.update(id, req.name(), req.location(),
                    req.coordX(), req.coordY(), req.equipmentIds(), req.equipmentType());
            return ResponseEntity.ok(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWritePoint(u)) throw new ForbiddenException("无权维护巡检点");
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "巡检点不存在"));
        }
        if (!authz.canAccessPoint(u, id)) throw new ForbiddenException("无权删除该巡检点");
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }
}
