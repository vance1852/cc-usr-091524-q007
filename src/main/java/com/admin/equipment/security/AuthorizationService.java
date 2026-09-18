package com.admin.equipment.security;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 集中授权边界：所有“谁能看/操作哪个对象”的判定都收敛在这里，控制器只做声明式调用，
 * 不允许散落 if/role 判断。判定规则只依赖入参实体（纯逻辑），便于单元测试。
 *
 * 约定：
 * - check* 方法在越权时抛出 {@link ForbiddenException}（由全局异常处理统一转 403）；
 * - can* 方法返回布尔，供列表过滤复用，保证“列表过滤”和“对象级 403”同源、结论一致；
 * - ADMIN 全量；AUDITOR 跨区域只读；其余角色按 managedAreas / 班组 / 受派关系收窄。
 */
@Component
public class AuthorizationService {

    private final InspectionPlanRepository planRepo;

    public AuthorizationService(InspectionPlanRepository planRepo) {
        this.planRepo = planRepo;
    }

    // ---------------------------------------------------------------------
    // 基础范围
    // ---------------------------------------------------------------------

    /** 用户可管理区域集合（逗号分隔），空白表示未配置任何区域。 */
    public Set<String> managedAreas(AppUser user) {
        Set<String> set = new HashSet<>();
        if (user.getManagedAreas() != null) {
            for (String part : user.getManagedAreas().split(",")) {
                String a = part.trim();
                if (!a.isEmpty()) set.add(a);
            }
        }
        return set;
    }

    public boolean isAdmin(AppUser user) {
        return user.roleEnum() == Role.ADMIN;
    }

    /** 跨区域可见（管理员或审计员）。 */
    public boolean seesAllAreas(AppUser user) {
        Role r = user.roleEnum();
        return r == Role.ADMIN || r == Role.AUDITOR;
    }

    public boolean areaInScope(AppUser user, String area) {
        if (seesAllAreas(user)) return true;
        return area != null && !area.isBlank() && managedAreas(user).contains(area.trim());
    }

    public boolean teamMatch(AppUser user, String teamName) {
        String mine = user.getTeamName();
        return mine != null && !mine.isBlank() && mine.equals(teamName);
    }

    private boolean assignedToUser(AppUser user, Long assigneeId) {
        return assigneeId != null && assigneeId.equals(user.getId());
    }

    private boolean planMentionsUser(InspectionPlan plan, Long userId) {
        if (plan == null || plan.getAssigneeIds() == null) return false;
        for (String part : plan.getAssigneeIds().split(",")) {
            String s = part.trim();
            if (!s.isEmpty() && s.equals(String.valueOf(userId))) return true;
        }
        return false;
    }

    /** 取任务所属计划区域（任务本身不冗余区域，经计划解析）。 */
    public String areaOfTask(InspectionTask task) {
        if (task == null || task.getPlanId() == null) return "";
        return planRepo.findById(task.getPlanId())
                .map(InspectionPlan::getArea)
                .orElse("");
    }

    private void deny(String message) {
        throw new ForbiddenException(message);
    }

    // ---------------------------------------------------------------------
    // 设备
    // ---------------------------------------------------------------------

    public boolean canReadEquipment(AppUser user, Equipment equipment) {
        if (equipment == null) return false;
        return areaInScope(user, equipment.getArea());
    }

    public void checkCanReadEquipment(AppUser user, Equipment equipment) {
        if (!canReadEquipment(user, equipment)) {
            deny("无权访问该区域的设备");
        }
    }

    public List<Equipment> filterEquipment(AppUser user, Collection<Equipment> all) {
        return all.stream().filter(e -> canReadEquipment(user, e)).toList();
    }

    /** 设备台账维护仅管理员。 */
    public void checkCanManageEquipment(AppUser user) {
        if (!isAdmin(user)) deny("仅管理员可维护设备台账");
    }

