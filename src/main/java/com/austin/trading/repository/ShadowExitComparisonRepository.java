package com.austin.trading.repository;

import com.austin.trading.entity.ShadowExitComparisonEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShadowExitComparisonRepository extends JpaRepository<ShadowExitComparisonEntity, Long> {
}
