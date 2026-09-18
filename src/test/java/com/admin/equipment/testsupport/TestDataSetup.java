package com.admin.equipment.testsupport;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.*;
import com.admin.equipment.security.PasswordUtil;
import com.admin.equipment.service.inspection.InspectionTaskService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/** 测试用固定数据：区域、班组、角色、设备、计划、任务、工单。 */
@Component
@Profile("test")
public class TestDataSetup implements CommandLineRunner {

    public final AppUserRepository userRepo;
    public final EquipmentRepository equipmentRepo;
    public final WorkOrderRepository workOrderRepo;
    public final InspectionPointRepository pointRepo;
    public final InspectionTemplateRepository templateRepo;
    public final InspectionTemplateItemRepository itemRepo;
    public final InspectionPlanRepository planRepo;
    public final InspectionPlanPointRepository planPointRepo;
    public final InspectionTaskRepository taskRepo;
    public final InspectionTaskService taskService;

    public TestDataSetup(AppUserRepository userRepo, EquipmentRepository equipmentRepo,
                         WorkOrderRepository workOrderRepo, InspectionPointRepository pointRepo,
                         InspectionTemplateRepository templateRepo,
                         InspectionTemplateItemRepository itemRepo,
                         InspectionPlanRepository planRepo,
                         InspectionPlanPointRepository planPointRepo,
                         InspectionTaskRepository taskRepo,
                         InspectionTaskService taskService) {
        this.userRepo = userRepo;
        this.equipmentRepo = equipmentRepo;
        this.workOrderRepo = workOrderRepo;
        this.pointRepo = pointRepo;
        this.templateRepo = templateRepo;
        this.itemRepo = itemRepo;
        this.planRepo = planRepo;
        this.planPointRepo = planPointRepo;
        this.taskRepo = taskRepo;
        this.taskService = taskService;
    }

    @Override
    public void run(String... args) {
        // 用户
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setPasswordHash(PasswordUtil.hash("admin123"));
        admin.setDisplayName("平台管理员");
        admin.setRole("ADMIN");
        admin.setTeamName("");
        admin.setManagedAreas("");
        admin.setEnabled(true);
        admin.setPermissionVersion(0L);
        userRepo.save(admin);
        AppUser wang = user("wangxj", "王巡检", "INSPECTOR", "甲班巡检组", "");
        AppUser li = user("lixj", "李巡检", "INSPECTOR", "乙班动力组", "");
        AppUser zhang = user("zhangwb", "张维修", "MAINTAINER", "维修一班", "动力站,电机房");
        AppUser zhao = user("zhaogcs", "赵计划", "PLANNER", "计划调度组", "注塑车间A区,注塑车间B区,包装车间");
        user("chenaudit", "陈审计", "AUDITOR", "审计组", "");
        AppUser disabled = user("disabled", "已禁用巡检", "INSPECTOR", "甲班巡检组", "");
        disabled.setEnabled(false);
        userRepo.save(disabled);

        // 设备（分布于各区域）
        Equipment eqA = equip("EQ-A", "注塑机A", "注塑车间A区", "robot", "normal");
        Equipment eqB = equip("EQ-B", "注塑机B", "注塑车间B区", "robot", "normal");
        Equipment eqPump = equip("EQ-P", "空压机", "动力站", "pump", "warning");
        Equipment eqConv = equip("EQ-C", "输送带", "包装车间", "conveyor", "fault");
        Equipment eqMotor = equip("EQ-M", "电机", "电机房", "motor", "normal");

        // 巡检点
        InspectionPoint pA = point("IP-A", "A区点", "注塑车间A区", eqA.getId().toString(), "robot");
        InspectionPoint pB = point("IP-B", "B区点", "注塑车间B区", eqB.getId().toString(), "robot");
        InspectionPoint pPump = point("IP-P", "动力站点", "动力站", eqPump.getId().toString(), "pump");
        InspectionPoint pConv = point("IP-C", "包装点", "包装车间", eqConv.getId().toString(), "conveyor");
        InspectionPoint pMotor = point("IP-M", "电机房点", "电机房", eqMotor.getId().toString(), "motor");

        // 模板
        InspectionTemplate tplRobot = template("TPL-ROBOT", "机器人模板", "robot");
        InspectionTemplate tplPump = template("TPL-PUMP", "泵模板", "pump");
        InspectionTemplate tplConv = template("TPL-CONV", "传送带模板", "conveyor");
        templateItem(tplRobot.getId(), "油位", "option", "正常,偏高,偏低");
        templateItem(tplPump.getId(), "压力", "numeric", null);
        templateItem(tplConv.getId(), "皮带", "option", "合适,过松");

        // 计划：甲班(A区)、乙班(动力站)、周检(包装+电机房)
        InspectionPlan planA = plan("PLAN-A", "甲班注塑计划", tplRobot.getId(), "甲班巡检组", pA);
        InspectionPlan planB = plan("PLAN-B", "乙班动力计划", tplPump.getId(), "乙班动力组", pPump);
        plan("PLAN-C", "周检包装电机计划", tplConv.getId(), "周巡检组", pConv, pMotor);

        // 任务：planA→王，planB→李，planA→班组甲班（无个人指派），planC→无指派（跨班组）
        taskService.generateTask(planA.getId(), wang.getId(), wang.getDisplayName(), false, null);
        taskService.generateTask(planB.getId(), li.getId(), li.getDisplayName(), false, null);
        taskService.generateTask(planA.getId(), null, "", false, null);
        taskService.generateTask(planRepo.findByCode("PLAN-C").orElseThrow().getId(), null, "", false, null);

        // 工单
        workOrder(eqPump.getId(), "动力站派给维修本人", String.valueOf(zhang.getId()), "open");
        workOrder(eqMotor.getId(), "电机房派给维修班组", "维修一班", "open");
        workOrder(eqA.getId(), "注塑A区工单（维修员不可见）", "", "open");
        workOrder(eqConv.getId(), "包装车间工单（计划员可见，维修员不可见）", "", "open");
    }

