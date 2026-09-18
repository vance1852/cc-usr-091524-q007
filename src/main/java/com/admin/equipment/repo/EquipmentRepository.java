package com.admin.equipment.repo;

import com.admin.equipment.model.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {
    boolean existsByCode(String code);
    long countByStatus(String status);
    List<Equipment> findAllByOrderByIdAsc();
    List<Equipment> findByAreaOrderByIdAsc(String area);
    long countByArea(String area);
    long countByAreaAndStatus(String area, String status);
}
