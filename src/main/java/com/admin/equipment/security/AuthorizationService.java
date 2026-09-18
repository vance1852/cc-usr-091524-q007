package com.admin.equipment.security;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPlanPoint;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionRecord;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.model.inspection.InspectionTaskPoint;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import com.admin.equipment.repo.inspection.InspectionPlanPointRepository;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.repo.inspection.InspectionRecordRepository;
import com.admin.equipment.repo.inspection.InspectionTaskPointRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 统一授权边界：所有控制器只调用这里，不重复散落角色/班组判断。
 *
 * <p>角色矩阵（读 = 列表与对象查看；写 = 增删改与执行）：
 * <pre>
 *   ADMIN      全部读/写（含人员授权管理）
 *   PLANNER    本区域 设备/巡检点 读；巡检点/模板/计划 写；可生成本范围计划任务
 *   INSPECTOR  仅本人或本班组任务读/执行；任务内设备/工单/异常/记录随任务可见
 *   MAINTAINER 本区域设备/工单读；获派(本人或本班组)工单及本区域工单处理
 *   AUDITOR    跨区域只读：设备、工单、任务、异常、统计
 * </pre>
 *
 * 对象级越权（存在但无权访问）统一判定为拒绝；控制器映射为 403，
 * 与列表过滤保持一致，绝不回退成 404。
 */
@Service
public class AuthorizationService {

    private final EquipmentRepository equipmentRepo;
    private final WorkOrderRepository workOrderRepo;
    private final InspectionPlanRepository planRepo;
    private final PlanPointReader planPointReader;
    private final InspectionPointRepository pointRepo;
    private final InspectionTaskRepository taskRepo;
    private final InspectionTaskPointRepository taskPointRepo;
    private final InspectionRecordRepository recordRepo;
    private final InspectionAbnormalityRepository abnormalityRepo;
    private final InspectionTemplateRepository templateRepo;

    public AuthorizationService(EquipmentRepository equipmentRepo,
                                 WorkOrderRepository workOrderRepo,
                                 InspectionPlanRepository planRepo,
                                 InspectionPlanPointRepository planPointRepo,
                                 InspectionPointRepository pointRepo,
                                 InspectionTaskRepository taskRepo,
                                 InspectionTaskPointRepository taskPointRepo,
                                 InspectionRecordRepository recordRepo,
                                 InspectionAbnormalityRepository abnormalityRepo,
                                 InspectionTemplateRepository templateRepo) {
        this.equipmentRepo = equipmentRepo;
        this.workOrderRepo = workOrderRepo;
        this.planRepo = planRepo;
        this.planPointReader = planPointRepo::findByPlanIdOrderBySequenceNoAsc;
        this.pointRepo = pointRepo;
        this.taskRepo = taskRepo;
        this.taskPointRepo = taskPointRepo;
        this.recordRepo = recordRepo;
        this.abnormalityRepo = abnormalityRepo;
        this.templateRepo = templateRepo;
    }

    /** 函数式读取计划下巡检点，便于单测替换。 */
    @FunctionalInterface
    public interface PlanPointReader {
        List<InspectionPlanPoint> read(Long planId);
    }

    // ---------------------------------------------------------------------
    // 角色能力（功能级）
    // ---------------------------------------------------------------------

    public boolean canReadEquipment(CurrentUser u) {
        return anyRole(u, Role.ADMIN, Role.PLANNER, Role.INSPECTOR, Role.MAINTAINER, Role.AUDITOR);
    }

    public boolean canWriteEquipment(CurrentUser u) {
        return u.isAdmin();
    }

    public boolean canReadPoint(CurrentUser u) {
        return anyRole(u, Role.ADMIN, Role.PLANNER, Role.INSPECTOR, Role.MAINTAINER, Role.AUDITOR);
    }

    public boolean canWritePoint(CurrentUser u) {
        return u.isAdmin() || u.hasRole(Role.PLANNER);
    }

    public boolean canReadTemplate(CurrentUser u) {
        return anyRole(u, Role.ADMIN, Role.PLANNER, Role.INSPECTOR, Role.AUDITOR);
    }

