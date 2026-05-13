package com.austin.trading.repository;

import com.austin.trading.entity.KolSourceProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KolSourceProfileRepository extends JpaRepository<KolSourceProfileEntity, Long> {
    Optional<KolSourceProfileEntity> findBySourceKey(String sourceKey);
}
