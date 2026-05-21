package com.austin.trading.notify;

import com.austin.trading.domain.enums.HoldDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramTemplateServiceFormattingTests {

    @Test
    void wrapHtmlRemovesDuplicateHeadlineFromBody() {
        String html = TelegramTemplateService.wrapHtml(
                "🧭 明日策略",
                "🧭 明日策略\r\n\r\n📊 市場\n- 觀察\n\n📌 結論\n- 人工確認"
        );

        assertThat(html).startsWith("<b>🧭 明日策略</b>");
        assertThat(html).contains("📊 市場");
        assertThat(html).contains("📌 結論");
        assertThat(html).doesNotContain("🧭 明日策略\n\n🧭 明日策略");
    }

    @Test
    void healthV2RowsMapToMiddayPortfolioDecisionSource() {
        var dto = TelegramTemplateService.toPositionIntelligenceResult(Map.of(
                "symbol", "1582",
                "stockName", "信錦",
                "actionTier", "HOLD",
                "structureStatus", "BULL_ALIGNED",
                "volumeStatus", "RISING_VOLUME",
                "relativeStrengthStatus", "OUTPERFORM",
                "trailingStopPrice", new BigDecimal("117.98"),
                "takeProfit2", new BigDecimal("118.65"),
                "reasons", List.of("price_above_ma5_ma10_ma20", "mainstream_theme")
        ));

        assertThat(dto.stockId()).isEqualTo("1582");
        assertThat(dto.holdDecision()).isEqualTo(HoldDecision.HOLD);
        assertThat(dto.suggestedStop()).isEqualByComparingTo("117.98");
        assertThat(dto.reason()).contains("price_above_ma5_ma10_ma20", "structure=BULL_ALIGNED");
    }

    @Test
    void healthV2ExitTierMapsToExitInsteadOfLegacyTrailingStopHeuristic() {
        var dto = TelegramTemplateService.toPositionIntelligenceResult(Map.of(
                "symbol", "00631L",
                "stockName", "元大正二",
                "actionTier", "EXIT_REVIEW",
                "structureStatus", "MA10_BREAK",
                "volumeStatus", "NORMAL",
                "relativeStrengthStatus", "INLINE",
                "stopLossPrice", new BigDecimal("30.24"),
                "reasons", List.of("below_ma10")
        ));

        assertThat(dto.holdDecision()).isEqualTo(HoldDecision.EXIT);
        assertThat(dto.suggestedStop()).isEqualByComparingTo("30.24");
        assertThat(dto.reason()).contains("below_ma10", "structure=MA10_BREAK");
    }

    @Test
    void structuralObserveTierPreventsTelegramExitWordingEvenWhenLegacyActionTierIsExitReview() {
        var dto = TelegramTemplateService.toPositionIntelligenceResult(Map.of(
                "symbol", "1582",
                "stockName", "信錦",
                "actionTier", "EXIT_REVIEW",
                "structuralTier", "OBSERVE_1D",
                "structuralReason", "price_broken but structure_intact; tolerate washout and observe one trading day",
                "structureStatus", "NEUTRAL",
                "volumeStatus", "RISING_VOLUME",
                "relativeStrengthStatus", "OUTPERFORM",
                "trailingStopPrice", new BigDecimal("117.98"),
                "reasons", List.of("below_trailing_stop")
        ));

        assertThat(dto.holdDecision()).isEqualTo(HoldDecision.HOLD);
        assertThat(dto.reason()).contains("structure_intact");
        assertThat(dto.reason()).doesNotContain("actionTier=EXIT_REVIEW");
    }
}