    // ---------------------------------------------------------------------
    // 巡检点
    // ---------------------------------------------------------------------

    public boolean canReadPoint(AppUser user, InspectionPoint point) {
        if (point == null) return false;
        return areaInScope(user, point.getArea());
    }

    public void checkCanReadPoint(AppUser user, InspectionPoint point) {
        if (!canReadPoint(user, point)) deny("无权访问该区域的巡检点");
    }

    public List<InspectionPoint> filterPoints(AppUser user, Collection<InspectionPoint> all) {
        return all.stream().filter(p -> canReadPoint(user, p)).toList();
    }

    /** 巡检点维护：管理员不限；计划员限本区域。 */
    public boolean canManagePointArea(AppUser user, String area) {
        if (isAdmin(user)) return true;
        return user.roleEnum() == Role.PLANNER && areaInScope(user, area);
    }

    public void checkCanManagePoint(AppUser user, InspectionPoint existing) {
        if (!canManagePointArea(user, existing.getArea())) {
            deny("无权维护该区域的巡检点");
        }
    }

    public void checkCanManagePointArea(AppUser user, String area) {
        if (!canManagePointArea(user, area)) deny("无权在该区域维护巡检点");
    }

    // ---------------------------------------------------------------------
    // 巡检计划
    // ---------------------------------------------------------------------

    public boolean canReadPlan(AppUser user, InspectionPlan plan) {
        if (plan == null) return false;
        if (seesAllAreas(user)) return true;
        Role r = user.roleEnum();
        if (r == Role.INSPECTOR) {
            // 巡检员：本区域、本班组或计划指名分配
            return areaInScope(user, plan.getArea())
                    || teamMatch(user, plan.getTeamName())
                    || planMentionsUser(plan, user.getId());
        }
        // 计划员/维修员：本区域
        return areaInScope(user, plan.getArea());
    }

    public void checkCanReadPlan(AppUser user, InspectionPlan plan) {
        if (!canReadPlan(user, plan)) deny("无权访问该巡检计划");
    }

    public List<InspectionPlan> filterPlans(AppUser user, Collection<InspectionPlan> all) {
        return all.stream().filter(p -> canReadPlan(user, p)).toList();
    }

    public boolean canManagePlan(AppUser user, InspectionPlan plan) {
        if (isAdmin(user)) return true;
        return user.roleEnum() == Role.PLANNER && areaInScope(user, plan.getArea());
    }

    public void checkCanManagePlan(AppUser user, InspectionPlan plan) {
        if (!canManagePlan(user, plan)) deny("无权维护该巡检计划");
    }

    public void checkCanManagePlanArea(AppUser user, String area) {
        if (!(isAdmin(user) || (user.roleEnum() == Role.PLANNER && areaInScope(user, area)))) {
            deny("无权在该区域维护巡检计划");
        }
    }

    // ---------------------------------------------------------------------
    // 巡检模板：定义类资源，登录用户可读；管理员/计划员可维护
    // ---------------------------------------------------------------------

    public void checkCanManageTemplate(AppUser user) {
        Role r = user.roleEnum();
        if (r != Role.ADMIN && r != Role.PLANNER) {
            deny("仅管理员或计划员可维护巡检模板");
        }
    }

    // ---------------------------------------------------------------------
    // 巡检任务
    // ---------------------------------------------------------------------

    public boolean canReadTask(AppUser user, InspectionTask task) {
        return canReadTask(user, task, areaOfTask(task));
    }

    /** 列表/统计批量场景：由调用方预解析计划区域，避免 N+1。 */
    public boolean canReadTask(AppUser user, InspectionTask task, String planArea) {
        if (task == null) return false;
        if (seesAllAreas(user)) return true;
        Role r = user.roleEnum();
        if (r == Role.INSPECTOR) {
            // 巡检员只能看到分配给自己或本班组的任务
            return assignedToUser(user, task.getAssigneeId()) || teamMatch(user, task.getTeamName());
        }
        if (r == Role.PLANNER || r == Role.MAINTAINER) {
            return areaInScope(user, planArea);
        }
        return false;
    }

