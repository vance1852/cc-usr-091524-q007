package com.admin.equipment.repo;

import com.admin.equipment.model.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {
    List<WorkOrder> findAllByOrderByIdDesc();
    List<WorkOrder> findByEquipmentIdOrderByIdDesc(Long equipmentId);
    List<WorkOrder> findByStatusOrderByIdDesc(String status);
    List<WorkOrder> findByAreaOrderByIdDesc(String area);
    List<WorkOrder> findByAssigneeIdOrderByIdDesc(Long assigneeId);
    List<WorkOrder> findByAreaAndStatusOrderByIdDesc(String area, String status);
    long countByStatus(String status);
    long countByArea(String area);
}
