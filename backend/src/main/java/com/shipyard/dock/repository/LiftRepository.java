package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.shipyard.dock.domain.Lift;
import com.shipyard.dock.dto.LiftLoadStatDTO;

public interface LiftRepository extends JpaRepository<Lift, Long> {

    List<Lift> findAllByOrderByLiftTimeDesc();

    boolean existsByLiftNo(String liftNo);

    List<Lift> findByErectionIdOrderByLiftTimeAsc(Long erectionId);

    List<Lift> findByBlockIdOrderByLiftTimeAsc(Long blockId);

    List<Lift> findByCraneIdOrderByLiftTimeAsc(Long craneId);

    /**
     * 手写 JPQL ③：按起重设备类型统计吊次数、吊装总吨位与平均载荷率。
     */
    @Query("select new com.shipyard.dock.dto.LiftLoadStatDTO("
            + "c.craneType, count(l), sum(l.liftWeightT), avg(l.liftWeightT * 100.0 / c.ratedLoadT)) "
            + "from Lift l, Crane c where l.craneId = c.id "
            + "group by c.craneType order by count(l) desc")
    List<LiftLoadStatDTO> statByCraneType();
}
