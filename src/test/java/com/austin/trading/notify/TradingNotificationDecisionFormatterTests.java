package com.austin.trading.notify;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TradingNotificationDecisionFormatterTests {

    private final TradingNotificationDecisionFormatter formatter = new TradingNotificationDecisionFormatter();

    @Test
    void premarketSummarizesDecisionAndRemovesTablesScoresAndVetoLists() {
        String raw = """
                # Claude thesis very long paragraph should not leak
                | 代號 | score | rank |
                | 2330 | 95 | 1 |
                market_grade=A 市場偏多 半導體 AI伺服器 記憶體
                final decision: 可進場 breakout continuation
                Veto / 排除清單: 6770 score 80 rank 2 debug trace
                風險: 追高風險; 量縮; 外資轉賣
                """;

        String out = formatter.format("PREMARKET", raw, LocalDate.of(2026, 5, 8));

        assertThat(out).contains("🌅 盤前策略", "📊 市場", "🎯 策略", "🔥 主流", "⚠️ 風險");
        assertThat(out).contains("等級：A");
        assertThat(out).contains("進攻");
        assertThat(out).doesNotContain("| 代號", "Veto", "score", "rank", "debug", "Claude thesis");
        assertThat(out.lines().count()).isLessThanOrEqualTo(20);
    }

    @Test
    void middayRequiresPositionActionsAndSwitchSummary() {
        String raw = """
                market_grade=B 觀察
                00631L HOLD 強勢續抱 stop 32.09
                2303 REDUCE 轉弱 壓力大
                6770 EXIT 跌破停損
                switch to 8039 breakout 更強標的
                candidate table score rank should be hidden
                """;

        String out = formatter.format("MIDDAY", raw, LocalDate.of(2026, 5, 8));

        assertThat(out).contains("🕛 盤中更新", "📌 持倉", "00631L → HOLD", "2303 → REDUCE", "6770 → EXIT");
        assertThat(out).contains("🔄 換股", "8039");
        assertThat(out).doesNotContain("candidate table", "score", "rank");
        assertThat(out.lines().count()).isLessThanOrEqualTo(20);
    }

    @Test
    void positionReviewShowsEveryPositionActionAndStopOrTakeProfit() {
        String raw = """
                00631L strength STRONG risk MEDIUM holdDecision HOLD suggestedStop 32.09 suggestedTakeProfit 35.00
                2303 strength WEAK risk HIGH holdDecision EXIT suggestedStop 92.59
                6770 strength STRONG risk LOW holdDecision HIGH_HOLD suggestedStop 63.47
                """;

        String out = formatter.format("POSITION_REVIEW", raw, LocalDate.of(2026, 5, 8));

        assertThat(out).contains("🩺 持倉健檢", "00631L → HOLD", "2303 → EXIT", "6770 → HOLD");
        assertThat(out).contains("停損 32.09", "強勢股數");
        assertThat(out.lines().count()).isLessThanOrEqualTo(20);
    }

    @Test
    void nextDayStrategyOutputsActionablePlanWithoutReasoningDump() {
        String raw = """
                tradingDate 2026-05-08 marketBias WATCH market_grade=B
                00631L HOLD 2303 REDUCE 6770 HOLD
                switchPlan 無更強標的
                breakout continuation pullback
                Codex reasoning: long trace should be removed. Claude thesis: long narrative.
                """;

        String out = formatter.format("NEXT_DAY_STRATEGY", raw, LocalDate.of(2026, 5, 8));

        assertThat(out).contains("🧭 明日策略", "📊 市場", "🎯 策略", "📌 持倉", "🔄 換股", "💰 明日進場方向");
        assertThat(out).contains("00631L → HOLD", "2303 → REDUCE", "無");
        assertThat(out).doesNotContain("Codex reasoning", "Claude thesis", "trace");
        assertThat(out.lines().count()).isLessThanOrEqualTo(20);
    }

    @Test
    void unknownTypeFallsBackToSafeDecisionDigest() {
        String raw = "market_grade=C 觀望 00631L HOLD veto list score rank debug";

        String out = formatter.format("SOMETHING", raw, LocalDate.of(2026, 5, 8));

        assertThat(out).contains("🧭 交易通知", "📊 市場", "📌 結論");
        assertThat(out).doesNotContain("veto", "score", "rank", "debug");
        assertThat(out.lines().count()).isLessThanOrEqualTo(20);
    }
}
