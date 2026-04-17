package com.austin.trading.ai.prompt;

import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import com.austin.trading.dto.response.PositionResponse;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * 5 分鐘監控 Prompt 建構器。
 * <p>
 * 在 monitorMode=ACTIVE 且 shouldNotify=true 時，
 * 提供即時行動建議（持倉警告/機會確認）。
 * </p>
 */
public class MonitorPromptBuilder {

    private MonitorPromptBuilder() {}

    public static final String SYSTEM_CONTEXT = PremarketPromptBuilder.SYSTEM_CONTEXT;

    /**
     * 建立 5 分鐘監控提示詞
     *
     * @param date           交易日
     * @param time           觸發時間
     * @param monitorDecision 監控決策
     * @param market         市場快照
     * @param openPositions  目前持倉
     * @param triggerReason  觸發原因描述
     */
    public static String build(
            LocalDate date,
            LocalTime time,
            MonitorDecisionResponse monitorDecision,
            MarketCurrentResponse market,
            List<PositionResponse> openPositions,
            String triggerReason
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 5 分鐘監控警示 ─ ").append(date).append(" ").append(time).append("\n\n");

        // 觸發原因
        if (triggerReason != null && !triggerReason.isBlank()) {
            sb.append("### 觸發原因\n").append(triggerReason).append("\n\n");
        }

        // 市場快照
        if (market != null) {
            sb.append("行情等級 ").append(market.marketGrade())
              .append("，階段：").append(market.marketPhase()).append("\n");
        }
        if (monitorDecision != null) {
            sb.append("監控模式：").append(monitorDecision.monitorMode())
              .append("，決策：").append(monitorDecision.decision()).append("\n\n");
        }

        // 持倉
        if (openPositions != null && !openPositions.isEmpty()) {
            sb.append("### 目前持倉\n");
            for (PositionResponse p : openPositions) {
                sb.append(String.format(Locale.ROOT,
                        "- %s 均成本 %.2f 現有 %.0f 股%n",
                        p.symbol(), p.avgCost().doubleValue(), p.qty().doubleValue()));
            }
            sb.append("\n");
        }

        sb.append("""
                ### 需要立即判斷

                1. 觸發訊號是否為真實轉折或假突破？
                2. 目前持倉的停損位是否被威脅？
                3. 建議行動：繼續持有 / 立即出場 / 減碼保護

                請給出 1-2 句明確行動結論，不需要詳細分析。
                """);

        return sb.toString();
    }
}
