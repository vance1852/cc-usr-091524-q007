package com.admin.equipment.service.inspection;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.*;
import com.admin.equipment.security.AuthorizationService;
import com.admin.equipment.security.ScopeService;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * 巡检统计：所有指标都在“当前用户可见范围”内计算，可见性判定与列表/对象接口同源
 * （{@link ScopeService} / {@link AuthorizationService}），
 * 保证不同角色看到的设备、工单、任务、统计口径一致。
 */
@Service
public class InspectionStatsService {

    private final InspectionTaskRepository taskRepo;
    private final InspectionTaskPointRepository taskPointRepo;
    private final InspectionRecordRepository recordRepo;
    private final InspectionAbnormalityRepository abnormalityRepo;
    private final InspectionPlanRepository planRepo;
    private final InspectionPointRepository pointRepo;
    private final EquipmentRepository equipmentRepo;
    private final WorkOrderRepository workOrderRepo;
    private final ScopeService scope;
    private final AuthorizationService authz;

    public InspectionStatsService(InspectionTaskRepository taskRepo,
                                  InspectionTaskPointRepository taskPointRepo,
                                  InspectionRecordRepository recordRepo,
                                  InspectionAbnormalityRepository abnormalityRepo,
                                  InspectionPlanRepository planRepo,
                                  InspectionPointRepository pointRepo,
                                  EquipmentRepository equipmentRepo,
                                  WorkOrderRepository workOrderRepo,
                                  ScopeService scope,
                                  AuthorizationService authz) {
        this.taskRepo = taskRepo;
        this.taskPointRepo = taskPointRepo;
        this.recordRepo = recordRepo;
        this.abnormalityRepo = abnormalityRepo;
        this.planRepo = planRepo;
        this.pointRepo = pointRepo;
        this.equipmentRepo = equipmentRepo;
        this.workOrderRepo = workOrderRepo;
        this.scope = scope;
        this.authz = authz;
    }

    public record OverallStats(long totalPlans, long totalTasks, long completedTasks, long inProgressTasks,
                                long pendingTasks, long cancelledTasks, long totalPoints,
                                long completedPoints, long missedPoints, long totalAbnormalities,
                                long woCreatedAbnormalities, long closedLoopAbnormalities,
                                double completionRate, double abnormalRate, double woConversionRate) {}

    public OverallStats getOverallStats(AppUser user) {
        long totalPlans = scope.visiblePlans(user, false).size();
        List<InspectionTask> allTasks = scope.visibleTasks(user);
        long totalTasks = allTasks.size();
        long completedTasks = 0, inProgressTasks = 0, pendingTasks = 0, cancelledTasks = 0;
        long totalPoints = 0, completedPoints = 0, missedPoints = 0;
        for (InspectionTask t : allTasks) {
            switch (t.getStatus() == null ? "" : t.getStatus()) {
                case "completed" -> completedTasks++;
                case "in_progress" -> inProgressTasks++;
                case "pending" -> pendingTasks++;
                case "cancelled" -> cancelledTasks++;
            }
            totalPoints += t.getTotalPoints() == null ? 0 : t.getTotalPoints();
            completedPoints += t.getCompletedPoints() == null ? 0 : t.getCompletedPoints();
            missedPoints += t.getMissedPoints() == null ? 0 : t.getMissedPoints();
        }
        List<InspectionAbnormality> allAb = scope.visibleAbnormalities(user);
        long totalAb = allAb.size();
        long woCreated = 0, closedLoop = 0;
        for (InspectionAbnormality ab : allAb) {
            if (Boolean.TRUE.equals(ab.getWorkOrderCreated())) woCreated++;
            if (Boolean.TRUE.equals(ab.getClosedLoop())) closedLoop++;
        }
        double completionRate = totalPoints > 0 ? (completedPoints * 100.0 / totalPoints) : 0;
        double abnormalRate = totalTasks > 0 ? (totalAb * 100.0 / totalTasks) : 0;
        double woConv = totalAb > 0 ? (woCreated * 100.0 / totalAb) : 0;
        return new OverallStats(totalPlans, totalTasks, completedTasks, inProgressTasks, pendingTasks,
                cancelledTasks, totalPoints, completedPoints, missedPoints, totalAb, woCreated,
                closedLoop, completionRate, abnormalRate, woConv);
    }

