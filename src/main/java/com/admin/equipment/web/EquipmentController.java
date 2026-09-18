package com.admin.equipment.web;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.repo.EquipmentRepository;
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
import java.util.Set;

@RestController
@RequestMapping("/api/equipments")
public class EquipmentController {

    private static final Set<String> STATUSES = Set.of("normal", "warning", "fault", "maintenance");

    private final EquipmentRepository repo;
    private final AuthorizationService authz;

    public EquipmentController(EquipmentRepository repo, AuthorizationService authz) {
        this.repo = repo;
        this.authz = authz;
    }

    public record EquipmentRequest(String code, String name, String location, String type, String status) {}

    @GetMapping
    public List<Equipment> list(HttpServletRequest request) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadEquipment(u)) throw new ForbiddenException("无权查看设备");
        return authz.visibleEquipment(u);
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody EquipmentRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWriteEquipment(u)) throw new ForbiddenException("仅管理员可维护设备台账");
        if (req.code() == null || req.code().isBlank() || req.name() == null || req.name().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "编号和名称必填"));
        }
        if (repo.existsByCode(req.code())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "设备编号已存在"));
        }
        Equipment e = new Equipment();
        e.setCode(req.code());
        e.setName(req.name());
        e.setLocation(req.location() == null ? "" : req.location());
        e.setType(req.type() == null ? "" : req.type());
        e.setStatus(validStatus(req.status()) ? req.status() : "normal");
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(e));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canReadEquipment(u)) throw new ForbiddenException("无权查看设备");
        Equipment e = repo.findById(id).orElse(null);
        if (e == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        // 存在但无权：统一 403，与列表过滤一致，不返回 404
        if (!authz.canAccessEquipment(u, id)) throw new ForbiddenException("无权访问该设备");
        return ResponseEntity.ok(e);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(HttpServletRequest request, @PathVariable Long id,
                                     @RequestBody EquipmentRequest req) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWriteEquipment(u)) throw new ForbiddenException("仅管理员可修改设备台账");
        Equipment e = repo.findById(id).orElse(null);
        if (e == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        if (req.name() != null && !req.name().isBlank()) e.setName(req.name());
        if (req.location() != null) e.setLocation(req.location());
        if (req.type() != null) e.setType(req.type());
        if (validStatus(req.status())) e.setStatus(req.status());
        return ResponseEntity.ok(repo.save(e));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest request, @PathVariable Long id) {
        CurrentUser u = CurrentUsers.from(request);
        if (!authz.canWriteEquipment(u)) throw new ForbiddenException("仅管理员可删除设备");
        if (!repo.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private boolean validStatus(String s) {
        return s != null && STATUSES.contains(s);
    }
}
