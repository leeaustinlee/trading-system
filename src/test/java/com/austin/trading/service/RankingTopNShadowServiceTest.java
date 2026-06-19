package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RankingTopNShadowServiceTest {

    @Test
    void bucketForRankCoversRequiredRanges() {
        assertThat(RankingTopNShadowService.bucketForRank(1)).isEqualTo("TOP1");
        assertThat(RankingTopNShadowService.bucketForRank(2)).isEqualTo("TOP2_3");
        assertThat(RankingTopNShadowService.bucketForRank(3)).isEqualTo("TOP2_3");
        assertThat(RankingTopNShadowService.bucketForRank(4)).isEqualTo("TOP4_5");
        assertThat(RankingTopNShadowService.bucketForRank(5)).isEqualTo("TOP4_5");
        assertThat(RankingTopNShadowService.bucketForRank(6)).isEqualTo("TOP6_10");
        assertThat(RankingTopNShadowService.bucketForRank(10)).isEqualTo("TOP6_10");
        assertThat(RankingTopNShadowService.bucketForRank(11)).isEqualTo("TOP11_20");
        assertThat(RankingTopNShadowService.bucketForRank(20)).isEqualTo("TOP11_20");
        assertThat(RankingTopNShadowService.bucketForRank(21)).isEqualTo("OUTSIDE_TOP20");
    }

    @Test
    void missedByTop3RequiresOutsideTop3AndAvailableHighForwardReturn() {
        assertThat(RankingTopNShadowService.missedByTop3(3, new BigDecimal("20"), null)).isFalse();
        assertThat(RankingTopNShadowService.missedByTop3(4, new BigDecimal("5.01"), null)).isTrue();
        assertThat(RankingTopNShadowService.missedByTop3(8, null, new BigDecimal("10.01"))).isTrue();
        assertThat(RankingTopNShadowService.missedByTop3(8, new BigDecimal("5.00"), new BigDecimal("10.00"))).isFalse();
        assertThat(RankingTopNShadowService.missedByTop3(8, null, null)).isFalse();
    }

    @Test
    void themeAdmissionHelpersClassifyCurrentBlock() {
        assertThat(ThemeAdmissionShadowService.isBlockedByCurrentStage("REJECT", null)).isTrue();
        assertThat(ThemeAdmissionShadowService.isBlockedByCurrentStage("WRITE_CANDIDATE", null)).isFalse();
        assertThat(ThemeAdmissionShadowService.isBlockedByCurrentStage(null, "vetoed by old gate")).isTrue();
    }
}