    public record DateStats(LocalDate date, long taskCount, long completedCount, long abnormalCount,
                             double completionRate, double abnormalRate) {}

    public List<DateStats> getDateRangeStats(AppUser user, LocalDate startDate, LocalDate endDate) {
        List<InspectionTask> visible = scope.visibleTasks(user);
        Map<LocalDate, List<InspectionTask>> byDate = new TreeMap<>();
        for (InspectionTask t : visible) {
            LocalDateTime sched = t.getScheduledStart();
            LocalDate d = sched != null ? sched.toLocalDate()
                    : (t.getCreatedAt() != null ? t.getCreatedAt().toLocalDate() : LocalDate.now());
            if (!d.isBefore(startDate) && !d.isAfter(endDate)) {
                byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(t);
            }
        }
        List<DateStats> result = new ArrayList<>();
        for (Map.Entry<LocalDate, List<InspectionTask>> e : byDate.entrySet()) {
            List<InspectionTask> list = e.getValue();
            long cnt = list.size();
            long comp = 0, ab = 0;
            long totalPts = 0, compPts = 0;
            for (InspectionTask t : list) {
                if ("completed".equals(t.getStatus())) comp++;
                ab += (t.getAbnormalCount() == null ? 0 : t.getAbnormalCount());
                totalPts += (t.getTotalPoints() == null ? 0 : t.getTotalPoints());
                compPts += (t.getCompletedPoints() == null ? 0 : t.getCompletedPoints());
            }
            double cr = totalPts > 0 ? (compPts * 100.0 / totalPts) : 0;
            double ar = cnt > 0 ? (ab * 100.0 / cnt) : 0;
            result.add(new DateStats(e.getKey(), cnt, comp, ab, cr, ar));
        }
        return result;
    }

    public record EquipmentInspectionHistory(Long equipmentId, String equipmentCode, String equipmentName,
                                              long inspectionCount, long abnormalCount,
                                              double abnormalRate, List<AbnormalitySummary> abnormalities) {}

    public record AbnormalitySummary(Long abnormalityId, Long taskId, Long workOrderId,
                                      String itemName, String title, String description,
                                      String severity, String status, LocalDateTime reportedAt,
                                      String recheckResult, LocalDateTime recheckAt) {}

    public EquipmentInspectionHistory getEquipmentHistory(AppUser user, Long equipmentId) {
        Equipment eq = equipmentRepo.findById(equipmentId).orElse(null);
        Set<Long> visibleTaskIds = visibleTaskIdSet(user);
        // 仅统计该设备上、且在当前用户可见任务范围内的异常
        List<InspectionAbnormality> abs = abnormalityRepo.findByEquipmentIdOrderByReportedAtDesc(equipmentId)
                .stream()
                .filter(ab -> ab.getTaskId() == null || visibleTaskIds.contains(ab.getTaskId()))
                .filter(ab -> authz.seesAllAreas(user) || areaOfEquipmentInScope(user, equipmentId, ab))
                .toList();
        long inspCount = 0;
        for (InspectionTask t : taskRepo.findAll()) {
            if (!visibleTaskIds.contains(t.getId())) continue;
            for (InspectionTaskPoint tp : taskPointRepo.findByTaskIdOrderByPlannedSequenceAsc(t.getId())) {
                if (tp.getEquipmentIds() != null && tp.getEquipmentIds().contains(String.valueOf(equipmentId))) {
                    if ("completed".equals(tp.getStatus())) inspCount++;
                }
            }
        }
        List<AbnormalitySummary> summaries = new ArrayList<>();
        for (InspectionAbnormality ab : abs) {
            summaries.add(new AbnormalitySummary(ab.getId(), ab.getTaskId(), ab.getWorkOrderId(),
                    ab.getItemName(), ab.getTitle(), ab.getDescription(), ab.getSeverity(),
                    ab.getStatus(), ab.getReportedAt(), ab.getRecheckResult(), ab.getRecheckAt()));
        }
        double ar = inspCount > 0 ? (abs.size() * 100.0 / inspCount) : 0;
        return new EquipmentInspectionHistory(equipmentId,
                eq != null ? eq.getCode() : "",
                eq != null ? eq.getName() : "",
                inspCount, abs.size(), ar, summaries);
    }

