package com.austin.trading.notify;

import com.austin.trading.domain.enums.HoldDecision;
import com.austin.trading.domain.enums.MarketBias;
import com.austin.trading.domain.enums.PositionRiskLevel;
import com.austin.trading.domain.enums.PositionStrength;
import com.austin.trading.domain.enums.SwitchDecision;
import com.austin.trading.dto.response.NextDayStrategyDto;
import com.austin.trading.dto.response.PortfolioSwitchSuggestionDto;
import com.austin.trading.dto.response.PositionIntelligenceResultDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradingNotificationFlowStabilizationTests {

    private final TradingNotificationDecisionFormatter formatter = new TradingNotificationDecisionFormatter();

    @Test
    void middayRawMarkdownDoesNotTreatIndexOrThresholdNumbersAsHoldings() {
        String raw = """
                market_grade=A 台股加權 41974 強勢，櫃買 36700 震盪
                風控門檻：2322 / 2000，追高風險
                MIDDAY raw markdown 沒有 structured portfolio review
                """;

        String out = formatter.format("MIDDAY", raw, LocalDate.of(2026, 5, 8));

        assertThat(out).contains("持倉健檢資料不足");
        assertThat(out).doesNotContain("41974 →", "36700 →", "2322 →", "2000 →");
        assertThat(out.lines().count()).isLessThanOrEqualTo(20);
    }

    @Test
    void middayUsesPortfolioReviewDtoAsOnlyPositionSource() {
        String raw = "market_grade=B 指數 41974 門檻 36700";
        List<PositionIntelligenceResultDto> review = List.of(
                new PositionIntelligenceResultDto("00631L", "元大正二", PositionStrength.STRONG,
                        PositionRiskLevel.MEDIUM, HoldDecision.HOLD,
                        new BigDecimal("32.09"), new BigDecimal("35.00"), SwitchDecision.KEEP, "續強"),
                new PositionIntelligenceResultDto("2303", "聯電", PositionStrength.WEAK,
                        PositionRiskLevel.HIGH, HoldDecision.REDUCE,
                        new BigDecimal("88.00"), null, SwitchDecision.PARTIAL_SWITCH, "轉弱")
        );

        String out = formatter.formatMidday(raw, review, LocalDate.of(2026, 5, 8));

        assertThat(out).contains("00631L → HOLD", "2303 → REDUCE", "停損 32.09", "換股");
        assertThat(out).doesNotContain("41974 →", "36700 →");
        assertThat(out.lines().count()).isLessThanOrEqualTo(20);
    }

    @Test
    void nextDayStrategyUsesNextDayStrategyDto() {
        NextDayStrategyDto dto = new NextDayStrategyDto(
                LocalDate.of(2026, 5, 8),
                List.of(new PositionIntelligenceResultDto("6770", "力積電", PositionStrength.NEUTRAL,
                        PositionRiskLevel.MEDIUM, HoldDecision.HOLD,
                        new BigDecimal("55.00"), new BigDecimal("64.00"), SwitchDecision.KEEP, "續抱觀察")),
                List.of(new PortfolioSwitchSuggestionDto("2303", "8039", "台虹", "breakout",
                        SwitchDecision.SWITCH, new BigDecimal("12.5"), "小倉", "更強")),
                MarketBias.WATCH,
                "明日進場方向：breakout / pullback / continuation；一句話結論：先觀察再確認。",
                "人工確認，不自動下單"
        );

        String out = formatter.formatNextDayStrategy(dto);

        assertThat(out).contains("🧭 明日策略", "觀察", "6770 → HOLD", "建議轉進 8039", "breakout");
        assertThat(out).doesNotContain("raw", "veto", "| 代號 |");
        assertThat(out.lines().count()).isLessThanOrEqualTo(20);
    }

    @Test
    void premarketGradeAIsObserveAggressiveNotPureAttack() {
        String out = formatter.format("PREMARKET", "market_grade=A 半導體 偏多", LocalDate.of(2026, 5, 8));
        assertThat(out).contains("觀察偏進攻");
        assertThat(out).doesNotContain("- 進攻");
    }

    @Test
    void postmarketDoesNotOutputPositionTomorrowHandlingOrVetoTable() {
        String raw = """
                market_grade=B 半導體 記憶體
                | 代號 | 原因 |
                | 6770 | Veto |
                持倉明日建議 00631L HOLD 2303 REDUCE
                明日操作：完整買點與張數
                """;
        String out = formatter.format("POSTMARKET", raw, LocalDate.of(2026, 5, 8));
        assertThat(out).contains("🌙 盤後分析");
        assertThat(out).doesNotContain("00631L →", "2303 →", "持倉明日建議", "| 代號 |", "Veto");
    }

    @Test
    void expiredAiTaskProducesSystemAlertInsteadOfTradeDecision() {
        String out = formatter.format("PREMARKET", "AI_TASK_EXPIRED generated_at 過期", LocalDate.of(2026, 5, 8));
        assertThat(out).contains("🚨 系統警報", "AI task 過期");
        assertThat(out).doesNotContain("盤前策略", "可進場");
    }
}
