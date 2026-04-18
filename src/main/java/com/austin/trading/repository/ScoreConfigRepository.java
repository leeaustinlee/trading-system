package com.austin.trading.repository;

import com.austin.trading.entity.ScoreConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScoreConfigRepository extends JpaRepository<ScoreConfigEntity, Long> {

    Optional<ScoreConfigEntity> findByConfigKey(String configKey);

    List<ScoreConfigEntity> findAllByOrderByConfigKeyAsc();
}