    /** 设备级异常的区域兜底判定：设备在范围内，或关联工单在范围内。 */
    private boolean areaOfEquipmentInScope(AppUser user, Long equipmentId, InspectionAbnormality ab) {
        Equipment eq = equipmentRepo.findById(equipmentId).orElse(null);
        if (eq != null && authz.areaInScope(user, eq.getArea())) return true;
        if (ab.getWorkOrderId() != null) {
            WorkOrder wo = workOrderRepo.findById(ab.getWorkOrderId()).orElse(null);
            if (wo != null && authz.areaInScope(user, wo.getArea())) return true;
        }
        return false;
    }

    private Set<Long> visibleTaskIdSet(AppUser user) {
        Set<Long> ids = new HashSet<>();
        for (InspectionTask t : scope.visibleTasks(user)) ids.add(t.getId());
        return ids;
    }

    public record ClosedLoopTrace(Long abnormalityId, Long taskId, String taskCode, Long pointId, String pointName,
                                   Long equipmentId, String equipmentCode, String equipmentName,
                                   String title, String description, String severity,
                                   String abStatus, LocalDateTime reportedAt,
                                   Long workOrderId, String workOrderType, String workOrderStatus,
                                   String workOrderPriority, String workOrderAssignee,
                                   LocalDateTime workOrderCreated, LocalDateTime workOrderClosed,
                                   String recheckResult, LocalDateTime recheckAt, String recheckBy,
                                   boolean closedLoop, LocalDateTime resolvedAt) {}

    public List<ClosedLoopTrace> getClosedLoopTraces(AppUser user, String statusFilter) {
        List<InspectionAbnormality> scoped = scope.visibleAbnormalities(user);
        List<InspectionAbnormality> abs = new ArrayList<>();
        for (InspectionAbnormality ab : scoped) {
            if (statusFilter == null || statusFilter.isBlank() || "all".equals(statusFilter)
                    || statusFilter.equals(ab.getStatus())) {
                abs.add(ab);
            }
        }
        Map<Long, String> taskCodeMap = new HashMap<>();
        Map<Long, String> pointNameMap = new HashMap<>();
        for (InspectionTask t : taskRepo.findAll()) {
            taskCodeMap.put(t.getId(), t.getCode());
        }
        for (InspectionPoint p : pointRepo.findAll()) {
            pointNameMap.put(p.getId(), p.getName());
        }
        List<ClosedLoopTrace> result = new ArrayList<>();
        for (InspectionAbnormality ab : abs) {
            WorkOrder wo = ab.getWorkOrderId() != null ? workOrderRepo.findById(ab.getWorkOrderId()).orElse(null) : null;
            result.add(new ClosedLoopTrace(
                    ab.getId(), ab.getTaskId(),
                    taskCodeMap.getOrDefault(ab.getTaskId(), ""),
                    ab.getPointId(),
                    pointNameMap.getOrDefault(ab.getPointId(), ""),
                    ab.getEquipmentId(), ab.getEquipmentCode(), ab.getEquipmentName(),
                    ab.getTitle(), ab.getDescription(), ab.getSeverity(), ab.getStatus(), ab.getReportedAt(),
                    ab.getWorkOrderId(),
                    wo != null ? wo.getType() : "",
                    wo != null ? wo.getStatus() : "",
                    wo != null ? wo.getPriority() : "",
                    wo != null ? wo.getAssignee() : "",
                    wo != null ? wo.getCreatedAt() : null,
                    wo != null ? wo.getClosedAt() : null,
                    ab.getRecheckResult(), ab.getRecheckAt(), ab.getRecheckBy(),
                    Boolean.TRUE.equals(ab.getClosedLoop()), ab.getResolvedAt()
            ));
        }
        return result;
    }

    public record TaskCompletionStats(Long taskId, String taskCode, Long planId, String status,
                                       int totalPoints, int completedPoints, int missedPoints,
                                       double completionRate,
                                       int abnormalCount, double abnormalRate,
                                       LocalDateTime scheduledStart, LocalDateTime actualStart,
                                       LocalDateTime scheduledEnd, LocalDateTime actualEnd,
                                       Long durationSeconds, boolean timeout) {}