    public boolean canWriteTemplate(CurrentUser u) {
        return u.isAdmin() || u.hasRole(Role.PLANNER);
    }

    public boolean canReadPlan(CurrentUser u) {
        return anyRole(u, Role.ADMIN, Role.PLANNER, Role.INSPECTOR, Role.AUDITOR);
    }

    public boolean canWritePlan(CurrentUser u) {
        return u.isAdmin() || u.hasRole(Role.PLANNER);
    }

    public boolean canReadTask(CurrentUser u) {
        return anyRole(u, Role.ADMIN, Role.PLANNER, Role.INSPECTOR, Role.MAINTAINER, Role.AUDITOR);
    }

    /** 生成任务：管理员、计划员。 */
    public boolean canGenerateTask(CurrentUser u) {
        return u.isAdmin() || u.hasRole(Role.PLANNER);
    }

    /** 开始/执行/跳过/取消巡检任务：管理员或被派巡检员。 */
    public boolean canExecuteTask(CurrentUser u) {
        return u.isAdmin() || u.hasRole(Role.INSPECTOR);
    }

    public boolean canReadWorkOrder(CurrentUser u) {
        return anyRole(u, Role.ADMIN, Role.PLANNER, Role.INSPECTOR, Role.MAINTAINER, Role.AUDITOR);
    }

    /** 创建/派发工单：管理员、计划员（本区域）。巡检异常由执行巡检时自动转单。 */
    public boolean canCreateWorkOrder(CurrentUser u) {
        return u.isAdmin() || u.hasRole(Role.PLANNER);
    }

    /** 流转工单状态：管理员、维修员（处理获派/本区域工单）。计划员只派单不处理。 */
    public boolean canUpdateWorkOrder(CurrentUser u) {
        return u.isAdmin() || u.hasRole(Role.MAINTAINER);
    }

    public boolean canReadStats(CurrentUser u) {
        return anyRole(u, Role.ADMIN, Role.PLANNER, Role.INSPECTOR, Role.MAINTAINER, Role.AUDITOR);
    }

    /** 上报巡检异常：管理员、计划员、巡检员、维修员；审计员只读不可写。 */
    public boolean canReportAbnormality(CurrentUser u) {
        return u.isAdmin() || u.hasRole(Role.PLANNER)
                || u.hasRole(Role.INSPECTOR) || u.hasRole(Role.MAINTAINER);
    }

    /** 复检闭环：管理员、计划员。 */
    public boolean canRecheckAbnormality(CurrentUser u) {
        return u.isAdmin() || u.hasRole(Role.PLANNER);
    }

    /** 漏检/超时检测、系统级操作：仅管理员。 */
    public boolean canRunSystemJob(CurrentUser u) {
        return u.isAdmin();
    }

    private boolean anyRole(CurrentUser u, Role... roles) {
        for (Role r : roles) if (u.hasRole(r)) return true;
        return false;
    }

    // ---------------------------------------------------------------------
    // 数据范围（列表侧）
    // ---------------------------------------------------------------------

    public Scope equipmentScope(CurrentUser u) {
        if (u.isAdmin() || u.hasRole(Role.AUDITOR)) return Scope.all();
        if (u.hasRole(Role.PLANNER) || u.hasRole(Role.MAINTAINER)) return areasOf(u);
        if (u.hasRole(Role.INSPECTOR)) return Scope.ids(new ArrayList<>(equipmentIdsOfTasks(u)));
        return Scope.none();
    }

    public Scope pointScope(CurrentUser u) {
        if (u.isAdmin() || u.hasRole(Role.AUDITOR)) return Scope.all();
        if (u.hasRole(Role.PLANNER) || u.hasRole(Role.MAINTAINER)) return areasOf(u);
        if (u.hasRole(Role.INSPECTOR)) {
            Set<Long> pointIds = new HashSet<>();
            for (Long taskId : accessibleTaskIds(u)) {
                for (InspectionTaskPoint tp : taskPointRepo.findByTaskIdOrderByPlannedSequenceAsc(taskId)) {
                    pointIds.add(tp.getPointId());
                }
            }
            return Scope.ids(new ArrayList<>(pointIds));
        }
        return Scope.none();
    }

