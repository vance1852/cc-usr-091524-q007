package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateItem;
import com.admin.equipment.service.inspection.InspectionTemplateService;
import com.admin.equipment.service.inspection.InspectionTemplateService.ItemSpec;
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
@RequestMapping("/api/inspection/templates")
public class InspectionTemplateController {

    private final InspectionTemplateService service;
    private final AuthorizationService authz;

    public InspectionTemplateController(InspectionTemplateService service, AuthorizationService authz) {
        this.service = service;
        this.authz = authz;
    }

    public record TemplateRequest(String code, String name, String equipmentType,
                                   String description, List<ItemSpec> items) {}

    @GetMapping
    public List<InspectionTemplate> list(HttpServletRequest request,
                                          @RequestParam(required = false) String equipmentType) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadTemplate(u)) throw new ForbiddenException("无权查看模板");
        return authz.visibleTemplates(u).stream()
                .filter(t -> equipmentType == null || equipmentType.isBlank()
                        || equipmentType.equals(t.getEquipmentType()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadTemplate(u)) throw new ForbiddenException("无权查看模板");
        if (!authz.canAccessTemplate(u, id)) {
            // 模板不存在给 404；存在但角色无权读已被上面拦截，这里保持对象一致性
            if (service.getById(id).isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "模板不存在"));
            }
            throw new ForbiddenException("无权访问该模板");
        }
        return service.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "模板不存在")));
    }

    @GetMapping("/{id}/items")
    public ResponseEntity<?> listItems(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadTemplate(u)) throw new ForbiddenException("无权查看模板");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "模板不存在"));
        }
        if (!authz.canAccessTemplate(u, id)) throw new ForbiddenException("无权访问该模板");
        List<InspectionTemplateItem> items = service.getItems(id);
        return ResponseEntity.ok(items);
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody TemplateRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWriteTemplate(u)) throw new ForbiddenException("无权维护模板");
        try {
            InspectionTemplate t = service.create(req.code(), req.name(), req.equipmentType(),
                    req.description(), req.items());
            return ResponseEntity.status(HttpStatus.CREATED).body(t);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest request, @PathVariable Long id,
                                     @RequestBody TemplateRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWriteTemplate(u)) throw new ForbiddenException("无权维护模板");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "模板不存在"));
        }
        try {
            InspectionTemplate t = service.update(id, req.name(), req.equipmentType(),
                    req.description(), req.items());
            return ResponseEntity.ok(t);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWriteTemplate(u)) throw new ForbiddenException("无权维护模板");
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "模板不存在"));
        }
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }
}
