package com.admin.equipment.security;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据范围解析：所有“列表/统计”入口都经此取得当前用户可见的对象集合，
 * 与对象级 403 判定（{@link AuthorizationService}）同源，保证列表过滤与单对象访问结论一致，
 * 不会出现“列表里没有但能直接访问”或反之的矛盾。
 */
@Service
public class ScopeService {

    private final AuthorizationService authz;
    private final EquipmentRepository equipmentRepo;
    private final WorkOrderRepository workOrderRepo;
    private final InspectionTaskRepository taskRepo;
    private final InspectionPlanRepository planRepo;
    private final InspectionPointRepository pointRepo;
    private final InspectionAbnormalityRepository abnormalityRepo;

    public ScopeService(AuthorizationService authz,
                         EquipmentRepository equipmentRepo,
                         WorkOrderRepository workOrderRepo,
                         InspectionTaskRepository taskRepo,
                         InspectionPlanRepository planRepo,
                         InspectionPointRepository pointRepo,
                         InspectionAbnormalityRepository abnormalityRepo) {
        this.authz = authz;
        this.equipmentRepo = equipmentRepo;
        this.workOrderRepo = workOrderRepo;
        this.taskRepo = taskRepo;
        this.planRepo = planRepo;
        this.pointRepo = pointRepo;
        this.abnormalityRepo = abnormalityRepo;
    }

    public List<Equipment> visibleEquipments(AppUser user) {
        return authz.filterEquipment(user, equipmentRepo.findAllByOrderByIdAsc());
    }

    public List<WorkOrder> visibleWorkOrders(AppUser user) {
        return authz.filterWorkOrders(user, workOrderRepo.findAllByOrderByIdDesc());
    }

    public List<InspectionPlan> visiblePlans(AppUser user, boolean enabledOnly) {
        List<InspectionPlan> all = enabledOnly
                ? planRepo.findByEnabledTrueOrderByCodeAsc()
                : planRepo.findAllByOrderByCodeAsc();
        return authz.filterPlans(user, all);
    }

    public List<InspectionPoint> visiblePoints(AppUser user, String equipmentType) {
        List<InspectionPoint> all = equipmentType != null && !equipmentType.isBlank()
                ? pointRepo.findByEquipmentTypeOrderByCodeAsc(equipmentType)
                : pointRepo.findAllByOrderByCodeAsc();
        return authz.filterPoints(user, all);
    }

    /** 预解析全部计划区域，供批量任务判定使用（避免 N+1）。 */
    public Map<Long, String> planAreaMap() {
        Map<Long, String> map = new HashMap<>();
        for (InspectionPlan p : planRepo.findAll()) {
            map.put(p.getId(), p.getArea() == null ? "" : p.getArea());
        }
        return map;
    }

    public List<InspectionTask> visibleTasks(AppUser user) {
        Map<Long, String> areas = planAreaMap();
        return taskRepo.findAllByOrderByCreatedAtDesc().stream()
                .filter(t -> authz.canReadTask(user, t, areas.getOrDefault(t.getPlanId(), "")))
                .toList();
    }

    /** 判定单个任务可见（单对象访问），内部解析计划区域。 */
    public boolean canReadTask(AppUser user, InspectionTask task) {
        return authz.canReadTask(user, task);
    }

    public List<InspectionAbnormality> visibleAbnormalities(AppUser user) {
        Map<Long, InspectionTask> taskMap = new HashMap<>();
        for (InspectionTask t : taskRepo.findAll()) taskMap.put(t.getId(), t);
        Map<Long, String> areas = planAreaMap();
        Map<Long, WorkOrder> orderMap = loadOrdersForAbnormalities();
        return abnormalityRepo.findAllByOrderByReportedAtDesc().stream()
                .filter(ab -> {
                    InspectionTask t = taskMap.get(ab.getTaskId());
                    WorkOrder o = ab.getWorkOrderId() == null ? null : orderMap.get(ab.getWorkOrderId());
                    String taskArea = t == null ? "" : areas.getOrDefault(t.getPlanId(), "");
                    return authz.canReadAbnormality(user, ab, t, o, taskArea);
                })
                .toList();
    }

    private Map<Long, WorkOrder> loadOrdersForAbnormalities() {
        Map<Long, WorkOrder> map = new HashMap<>();
        for (WorkOrder o : workOrderRepo.findAll()) map.put(o.getId(), o);
        return map;
    }
}