    /** 模板是平台级主数据，凡有读权限的角色可见全部模板（执行/计划均需引用）。 */
    public Scope templateScope(CurrentUser u) {
        return canReadTemplate(u) ? Scope.all() : Scope.none();
    }

    public Scope planScope(CurrentUser u) {
        if (u.isAdmin() || u.hasRole(Role.AUDITOR)) return Scope.all();
        if (u.hasRole(Role.PLANNER)) {
            // 计划本身无区域字段：含本区域巡检点的计划可见
            Set<Long> ids = new HashSet<>();
            for (InspectionPlan plan : planRepo.findAllByOrderByCodeAsc()) {
                if (planTouchesAreas(plan, u)) ids.add(plan.getId());
            }
            return Scope.ids(new ArrayList<>(ids));
        }
        if (u.hasRole(Role.INSPECTOR)) {
            Set<Long> planIds = new HashSet<>();
            for (Long taskId : accessibleTaskIds(u)) {
                taskRepo.findById(taskId).ifPresent(t -> planIds.add(t.getPlanId()));
            }
            return Scope.ids(new ArrayList<>(planIds));
        }
        return Scope.none();
    }

    public Scope taskScope(CurrentUser u) {
        if (u.isAdmin() || u.hasRole(Role.AUDITOR)) return Scope.all();
        if (u.hasRole(Role.PLANNER) || u.hasRole(Role.INSPECTOR) || u.hasRole(Role.MAINTAINER)) {
            return Scope.ids(accessibleTaskIds(u));
        }
        return Scope.none();
    }

    public Scope workOrderScope(CurrentUser u) {
        if (u.isAdmin() || u.hasRole(Role.AUDITOR)) return Scope.all();
        if (u.hasRole(Role.PLANNER)) return areasOf(u);
        if (u.hasRole(Role.MAINTAINER)) {
            // 与 canOperateWorkOrder 同一集合：本区域设备工单 ∪ 派给本人/本班组的工单
            List<Long> visible = new ArrayList<>();
            for (WorkOrder w : workOrderRepo.findAllByOrderByIdDesc()) {
                if (canOperateWorkOrder(u, w)) visible.add(w.getId());
            }
            return Scope.ids(visible);
        }
        if (u.hasRole(Role.INSPECTOR)) {
            Set<Long> equipIds = equipmentIdsOfTasks(u);
            Set<Long> taskIds = new HashSet<>(accessibleTaskIds(u));
            Set<Long> fromTask = workOrderIdsFromTasks(taskIds);
            List<Long> visible = new ArrayList<>();
            for (WorkOrder w : workOrderRepo.findAllByOrderByIdDesc()) {
                if (equipIds.contains(w.getEquipmentId()) || fromTask.contains(w.getId())) {
                    visible.add(w.getId());
                }
            }
            return Scope.ids(visible);
        }
        return Scope.none();
    }

    // ---------------------------------------------------------------------
    // 对象级授权（对象侧）—— 存在但无权访问一律 false（控制器返回 403）
    // ---------------------------------------------------------------------

    public boolean canAccessEquipment(CurrentUser u, Long equipmentId) {
        if (equipmentId == null) return false;
        Optional<Equipment> opt = equipmentRepo.findById(equipmentId);
        if (opt.isEmpty()) return false;
        Scope s = equipmentScope(u);
        return switch (s.kind()) {
            case ALL -> true;
            case NONE -> false;
            case AREAS -> s.containsArea(opt.get().getLocation());
            case IDS -> s.containsId(equipmentId);
        };
    }

    public boolean canAccessPoint(CurrentUser u, Long pointId) {
        if (pointId == null) return false;
        Optional<InspectionPoint> opt = pointRepo.findById(pointId);
        if (opt.isEmpty()) return false;
        Scope s = pointScope(u);
        return switch (s.kind()) {
            case ALL -> true;
            case NONE -> false;
            case AREAS -> s.containsArea(opt.get().getLocation());
            case IDS -> s.containsId(pointId);
        };
    }