    public List<TaskCompletionStats> getTaskCompletionStats(AppUser user, LocalDate startDate, LocalDate endDate) {
        List<InspectionTask> visible = scope.visibleTasks(user);
        List<TaskCompletionStats> result = new ArrayList<>();
        for (InspectionTask t : visible) {
            LocalDateTime sched = t.getScheduledStart();
            LocalDate d = sched != null ? sched.toLocalDate()
                    : (t.getCreatedAt() != null ? t.getCreatedAt().toLocalDate() : null);
            if (d == null || d.isBefore(startDate) || d.isAfter(endDate)) continue;
            int total = t.getTotalPoints() == null ? 0 : t.getTotalPoints();
            int comp = t.getCompletedPoints() == null ? 0 : t.getCompletedPoints();
            int missed = t.getMissedPoints() == null ? 0 : t.getMissedPoints();
            int abn = t.getAbnormalCount() == null ? 0 : t.getAbnormalCount();
            double cr = total > 0 ? (comp * 100.0 / total) : 0;
            double ar = total > 0 ? (abn * 100.0 / total) : 0;
            Long dur = null;
            if (t.getActualStart() != null && t.getActualEnd() != null) {
                dur = Duration.between(t.getActualStart(), t.getActualEnd()).getSeconds();
            }
            boolean timeout = false;
            if (t.getScheduledEnd() != null && t.getActualEnd() != null) {
                timeout = t.getActualEnd().isAfter(t.getScheduledEnd());
            } else if (t.getScheduledEnd() != null && LocalDateTime.now().isAfter(t.getScheduledEnd())
                    && !"completed".equals(t.getStatus())) {
                timeout = true;
            }
            result.add(new TaskCompletionStats(t.getId(), t.getCode(), t.getPlanId(),
                    t.getStatus(), total, comp, missed, cr, abn, ar,
                    t.getScheduledStart(), t.getActualStart(),
                    t.getScheduledEnd(), t.getActualEnd(), dur, timeout));
        }
        return result;
    }

    public record ExecutionTrace(Long taskId, String taskCode, List<TracePoint> points,
                                  long totalDurationSeconds) {
    }

    public record TracePoint(Long taskPointId, Long pointId, String pointCode, String pointName,
                              int plannedSequence, Integer actualSequence,
                              LocalDateTime arrivedAt, LocalDateTime leftAt, Long durationSeconds,
                              String status, boolean missed, int itemCount,
                              int qualifiedCount, int abnormalCount, String remark) {}

    public InspectionTask findTaskRaw(Long taskId) {
        return taskRepo.findById(taskId).orElse(null);
    }

    public ExecutionTrace getExecutionTrace(Long taskId) {
        InspectionTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null) return new ExecutionTrace(taskId, "", new ArrayList<>(), 0L);
        List<InspectionTaskPoint> tps = taskPointRepo.findByTaskIdOrderByPlannedSequenceAsc(taskId);
        List<TracePoint> result = new ArrayList<>();
        for (InspectionTaskPoint tp : tps) {
            result.add(new TracePoint(tp.getId(), tp.getPointId(), tp.getPointCode(), tp.getPointName(),
                    tp.getPlannedSequence() == null ? 0 : tp.getPlannedSequence(), tp.getActualSequence(),
                    tp.getArrivedAt(), tp.getLeftAt(), tp.getDurationSeconds(),
                    tp.getStatus(), Boolean.TRUE.equals(tp.getIsMissed()),
                    tp.getItemCount() == null ? 0 : tp.getItemCount(),
                    tp.getQualifiedCount() == null ? 0 : tp.getQualifiedCount(),
                    tp.getAbnormalCount() == null ? 0 : tp.getAbnormalCount(),
                    tp.getRemark()));
        }
        long totalDur = 0;
        for (TracePoint tp : result) {
            if (tp.durationSeconds != null) totalDur += tp.durationSeconds();
        }
        return new ExecutionTrace(taskId, task.getCode(), result, totalDur);
    }
}