    private AppUser user(String username, String name, String role, String team, String areas) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(PasswordUtil.hash("123456"));
        u.setDisplayName(name);
        u.setRole(role);
        u.setTeamName(team);
        u.setManagedAreas(areas);
        u.setEnabled(true);
        u.setPermissionVersion(0L);
        return userRepo.save(u);
    }

    private Equipment equip(String code, String name, String loc, String type, String status) {
        Equipment e = new Equipment();
        e.setCode(code);
        e.setName(name);
        e.setLocation(loc);
        e.setType(type);
        e.setStatus(status);
        return equipmentRepo.save(e);
    }

    private InspectionPoint point(String code, String name, String loc, String equipIds, String type) {
        InspectionPoint p = new InspectionPoint();
        p.setCode(code);
        p.setName(name);
        p.setLocation(loc);
        p.setCoordX(10.0);
        p.setCoordY(10.0);
        p.setEquipmentIds(equipIds);
        p.setEquipmentType(type);
        return pointRepo.save(p);
    }

    private InspectionTemplate template(String code, String name, String type) {
        InspectionTemplate t = new InspectionTemplate();
        t.setCode(code);
        t.setName(name);
        t.setEquipmentType(type);
        return templateRepo.save(t);
    }

    private void templateItem(Long tplId, String name, String type, String options) {
        InspectionTemplateItem i = new InspectionTemplateItem();
        i.setTemplateId(tplId);
        i.setName(name);
        i.setType(type);
        i.setSortOrder(1);
        i.setQualifiedOptions(options == null ? "" : options);
        i.setNormalMin(null);
        i.setNormalMax(null);
        i.setJudgeCriteria("");
        itemRepo.save(i);
    }

    private InspectionPlan plan(String code, String name, Long tplId, String team, InspectionPoint... pts) {
        InspectionPlan p = new InspectionPlan();
        p.setCode(code);
        p.setName(name);
        p.setTemplateId(tplId);
        p.setTeamName(team);
        p.setStartTime("08:00");
        p.setEndTime("18:00");
        p.setTimeWindowMinutes(600);
        p.setCycleType("daily");
        p.setCycleValue(1);
        p.setShiftType("day");
        p.setAssigneeIds("");
        p.setRemark("");
        p.setEnabled(true);
        InspectionPlan saved = planRepo.save(p);
        int seq = 1;
        for (InspectionPoint pt : pts) {
            InspectionPlanPoint pp = new InspectionPlanPoint();
            pp.setPlanId(saved.getId());
            pp.setPointId(pt.getId());
            pp.setSequenceNo(seq++);
            planPointRepo.save(pp);
        }
        return saved;
    }

    private void workOrder(Long equipId, String title, String assignee, String status) {
        WorkOrder w = new WorkOrder();
        w.setEquipmentId(equipId);
        w.setTitle(title);
        w.setType("repair");
        w.setPriority("medium");
        w.setDescription("");
        w.setAssignee(assignee);
        w.setStatus(status);
        workOrderRepo.save(w);
    }
}