    public boolean canAccessTemplate(CurrentUser u, Long templateId) {
        return templateId != null && canReadTemplate(u) && templateRepo.existsById(templateId);
    }

    public boolean canAccessPlan(CurrentUser u, Long planId) {
        if (planId == null) return false;
        Optional<InspectionPlan> opt = planRepo.findById(planId);
        if (opt.isEmpty()) return false;
        if (u.isAdmin() || u.hasRole(Role.AUDITOR)) return true;
        if (u.hasRole(Role.PLANNER)) return planTouchesAreas(opt.get(), u);
        if (u.hasRole(Role.INSPECTOR)) {
            return taskScope(u).ids().stream()
                    .anyMatch(tid -> taskRepo.findById(tid)
                            .map(t -> planId.equals(t.getPlanId())).orElse(false));
        }
        return false;
    }

    public boolean canAccessTask(CurrentUser u, Long taskId) {
        if (taskId == null) return false;
        Optional<InspectionTask> opt = taskRepo.findById(taskId);
        return opt.filter(task -> canAccessTaskEntity(u, task)).isPresent();
    }

    public boolean canAccessTaskEntity(CurrentUser u, InspectionTask task) {
        if (task == null) return false;
        if (u.isAdmin() || u.hasRole(Role.AUDITOR)) return true;
        if (u.hasRole(Role.PLANNER)) {
            // 计划员：任务所属计划在其本范围内
            return planRepo.findById(task.getPlanId())
                    .map(p -> planTouchesAreas(p, u)).orElse(false);
        }
        if (u.hasRole(Role.INSPECTOR)) return taskAssignedToUserOrTeam(u, task);
        if (u.hasRole(Role.MAINTAINER)) return taskTouchesAreas(task, u);
        return false;
    }

    /** 巡检员执行任务（开始/执行/跳过/取消）：管理员或本人/本班组被派巡检员。 */
    public boolean canOperateTask(CurrentUser u, InspectionTask task) {
        if (task == null) return false;
        if (u.isAdmin()) return true;
        return u.hasRole(Role.INSPECTOR) && taskAssignedToUserOrTeam(u, task);
    }

    public boolean canAccessWorkOrder(CurrentUser u, Long workOrderId) {
        if (workOrderId == null) return false;
        Optional<WorkOrder> opt = workOrderRepo.findById(workOrderId);
        if (opt.isEmpty()) return false;
        WorkOrder w = opt.get();
        Scope s = workOrderScope(u);
        return switch (s.kind()) {
            case ALL -> true;
            case NONE -> false;
            case AREAS -> equipmentRepo.findById(w.getEquipmentId())
                    .map(e -> s.containsArea(e.getLocation())).orElse(false);
            case IDS -> s.containsId(workOrderId);
        };
    }

    /** 维修员/管理员能否流转某工单状态（功能权限已由 canUpdateWorkOrder 保证）。 */
    public boolean canOperateWorkOrder(CurrentUser u, WorkOrder w) {
        if (w == null) return false;
        if (u.isAdmin()) return true;
        if (u.hasRole(Role.MAINTAINER)) {
            boolean inArea = equipmentRepo.findById(w.getEquipmentId())
                    .map(e -> u.managesArea(e.getLocation())).orElse(false);
            // 获派本人/本班组，或本区域内工单均可接单处理
            return inArea || assignedToUserOrTeam(u, w.getAssignee());
        }
        return false;
    }

