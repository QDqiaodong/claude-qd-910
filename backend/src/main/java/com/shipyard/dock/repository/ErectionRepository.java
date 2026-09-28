package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shipyard.dock.domain.Erection;

public interface ErectionRepository extends JpaRepository<Erection, Long> {

    List<Erection> findAllByOrderByIdAsc();

    boolean existsByErectionNo(String erectionNo);

    List<Erection> findByVesselIdOrderBySegSeqAsc(Long vesselId);

    Optional<Erection> findFirstByVesselIdOrderBySegSeqDesc(Long vesselId);

    List<Erection> findByBlockIdOrderByIdAsc(Long blockId);

    List<Erection> findByBerthIdOrderBySegSeqAsc(Long berthId);
}
