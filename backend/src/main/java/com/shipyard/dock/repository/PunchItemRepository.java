package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shipyard.dock.domain.PunchItem;

public interface PunchItemRepository extends JpaRepository<PunchItem, Long> {

    List<PunchItem> findAllByOrderByIdAsc();

    boolean existsByPunchNo(String punchNo);

    List<PunchItem> findBySurveyIdOrderByIdAsc(Long surveyId);

    List<PunchItem> findByBlockIdOrderByIdAsc(Long blockId);

    /** 某分段尚未闭环的遗留项 */
    List<PunchItem> findByBlockIdAndStatusNotOrderByIdAsc(Long blockId, String status);

    long countByBlockIdAndStatusNot(Long blockId, String status);
}
