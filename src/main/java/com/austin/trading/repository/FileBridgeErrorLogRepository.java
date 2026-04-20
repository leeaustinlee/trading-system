package com.austin.trading.repository;

import com.austin.trading.entity.FileBridgeErrorLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileBridgeErrorLogRepository extends JpaRepository<FileBridgeErrorLogEntity, Long> {

    List<FileBridgeErrorLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByFileHash(String fileHash);
}
