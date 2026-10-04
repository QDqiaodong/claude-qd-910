package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.shipyard.dock.domain.LaunchRelease;

import jakarta.persistence.LockModeType;

public interface LaunchReleaseRepository extends JpaRepository<LaunchRelease, Long> {

    List<LaunchRelease> findAllByOrderByIdDesc();

    List<LaunchRelease> findByVesselIdOrderByIdDesc(Long vesselId);

    Optional<LaunchRelease> findFirstByVesselIdAndStatusOrderByIdDesc(Long vesselId, String status);

    boolean existsByReleaseNo(String releaseNo);

    /** 确认 / 作废 / 重开走行锁：并发操作同一放行单时串行，只放行一个结果 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LaunchRelease r where r.id = :id")
    Optional<LaunchRelease> findByIdForUpdate(@Param("id") Long id);
}
