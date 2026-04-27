package com.austin.trading.controller;

import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.PositionReviewService;
import com.austin.trading.service.PositionReviewService.PendingExitItem;
import com.austin.trading.service.PositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0.2:GET /api/positions/review/pending-exits 行為驗證。
 */
class PositionReviewControllerTests {

    private PositionReviewService reviewService;
    private PositionController controller;

    @BeforeEach
    void setUp() {
        reviewService = mock(PositionReviewService.class);
        controller = new PositionController(
                mock(PositionService.class),
                mock(CandidateScanService.class),
                reviewService
        );
    }

    @Test
    void getPendingExits_returnsCountAndItems() {
        LocalDateTime t1 = LocalDateTime.of(2026, 4, 27, 10, 30);
        LocalDateTime t2 = LocalDateTime.of(2026, 4, 27, 11,  0);
        when(reviewService.findPendingExits()).thenReturn(List.of(
                new PendingExitItem("2330", "停損觸發", t1),
                new PendingExitItem("2317", "趨勢轉弱", t2)
        ));

        Map<String, Object> result = controller.getPendingExits();

        assertThat(result).containsEntry("count", 2);
        @SuppressWarnings("unchecked")
        List<PendingExitItem> items = (List<PendingExitItem>) result.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).symbol()).isEqualTo("2330");
        assertThat(items.get(0).reason()).isEqualTo("停損觸發");
        assertThat(items.get(0).reviewedAt()).isEqualTo(t1);
        assertThat(items.get(1).symbol()).isEqualTo("2317");
    }

    @Test
    void getPendingExits_emptyListReturnsZeroCount() {
        when(reviewService.findPendingExits()).thenReturn(List.of());

        Map<String, Object> result = controller.getPendingExits();

        assertThat(result).containsEntry("count", 0);
        @SuppressWarnings("unchecked")
        List<PendingExitItem> items = (List<PendingExitItem>) result.get("items");
        assertThat(items).isEmpty();
    }
}