    public void checkCanReadTask(AppUser user, InspectionTask task) {
        if (!canReadTask(user, task)) deny("无权访问该巡检任务");
    }

    public List<InspectionTask> filterTasks(AppUser user, Collection<InspectionTask> all) {
        return all.stream().filter(t -> canReadTask(user, t)).toList();
    }

    /** 执行/操作任务：管理员，或被分配/本班组巡检员。 */
    public boolean canExecuteTask(AppUser user, InspectionTask task) {
        if (isAdmin(user)) return true;
        if (user.roleEnum() != Role.INSPECTOR) return false;
        return assignedToUser(user, task.getAssigneeId()) || teamMatch(user, task.getTeamName());
    }

    public void checkCanExecuteTask(AppUser user, InspectionTask task) {
        if (!canExecuteTask(user, task)) deny("该任务未分配给你或所在班组");
    }

    /** 生成任务/派工：管理员或本区域计划员。 */
    public void checkCanGenerateTask(AppUser user, InspectionPlan plan) {
        if (!canManagePlan(user, plan)) deny("无权基于该计划生成巡检任务");
    }

    /** 取消任务等任务管理：管理员或任务所属计划区域的计划员。 */
    public void checkCanManageTask(AppUser user, InspectionTask task) {
        if (isAdmin(user)) return;
        if (user.roleEnum() != Role.PLANNER) deny("无权管理该巡检任务");
        if (!areaInScope(user, areaOfTask(task))) deny("无权管理该巡检任务");
    }

    /** 漏检/超时检测等系统调度操作仅管理员。 */
    public void checkCanRunSystemJob(AppUser user) {
        if (!isAdmin(user)) deny("仅管理员可执行该操作");
    }

    // ---------------------------------------------------------------------
    // 工单
    // ---------------------------------------------------------------------

    public boolean canReadWorkOrder(AppUser user, WorkOrder order) {
        if (order == null) return false;
        if (seesAllAreas(user)) return true;
        Role r = user.roleEnum();
        if (r == Role.MAINTAINER) {
            // 维修员：获派给自己，或本区域
            return assignedToUser(user, order.getAssigneeId()) || areaInScope(user, order.getArea());
        }
        return areaInScope(user, order.getArea());
    }

    public void checkCanReadWorkOrder(AppUser user, WorkOrder order) {
        if (!canReadWorkOrder(user, order)) deny("无权访问该工单");
    }

    public List<WorkOrder> filterWorkOrders(AppUser user, Collection<WorkOrder> all) {
        return all.stream().filter(o -> canReadWorkOrder(user, o)).toList();
    }

    /** 手工建单：管理员或本区域计划员（巡检转工单由系统内部完成，不经此边界）。 */
    public boolean canCreateWorkOrder(AppUser user, Equipment equipment) {
        Role r = user.roleEnum();
        if (r == Role.ADMIN) return true;
        return r == Role.PLANNER && areaInScope(user, equipment.getArea());
    }

    public void checkCanCreateWorkOrder(AppUser user, Equipment equipment) {
        if (!canCreateWorkOrder(user, equipment)) deny("无权在该区域创建工单");
    }

    /** 工单状态流转：管理员，或获派/本区域维修员。 */
    public boolean canTransitionWorkOrder(AppUser user, WorkOrder order) {
        if (isAdmin(user)) return true;
        if (user.roleEnum() != Role.MAINTAINER) return false;
        return assignedToUser(user, order.getAssigneeId()) || areaInScope(user, order.getArea());
    }

    public void checkCanTransitionWorkOrder(AppUser user, WorkOrder order) {
        if (!canTransitionWorkOrder(user, order)) deny("无权处理该工单");
    }

