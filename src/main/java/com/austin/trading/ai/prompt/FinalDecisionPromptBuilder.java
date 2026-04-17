package com.austin.trading.ai.prompt;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.PositionResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * 09:30 最終決策前 Prompt 建構器。
 * <p>
 * 請 Claude 在最終下單前做最後一輪研究確認：
 * ① 開盤量價確認
 * ② 入選標的即時現況
 * ③ 風險確認
 * </p>
 */
public class FinalDecisionPromptBuilder {

    private FinalDecisionPromptBuilder() {}

    public static final String SYSTEM_CONTEXT = PremarketPromptBuilder.SYSTEM_CONTEXT;

    /**
     * 建立 09:30 最終決策前研究提示詞
     *
     * @param date           交易日
     * @param market         當日市場狀態
     * @param engineDecision 引擎初始決策（可為 null）
     * @param candidates     今日候選股
     * @param openPositions  目前持倉
     */
    public static String build(
            LocalDate date,
            MarketCurrentResponse market,
            FinalDecisionResponse engineDecision,
            List<CandidateResponse> candidates,
            List<PositionResponse> openPositions
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 09:30 最終決策研究確認 ─ ").append(date).append("\n\n");

        // 市場狀態
        if (market != null) {
            sb.append("### 當日市場（09:30 前快照）\n");
            sb.append("行情等級 ").append(market.marketGrade())
              .append("，階段：").append(market.marketPhase())
              .append("，決策：").append(market.decision()).append("\n\n");
        }

        // 引擎初始決策
        if (engineDecision != null) {
            sb.append("### 決策引擎初始結果\n");
            sb.append("決策：**").append(engineDecision.decision()).append("**\n");
            sb.append("摘要：").append(engineDecision.summary()).append("\n");
            if (!engineDecision.selectedStocks().isEmpty()) {
                sb.append("入選標的：");
                for (FinalDecisionSelectedStockResponse s : engineDecision.selectedStocks()) {
                    sb.append(s.stockCode()).append("(").append(s.entryPriceZone()).append(") ");
                }
                sb.append("\n");
            }
            if (!engineDecision.rejectedReasons().isEmpty()) {
                sb.append("排除原因：").append(String.join("；", engineDecision.rejectedReasons())).append("\n");
            }
            sb.append("\n");
        }

        // 目前持倉
        if (openPositions != null && !openPositions.isEmpty()) {
            sb.append("### 目前持倉\n");
            for (PositionResponse p : openPositions) {
                sb.append(String.format(Locale.ROOT, "- %s %s × %.0f 股，成本 %.2f%n",
                        p.symbol(), p.side(), p.qty().doubleValue(), p.avgCost().doubleValue()));
            }
            sb.append("\n");
        }

        // 候選股
        if (!candidates.isEmpty()) {
            sb.append("### 今日候選股（待確認）\n");
            for (CandidateResponse c : candidates) {
                sb.append("- **").append(c.symbol()).append("** ");
                if (c.stockName() != null) sb.append(c.stockName());
                if (c.entryPriceZone() != null) sb.append(" 進場區：").append(c.entryPriceZone());
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("""
                ### 09:30 前請確認以下事項

                1. **大盤開盤確認**：台積電開盤方向？大盤前 5 分鐘量價？是否符合進場條件？
                2. **入選標的確認**（每檔）：
                   - 開盤量價是否如預期（突破/回測/轉強）
                   - 現價是否在進場區間內
                   - 是否有開高走低或爆量異常訊號
                3. **進場決策**：
                   - 最終建議：進場 / 觀望 / 今日休息
                   - 若進場：確認進場條件、停損位、預計倉位方向
                4. **風險提示**：有無需特別注意的短期風險

                ⚠️ 最終下單張數與倉位金額由 Codex 決定，不得在此輸出。
                """);

        return sb.toString();
    }
}
