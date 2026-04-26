package com.austin.trading.service;

import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.engine.StopLossTakeProfitEngine;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.PositionReviewLogEntity;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.PositionReviewLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v2.14：驗證 PositionResponse 帶出最近一筆 position_review_log 的 reviewStatus / reviewedAt /
 * reviewReason；無 review log 時 fallback 為 null。
 */
class PositionServiceReviewStatusTests {

    private PositionRepository positionRepo;
    private PositionReviewLogRepository reviewLogRepo;
    private PositionService service;

    @BeforeEach
    void setUp() {
        positionRepo = mock(PositionRepository.class);
        reviewLogRepo = mock(PositionReviewLogRepository.class);
        // 其他 dependency 可以塞 mock，PositionService 在 toResponse 路徑只用到 reviewLogRepo
        service = new PositionService(
                positionRepo,
                mock(PnlService.class),
                mock(TradeReviewService.class),
                mock(ScoreConfigService.class),
                mock(StopLossTakeProfitEngine.class),
                mock(CapitalLedgerService.class),
                mock(CapitalService.class),
                reviewLogRepo
        );
    }

    @Test
    void getOpenPositions_includesReviewStatusWhenPresent() {
        PositionEntity p = openPosition(101L, "2303");
        when(positionRepo.findByStatusOrderByCreatedAtDesc(anyString(), any(PageRequest.class)))
                .thenReturn(List.of(p));

        PositionReviewLogEntity rev = new PositionReviewLogEntity();
        rev.setPositionId(101L);
        rev.setSymbol("2303");
        rev.setReviewDate(LocalDate.of(2026, 4, 24));
        rev.setReviewTime(LocalTime.of(13, 50));
        rev.setReviewType("INTRADAY");
        rev.setDecisionStatus("WEAKEN");
        rev.setReason("離 5MA 收斂、量縮、距停損 1.8%");
        when(reviewLogRepo.findTopByPositionIdOrderByCreatedAtDesc(101L))
                .thenReturn(Optional.of(rev));

        List<PositionResponse> rows = service.getOpenPositions(10);
        assertThat(rows).hasSize(1);
        PositionResponse r = rows.get(0);
        assertThat(r.reviewStatus()).isEqualTo("WEAKEN");
        assertThat(r.reviewReason()).contains("離 5MA");
        assertThat(r.reviewedAt()).isEqualTo(LocalDateTime.of(2026, 4, 24, 13, 50));
        assertThat(r.symbol()).isEqualTo("2303");
    }

    @Test
    void getOpenPositions_reviewStatusNullWhenNoLog() {
        PositionEntity p = openPosition(202L, "00631L");
        when(positionRepo.findByStatusOrderByCreatedAtDesc(anyString(), any(PageRequest.class)))
                .thenReturn(List.of(p));
        when(reviewLogRepo.findTopByPositionIdOrderByCreatedAtDesc(202L))
                .thenReturn(Optional.empty());

        List<PositionResponse> rows = service.getOpenPositions(10);
        assertThat(rows.get(0).reviewStatus()).isNull();
        assertThat(rows.get(0).reviewedAt()).isNull();
        assertThat(rows.get(0).reviewReason()).isNull();
    }

    @Test
    void getOpenPositions_repoExceptionDoesNotPropagate() {
        PositionEntity p = openPosition(303L, "2330");
        when(positionRepo.findByStatusOrderByCreatedAtDesc(anyString(), any(PageRequest.class)))
                .thenReturn(List.of(p));
        when(reviewLogRepo.findTopByPositionIdOrderByCreatedAtDesc(303L))
                .thenThrow(new RuntimeException("simulated DB failure"));

        // 即使 review_log 讀失敗，主資料仍應回，欄位為 null
        List<PositionResponse> rows = service.getOpenPositions(10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).reviewStatus()).isNull();
        assertThat(rows.get(0).symbol()).isEqualTo("2330");
    }

    @Test
    void getOpenPositions_strongStatusFlowsThrough() {
        PositionEntity p = openPosition(404L, "2454");
        when(positionRepo.findByStatusOrderByCreatedAtDesc(anyString(), any(PageRequest.class)))
                .thenReturn(List.of(p));
        PositionReviewLogEntity rev = new PositionReviewLogEntity();
        rev.setPositionId(404L);
        rev.setSymbol("2454");
        rev.setReviewDate(LocalDate.of(2026, 4, 25));
        rev.setReviewType("POSTMARKET");
        rev.setDecisionStatus("STRONG");
        rev.setReason("續抱：題材不變、距停損 8%");
        when(reviewLogRepo.findTopByPositionIdOrderByCreatedAtDesc(404L))
                .thenReturn(Optional.of(rev));

        PositionResponse r = service.getOpenPositions(10).get(0);
        assertThat(r.reviewStatus()).isEqualTo("STRONG");
        // reviewTime 為 null → 用 LocalTime.MIDNIGHT 拼
        assertThat(r.reviewedAt()).isEqualTo(LocalDateTime.of(2026, 4, 25, 0, 0));
    }

    private static PositionEntity openPosition(long id, String symbol) {
        PositionEntity p = new PositionEntity();
        // PositionEntity.id 沒有 public setter；用 reflection 注入測試 id
        try {
            java.lang.reflect.Field f = PositionEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("inject test id failed", e);
        }
        p.setSymbol(symbol);
        p.setStockName("test");
        p.setSide("LONG");
        p.setQty(new BigDecimal("1000"));
        p.setAvgCost(new BigDecimal("100"));
        p.setStatus("OPEN");
        p.setStrategyType("SETUP");
        return p;
    }
}