    /** 派工：管理员或本区域计划员。 */
    public void checkCanAssignWorkOrder(AppUser user, WorkOrder order) {
        Role r = user.roleEnum();
        if (r == Role.ADMIN) return;
        if (r == Role.PLANNER && areaInScope(user, order.getArea())) return;
        deny("无权派发该工单");
    }

    // ---------------------------------------------------------------------
    // 异常（闭环事件）
    // ---------------------------------------------------------------------

    /**
     * @param task 异常关联任务（可为 null）
     * @param order 异常转出的工单（可为 null）
     */
    public boolean canReadAbnormality(AppUser user, InspectionAbnormality ab,
                                       InspectionTask task, WorkOrder order) {
        return canReadAbnormality(user, ab, task, order, task == null ? "" : areaOfTask(task));
    }

    /**
     * 批量场景：由调用方预解析任务所属计划区域，避免每个异常再查一次计划。
     *
     * @param taskArea 异常关联任务所属计划的区域（无任务时传 ""）
     */
    public boolean canReadAbnormality(AppUser user, InspectionAbnormality ab,
                                       InspectionTask task, WorkOrder order, String taskArea) {
        if (ab == null) return false;
        if (seesAllAreas(user)) return true;
        Role r = user.roleEnum();
        String area = resolveAbnormalityArea(taskArea, order);
        if (r == Role.MAINTAINER) {
            // 维修员：处理关联工单（获派或本区域），或异常发生在本区域
            if (order != null && (assignedToUser(user, order.getAssigneeId())
                    || areaInScope(user, order.getArea()))) {
                return true;
            }
            return areaInScope(user, area);
        }
        if (r == Role.INSPECTOR) {
            // 巡检员：本人/本班组任务上的异常，或本区域设备异常
            if (task != null && (assignedToUser(user, task.getAssigneeId())
                    || teamMatch(user, task.getTeamName()))) {
                return true;
            }
            return areaInScope(user, area);
        }
        // 计划员：本区域
        return areaInScope(user, area);
    }

    /** 异常区域：优先取关联工单区域，否则回退到任务所属计划区域。 */
    public String resolveAbnormalityArea(String taskArea, WorkOrder order) {
        if (order != null && order.getArea() != null && !order.getArea().isBlank()) {
            return order.getArea();
        }
        return taskArea == null ? "" : taskArea;
    }

    public void checkCanReadAbnormality(AppUser user, InspectionAbnormality ab,
                                         InspectionTask task, WorkOrder order) {
        if (!canReadAbnormality(user, ab, task, order)) deny("无权访问该异常事件");
    }

    public <T> List<T> filterAbnormalities(AppUser user, Collection<T> items,
                                            Function<T, InspectionTask> taskResolver,
                                            Function<T, WorkOrder> orderResolver,
                                            Function<T, InspectionAbnormality> abResolver) {
        return items.stream()
                .filter(x -> canReadAbnormality(user, abResolver.apply(x),
                        taskResolver.apply(x), orderResolver.apply(x)))
                .toList();
    }

    /** 异常复核/闭环：管理员、本区域计划员或处理该工单的维修员。 */
    public void checkCanManageAbnormality(AppUser user, InspectionAbnormality ab,
                                           InspectionTask task, WorkOrder order) {
        Role r = user.roleEnum();
        if (r == Role.ADMIN) return;
        if (r == Role.PLANNER
                && ((order != null && areaInScope(user, order.getArea()))
                    || (task != null && areaInScope(user, areaOfTask(task))))) {
            return;
        }
        if (r == Role.MAINTAINER && order != null
                && (assignedToUser(user, order.getAssigneeId()) || areaInScope(user, order.getArea()))) {
            return;
        }
        deny("无权处理该异常事件");
    }

    // ---------------------------------------------------------------------
    // 人员与授权管理
    // ---------------------------------------------------------------------

    public void checkCanManageUsers(AppUser user) {
        if (!isAdmin(user)) deny("仅管理员可维护人员与授权");
    }
}
