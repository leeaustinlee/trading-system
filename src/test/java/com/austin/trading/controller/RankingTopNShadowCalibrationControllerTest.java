package com.austin.trading.controller;

import com.austin.trading.dto.response.RankingTopNShadowCalibrationResponse;
import com.austin.trading.service.RankingTopNShadowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RankingTopNShadowCalibrationControllerTest {
    private FakeRankingTopNShadowService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = new FakeRankingTopNShadowService();
        mockMvc = MockMvcBuilders.standaloneSetup(new RankingTopNShadowCalibrationController(service)).build();
    }

    @Test
    void calibrationEndpointReturnsSafetyFlagsAndDelegatesDays() throws Exception {
        service.calibrationResponse = new RankingTopNShadowCalibrationResponse(
                true, true, true, true, 60,
                LocalDate.of(2026, 4, 21), LocalDate.of(2026, 6, 19), 4, 1,
                window(3, 3), window(5, 4), window(10, 4), window(20, 4),
                List.of(new RankingTopNShadowCalibrationResponse.TopNDeltaComparison(
                        "Top3 vs Top5", 3, 5, 1, 1, new BigDecimal("8.0000"),
                        new BigDecimal("12.0000"), new BigDecimal("100.0000"), new BigDecimal("100.0000"))),
                List.of());

        mockMvc.perform(get("/api/ranking/topn-shadow/calibration").param("days", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.shadowOnly").value(true))
                .andExpect(jsonPath("$.doesNotAffectRanking").value(true))
                .andExpect(jsonPath("$.doesNotAffectBuySell").value(true))
                .andExpect(jsonPath("$.top3.topN").value(3))
                .andExpect(jsonPath("$.top5.selectedCount").value(4))
                .andExpect(jsonPath("$.comparisons[0].comparison").value("Top3 vs Top5"));

        assertThat(service.calibrationDays).isEqualTo(60);
    }

    @Test
    void missedWinnersEndpointReturnsReadOnlyShadowReport() throws Exception {
        service.missedWinnersResponse = new RankingTopNShadowCalibrationResponse.MissedWinnersResponse(
                true, true, true, true, 30,
                LocalDate.of(2026, 5, 21), LocalDate.of(2026, 6, 19), 0,
                null, null, List.of(), List.of("NO_ROWS_IN_REQUESTED_WINDOW:ranking_topn_shadow_result"));

        mockMvc.perform(get("/api/ranking/topn-shadow/missed-winners").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.shadowOnly").value(true))
                .andExpect(jsonPath("$.doesNotAffectBuySell").value(true))
                .andExpect(jsonPath("$.totalMissedWinners").value(0))
                .andExpect(jsonPath("$.dataGaps[0]").value("NO_ROWS_IN_REQUESTED_WINDOW:ranking_topn_shadow_result"));

        assertThat(service.missedWinnersDays).isEqualTo(30);
    }

    @Test
    void themeQuotaEndpointReturnsExplicitQuotaAnalysis() throws Exception {
        service.themeQuotaResponse = new RankingTopNShadowCalibrationResponse.ThemeQuotaResponse(
                true, true, true, true, 45,
                LocalDate.of(2026, 5, 6), LocalDate.of(2026, 6, 19), 1,
                List.of(new RankingTopNShadowCalibrationResponse.ThemeQuotaAnalysis(
                        "AI", 5, 1, 2, 4, 5, 4, 2, new BigDecimal("50.0000"),
                        new BigDecimal("6.0000"), new BigDecimal("9.0000"), new BigDecimal("2.0000"),
                        new BigDecimal("7.0000"), 3, "SHADOW_ONLY_REVIEW:multiple missed winners outside current Top3; suggestedTop3Quota=3")),
                List.of());

        mockMvc.perform(get("/api/ranking/topn-shadow/theme-quota").param("days", "45"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.doesNotAffectRanking").value(true))
                .andExpect(jsonPath("$.themes[0].themeTag").value("AI"))
                .andExpect(jsonPath("$.themes[0].missedWinnerCount").value(2))
                .andExpect(jsonPath("$.themes[0].shadowQuotaRationale").value("SHADOW_ONLY_REVIEW:multiple missed winners outside current Top3; suggestedTop3Quota=3"));

        assertThat(service.themeQuotaDays).isEqualTo(45);
    }

    private RankingTopNShadowCalibrationResponse.TopNWindowComparison window(int topN, long count) {
        return new RankingTopNShadowCalibrationResponse.TopNWindowComparison(
                topN, count, count, new BigDecimal("2.0000"), new BigDecimal("80.0000"),
                new BigDecimal("1.0000"), new BigDecimal("75.0000"), new BigDecimal("4.0000"),
                new BigDecimal("75.0000"), new BigDecimal("6.0000"), new BigDecimal("75.0000"),
                new BigDecimal("-3.0000"), 0, new BigDecimal("0.0000"));
    }

    private static class FakeRankingTopNShadowService extends RankingTopNShadowService {
        private RankingTopNShadowCalibrationResponse calibrationResponse;
        private RankingTopNShadowCalibrationResponse.MissedWinnersResponse missedWinnersResponse;
        private RankingTopNShadowCalibrationResponse.ThemeQuotaResponse themeQuotaResponse;
        private int calibrationDays;
        private int missedWinnersDays;
        private int themeQuotaDays;

        private FakeRankingTopNShadowService() {
            super(null, null, null);
        }

        @Override
        public RankingTopNShadowCalibrationResponse calibration(int days) {
            this.calibrationDays = days;
            return calibrationResponse;
        }

        @Override
        public RankingTopNShadowCalibrationResponse.MissedWinnersResponse missedWinners(int days) {
            this.missedWinnersDays = days;
            return missedWinnersResponse;
        }

        @Override
        public RankingTopNShadowCalibrationResponse.ThemeQuotaResponse themeQuota(int days) {
            this.themeQuotaDays = days;
            return themeQuotaResponse;
        }
    }
}
