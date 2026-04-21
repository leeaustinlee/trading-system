package com.austin.trading.repository;

import com.austin.trading.entity.BenchmarkAnalyticsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BenchmarkAnalyticsRepository extends JpaRepository<BenchmarkAnalyticsEntity, Long> {

    Optional<BenchmarkAnalyticsEntity> findByStartDateAndEndDate(LocalDate startDate, LocalDate endDate);

    @Query("SELECT b FROM BenchmarkAnalyticsEntity b ORDER BY b.endDate DESC, b.id DESC")
    List<BenchmarkAnalyticsEntity> findAllOrderByEndDateDesc();

    @Query("SELECT b FROM BenchmarkAnalyticsEntity b ORDER BY b.endDate DESC, b.id DESC LIMIT 1")
    Optional<BenchmarkAnalyticsEntity> findLatest();
}
