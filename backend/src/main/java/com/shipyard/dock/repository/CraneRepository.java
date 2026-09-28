package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.shipyard.dock.domain.Crane;

public interface CraneRepository extends JpaRepository<Crane, Long> {

    List<Crane> findAllByOrderByIdAsc();

    boolean existsByCraneNo(String craneNo);

    @Query("select c from Crane c where c.status = 'AVAILABLE' order by c.ratedLoadT desc")
    List<Crane> findAvailable();
}
