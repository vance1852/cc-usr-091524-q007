package com.admin.equipment.security;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 授权边界纯逻辑测试：不启 Spring，仅验证 AuthorizationService 谓词。
 * 重点：列表过滤与对象级判定同源、跨班组/跨区域 ID 猜测必被拒。
 */
class AuthorizationServiceTest {

    private AuthorizationService authz;
    private InspectionPlanRepository planRepo;

    private AppUser user(long id, Role role, String team, String areas) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("u" + id);
        u.setRole(role.name());
        u.setTeamName(team);
        u.setManagedAreas(areas);
        u.setEnabled(true);
        u.setPermissionVersion(1);
        return u;
    }

    private Equipment equipment(long id, String area) {
        Equipment e = new Equipment();
        e.setId(id);
        e.setArea(area);
        return e;
    }

    private InspectionPlan plan(long id, String area, String team, String assigneeIds) {
        InspectionPlan p = new InspectionPlan();
        p.setId(id);
        p.setArea(area);
        p.setTeamName(team);
        p.setAssigneeIds(assigneeIds);
        return p;
    }

    private InspectionTask task(long id, long planId, Long assigneeId, String team) {
        InspectionTask t = new InspectionTask();
        t.setId(id);
        t.setPlanId(planId);
        t.setAssigneeId(assigneeId);
        t.setTeamName(team);
        return t;
    }

    @BeforeEach
    void setUp() {
        planRepo = mock(InspectionPlanRepository.class);
        when(planRepo.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.ofNullable(planStore.get(id));
        });
        authz = new AuthorizationService(planRepo);
    }

    private final java.util.Map<Long, InspectionPlan> planStore = new java.util.HashMap<>();

    @Test
    void equipmentScopeByArea() {
        AppUser inspector = user(2, Role.INSPECTOR, "甲班巡检组", "注塑车间A区");
        Equipment inArea = equipment(1, "注塑车间A区");
        Equipment outArea = equipment(2, "动力站");

        assertTrue(authz.canReadEquipment(inspector, inArea));
        assertFalse(authz.canReadEquipment(inspector, outArea));
        assertThrows(ForbiddenException.class, () -> authz.checkCanReadEquipment(inspector, outArea));

        // 列表过滤与对象级判定一致
        List<Equipment> filtered = authz.filterEquipment(inspector, List.of(inArea, outArea));
        assertEquals(1, filtered.size());
        assertEquals(1L, filtered.get(0).getId());
    }

    @Test
    void adminAndAuditorSeeAllAreas() {
        AppUser admin = user(1, Role.ADMIN, "", "");
        AppUser auditor = user(7, Role.AUDITOR, "审计组", "");
        Equipment e = equipment(9, "任意区域");
        assertTrue(authz.canReadEquipment(admin, e));
        assertTrue(authz.canReadEquipment(auditor, e));
    }

    @Test
    void inspectorSeesOnlyAssignedOrOwnTeamTasks() {
        planStore.put(10L, plan(10, "动力站", "乙班动力组", "3"));
        planStore.put(11L, plan(11, "注塑车间A区", "甲班巡检组", "2"));

        AppUser wang = user(2, Role.INSPECTOR, "甲班巡检组", "注塑车间A区");

        InspectionTask assignedToOther = task(100, 10, 3L, "乙班动力组"); // 动力站、别的班组
        InspectionTask ownTeam = task(101, 11, 99L, "甲班巡检组");        // 指派人不是他但同班组
        InspectionTask assignedToSelf = task(102, 10, 2L, "乙班动力组"); // 跨班组但指名分配给他

        assertFalse(authz.canReadTask(wang, assignedToOther, "动力站"));
        assertTrue(authz.canReadTask(wang, ownTeam, "注塑车间A区"));
        assertTrue(authz.canReadTask(wang, assignedToSelf, "动力站"));

        assertThrows(ForbiddenException.class,
                () -> authz.checkCanReadTask(wang, assignedToOther));
        // 越权猜测任务也不能执行
        assertThrows(ForbiddenException.class,
                () -> authz.checkCanExecuteTask(wang, assignedToOther));
        // 同班组可执行
        assertDoesNotThrow(() -> authz.checkCanExecuteTask(wang, ownTeam));
    }

    @Test
    void maintainerWorkOrderAssignedOrArea() {
        AppUser zhang = user(4, Role.MAINTAINER, "维修一班", "动力站,包装车间");
        WorkOrder inArea = order(1, "动力站", null);
        WorkOrder assignedToMe = order(2, "电机房", 4L);   // 区域不在范围，但派给了他
        WorkOrder foreign = order(3, "注塑车间A区", null);

        assertTrue(authz.canReadWorkOrder(zhang, inArea));
        assertTrue(authz.canReadWorkOrder(zhang, assignedToMe));
        assertFalse(authz.canReadWorkOrder(zhang, foreign));
        assertTrue(authz.canTransitionWorkOrder(zhang, assignedToMe));
        assertThrows(ForbiddenException.class,
                () -> authz.checkCanTransitionWorkOrder(zhang, foreign));
    }

    @Test
    void inspectorCannotTransitionWorkOrderButCanReadOwnArea() {
        AppUser wang = user(2, Role.INSPECTOR, "甲班巡检组", "注塑车间A区");
        WorkOrder wo = order(1, "注塑车间A区", null);
        assertTrue(authz.canReadWorkOrder(wang, wo));
        assertFalse(authz.canTransitionWorkOrder(wang, wo));
    }

    @Test
    void plannerManagesOnlyOwnAreaPlansAndPoints() {
        AppUser sun = user(6, Role.PLANNER, "计划调度组", "注塑车间A区,注塑车间B区");
        InspectionPlan own = plan(1, "注塑车间A区", "甲班巡检组", "2");
        InspectionPlan other = plan(2, "动力站", "乙班动力组", "3");

        assertTrue(authz.canManagePlan(sun, own));
        assertFalse(authz.canManagePlan(sun, other));
        assertDoesNotThrow(() -> authz.checkCanManagePlanArea(sun, "注塑车间B区"));
        assertThrows(ForbiddenException.class,
                () -> authz.checkCanManagePlanArea(sun, "动力站"));
        assertThrows(ForbiddenException.class,
                () -> authz.checkCanManagePointArea(sun, "电机房"));
    }

    @Test
    void templateAndUserManagementRoleGate() {
        AppUser inspector = user(2, Role.INSPECTOR, "甲班巡检组", "注塑车间A区");
        AppUser planner = user(6, Role.PLANNER, "计划调度组", "注塑车间A区");
        assertThrows(ForbiddenException.class, () -> authz.checkCanManageTemplate(inspector));
        assertDoesNotThrow(() -> authz.checkCanManageTemplate(planner));
        assertThrows(ForbiddenException.class, () -> authz.checkCanManageUsers(planner));
        assertDoesNotThrow(() -> authz.checkCanManageUsers(user(1, Role.ADMIN, "", "")));
    }

    @Test
    void auditorReadOnlyAcrossAllAreas() {
        AppUser auditor = user(7, Role.AUDITOR, "审计组", "");
        InspectionTask t = task(100, 10, 3L, "乙班动力组");
        assertTrue(authz.canReadTask(auditor, t, "动力站"));
        // 审计员不能执行任务
        assertThrows(ForbiddenException.class, () -> authz.checkCanExecuteTask(auditor, t));
    }

    private WorkOrder order(long id, String area, Long assigneeId) {        WorkOrder w = new WorkOrder();
        w.setId(id);
        w.setArea(area);
        w.setAssigneeId(assigneeId);
        return w;
    }
}
