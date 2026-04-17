package com.austin.trading.ai.prompt;

import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.PositionResponse;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * 整點行情閘 Prompt 建構器。
 * <p>
 * 每小時評估當前盤況，給予持倉續抱/停利/換股建議。
 * </p>
 */
public class HourlyGatePromptBuilder {

    private HourlyGatePromptBuilder() {}

    public static final String SYSTEM_CONTEXT = PremarketPromptBuilder.SYSTEM_CONTEXT;

    /**
     * 建立整點行情閘分析提示詞
     *
     * @param date          交易日
     * @param time          當前時間
     * @param market        市場快照
     * @param gateDecision  行情閘決策
     * @param openPositions 目前持倉
     */
    public static String build(
            LocalDate date,
            LocalTime time,
            MarketCurrentResponse market,
            HourlyGateDecisionResponse gateDecision,
            List<PositionResponse> openPositions
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 整點行情閘研究 ─ ").append(date).append(" ").append(time).append("\n\n");

        // 市場狀態
        if (market != null) {
            sb.append("### 當前市場\n");
            sb.append("行情等級 ").append(market.marketGrade())
              .append("，階段：").append(market.marketPhase()).append("\n");
        }
        if (gateDecision != null) {
            sb.append("閘狀態：").append(gateDecision.hourlyGate())
              .append("，時間衰減：").append(gateDecision.timeDecayStage())
              .append("，鎖定：").append(gateDecision.decisionLock()).append("\n\n");
        }

        // 持倉
        if (openPositions != null && !openPositions.isEmpty()) {
            sb.append("### 目前持倉\n");
            for (PositionResponse p : openPositions) {
                sb.append(String.format(Locale.ROOT,
                        "- %s %s 均成本 %.2f × %.0f 股%n",
                        p.symbol(), p.side(),
                        p.avgCost().doubleValue(), p.qty().doubleValue()));
            }
            sb.append("\n");
        } else {
            sb.append("### 目前持倉：無\n\n");
        }

        sb.append("""
                ### 請分析以下事項

                1. **當前盤況**：行情處於哪個階段？強度是否維持？
                2. **持倉評估**（每檔）：
                   - 是否接近停利/停損
                   - 是否出現盤型轉弱訊號
                   - 建議：續抱 / 減碼 / 出場
                3. **新機會**：若空手，是否有短線介入機會（需符合 Level 4 條件）
                4. **接下來 1 小時重點觀察**：關鍵價位與條件

                禁止輸出「買 X 張」等具體下單指令。
                """);

        return sb.toString();
    }
}
