package com.austin.trading.repository;

import com.austin.trading.entity.KolSignalTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KolSignalTraceRepository extends JpaRepository<KolSignalTraceEntity, Long> {
    List<KolSignalTraceEntity> findBySignalIdOrderByCreatedAtAsc(Long signalId);
}