    /** 巡检员执行某巡检点：任务可操作且该点属于该任务。 */
    public boolean canOperateTaskPoint(CurrentUser u, Long taskId, Long taskPointId) {
        if (taskId == null || taskPointId == null) return false;
        InspectionTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null || !canOperateTask(u, task)) return false;
        return taskPointRepo.findById(taskPointId)
                .map(tp -> taskId.equals(tp.getTaskId())).orElse(false);
    }

    /** 按巡检点 ID 校验只读可见性（点所属任务可访问即可）。 */
    public boolean canAccessTaskPoint(CurrentUser u, Long taskPointId) {
        if (taskPointId == null) return false;
        return taskPointRepo.findById(taskPointId)
                .map(tp -> canAccessTask(u, tp.getTaskId())).orElse(false);
    }

    /** 异常对象可见性：跨角色审计/管理员全见；其余跟随任务或设备区域。 */
    public boolean canAccessAbnormality(CurrentUser u, InspectionAbnormality ab) {
        if (ab == null) return false;
        if (u.isAdmin() || u.hasRole(Role.AUDITOR)) return true;
        if (u.hasRole(Role.PLANNER)) {
            return (ab.getTaskId() != null && canAccessTask(u, ab.getTaskId()))
                    || (ab.getEquipmentId() != null && canAccessEquipment(u, ab.getEquipmentId()));
        }
        if (u.hasRole(Role.MAINTAINER)) {
            return ab.getEquipmentId() != null && canAccessEquipment(u, ab.getEquipmentId());
        }
        if (u.hasRole(Role.INSPECTOR)) {
            return ab.getTaskId() != null && canAccessTask(u, ab.getTaskId());
        }
        return false;
    }

    /** 巡检记录跟随其任务。 */
    public boolean canAccessRecord(CurrentUser u, InspectionRecord rec) {
        return rec != null && rec.getTaskId() != null && canAccessTask(u, rec.getTaskId());
    }

    /** 计划员为某计划生成任务：计划必须在其本范围内。 */
    public boolean canGenerateForPlan(CurrentUser u, InspectionPlan plan) {
        if (plan == null) return false;
        if (u.isAdmin()) return true;
        return u.hasRole(Role.PLANNER) && planTouchesAreas(plan, u);
    }

    // ---------------------------------------------------------------------
    // 列表过滤（与对象级 canAccess* 同源，保证二者一致）
    // ---------------------------------------------------------------------

    public List<Equipment> visibleEquipment(CurrentUser u) {
        Scope s = equipmentScope(u);
        List<Equipment> all = equipmentRepo.findAll();
        List<Equipment> out = new ArrayList<>();
        for (Equipment e : all) {
            if (matchAreaOrId(s, e.getLocation(), e.getId())) out.add(e);
        }
        return out;
    }

    public List<InspectionPoint> visiblePoints(CurrentUser u) {
        Scope s = pointScope(u);
        List<InspectionPoint> out = new ArrayList<>();
        for (InspectionPoint p : pointRepo.findAllByOrderByCodeAsc()) {
            if (matchAreaOrId(s, p.getLocation(), p.getId())) out.add(p);
        }
        return out;
    }

    public List<InspectionTemplate> visibleTemplates(CurrentUser u) {
        return canReadTemplate(u) ? templateRepo.findAllByOrderByCodeAsc() : new ArrayList<>();
    }

    public List<InspectionPlan> visiblePlans(CurrentUser u) {
        Scope s = planScope(u);
        List<InspectionPlan> out = new ArrayList<>();
        for (InspectionPlan p : planRepo.findAllByOrderByCodeAsc()) {
            if (s.isAll() || s.containsId(p.getId())) out.add(p);
        }
        return out;
    }

    public List<InspectionTask> visibleTasks(CurrentUser u) {
        Scope s = taskScope(u);
        List<InspectionTask> out = new ArrayList<>();
        for (InspectionTask t : taskRepo.findAllByOrderByCreatedAtDesc()) {
            if (s.isAll() || s.containsId(t.getId())) out.add(t);
        }
        return out;
    }

    public List<WorkOrder> visibleWorkOrders(CurrentUser u) {
        Scope s = workOrderScope(u);
        List<WorkOrder> out = new ArrayList<>();
        for (WorkOrder w : workOrderRepo.findAllByOrderByIdDesc()) {
            if (matchesWorkOrderScope(s, w)) out.add(w);
        }
        return out;
    }

    public List<InspectionAbnormality> visibleAbnormalities(CurrentUser u) {
        List<InspectionAbnormality> out = new ArrayList<>();
        for (InspectionAbnormality ab : abnormalityRepo.findAllByOrderByReportedAtDesc()) {
            if (canAccessAbnormality(u, ab)) out.add(ab);
        }
        return out;
    }

    /** 工单是否落在 AREAS 范围（按设备所在区域）或 IDS 范围内。 */
    private boolean matchesWorkOrderScope(Scope s, WorkOrder w) {
        return switch (s.kind()) {
            case ALL -> true;
            case NONE -> false;
            case IDS -> s.containsId(w.getId());
            case AREAS -> equipmentRepo.findById(w.getEquipmentId())
                    .map(e -> s.containsArea(e.getLocation())).orElse(false);
        };
    }

    private boolean matchAreaOrId(Scope s, String location, Long id) {
        return switch (s.kind()) {
            case ALL -> true;
            case NONE -> false;
            case AREAS -> s.containsArea(location);
            case IDS -> s.containsId(id);
        };
    }

    // ---------------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------------

    /** 巡检员/维修员可访问的任务 ID：本人被派 或 本班组（巡检员）；维修员按区域。 */
    private List<Long> accessibleTaskIds(CurrentUser u) {
        List<Long> ids = new ArrayList<>();
        for (InspectionTask t : taskRepo.findAllByOrderByCreatedAtDesc()) {
            if (canAccessTaskEntity(u, t)) ids.add(t.getId());
        }
        return ids;
    }

    private boolean taskAssignedToUserOrTeam(CurrentUser u, InspectionTask t) {
        if (u.id() != null && u.id().equals(t.getAssigneeId())) return true;
        return sameTeam(u.team(), t.getTeamName());
    }

    private boolean sameTeam(String userTeam, String taskTeam) {
        return userTeam != null && !userTeam.isBlank()
                && taskTeam != null && !taskTeam.isBlank()
                && userTeam.equalsIgnoreCase(taskTeam);
    }

    private boolean assignedToUserOrTeam(CurrentUser u, String assignee) {
        if (assignee == null || assignee.isBlank()) return false;
        String a = assignee.trim();
        if (a.equals(String.valueOf(u.id())) || a.equals(u.username())
                || (u.displayName() != null && a.equals(u.displayName()))) {
            return true;
        }
        return sameTeam(u.team(), a);
    }

    private Scope areasOf(CurrentUser u) {
        List<String> areas = u.managedAreas();
        return (areas == null || areas.isEmpty()) ? Scope.none() : Scope.areas(areas);
    }

    private Set<Long> equipmentIdsOfTasks(CurrentUser u) {
        Set<Long> equipIds = new HashSet<>();
        for (Long taskId : accessibleTaskIds(u)) {
            for (InspectionTaskPoint tp : taskPointRepo.findByTaskIdOrderByPlannedSequenceAsc(taskId)) {
                equipIds.addAll(parseIdList(tp.getEquipmentIds()));
            }
        }
        return equipIds;
    }

    private boolean planTouchesAreas(InspectionPlan plan, CurrentUser u) {
        for (InspectionPlanPoint pp : planPointReader.read(plan.getId())) {
            InspectionPoint p = pointRepo.findById(pp.getPointId()).orElse(null);
            if (p != null && u.managesArea(p.getLocation())) return true;
        }
        return false;
    }

    private boolean taskTouchesAreas(InspectionTask task, CurrentUser u) {
        for (InspectionTaskPoint tp : taskPointRepo.findByTaskIdOrderByPlannedSequenceAsc(task.getId())) {
            for (Long eid : parseIdList(tp.getEquipmentIds())) {
                Equipment e = equipmentRepo.findById(eid).orElse(null);
                if (e != null && u.managesArea(e.getLocation())) return true;
            }
            InspectionPoint p = pointRepo.findById(tp.getPointId()).orElse(null);
            if (p != null && u.managesArea(p.getLocation())) return true;
        }
        return false;
    }

    private Set<Long> workOrderIdsFromTasks(Set<Long> taskIds) {
        Set<Long> woIds = new HashSet<>();
        for (Long taskId : taskIds) {
            for (InspectionAbnormality ab : abnormalityRepo.findByTaskIdOrderByReportedAtDesc(taskId)) {
                if (ab.getWorkOrderId() != null) woIds.add(ab.getWorkOrderId());
            }
        }
        return woIds;
    }

    private List<Long> parseIdList(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String p : csv.split(",")) {
            try {
                long v = Long.parseLong(p.trim());
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
