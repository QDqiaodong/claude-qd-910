package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.VesselTypeStatDTO;

public interface VesselRepository extends JpaRepository<Vessel, Long> {

    List<Vessel> findAllByOrderByIdAsc();

    boolean existsByVesselNo(String vesselNo);

    boolean existsByRegNo(String regNo);

    List<Vessel> findByOwnerIdOrderByIdAsc(Long ownerId);

    /**
     * 手写 JPQL ⑥：按船舶类型统计条数、平均船长与排水量合计。
     */
    @Query("select new com.shipyard.dock.dto.VesselTypeStatDTO("
            + "v.vesselType, count(v), avg(v.lengthM), sum(v.displacementT)) "
            + "from Vessel v group by v.vesselType order by count(v) desc")
    List<VesselTypeStatDTO> statByVesselType();
}
