package com.austin.trading.ai.prompt;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 個股深度研究 Prompt 建構器。
 * <p>
 * 供 Claude 對單一候選股進行基本面、籌碼面、技術面、風險面全方位分析，
 * 輸出「買進/觀望/等回測/排除」建議。
 * </p>
 */
public class StockEvaluationPromptBuilder {

    private StockEvaluationPromptBuilder() {}

    public static final String SYSTEM_CONTEXT = PremarketPromptBuilder.SYSTEM_CONTEXT;

    /**
     * 建立個股深度研究提示詞
     *
     * @param date      交易日
     * @param candidate 候選股資料
     * @param market    當日市場狀態
     * @param payloadJson 候選股擴充資料 JSON（含法人流向、收盤報價等，可為 null）
     */
    public static String build(
            LocalDate date,
            CandidateResponse candidate,
            MarketCurrentResponse market,
            String payloadJson
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 個股深度研究 ─ ")
          .append(candidate.symbol()).append(" ")
          .append(candidate.stockName() != null ? candidate.stockName() : "")
          .append(" ─ ").append(date).append("\n\n");

        // 市場背景
        if (market != null) {
            sb.append("### 當日市場背景\n");
            sb.append("行情等級 ").append(market.marketGrade())
              .append("，階段：").append(market.marketPhase())
              .append("，決策：").append(market.decision()).append("\n\n");
        }

        // 個股基本資訊
        sb.append("### 候選股資料\n");
        sb.append("- 代號：").append(candidate.symbol()).append("\n");
        if (candidate.stockName() != null)
            sb.append("- 名稱：").append(candidate.stockName()).append("\n");
        if (candidate.entryPriceZone() != null)
            sb.append("- 進場區間：").append(candidate.entryPriceZone()).append("\n");
        if (candidate.riskRewardRatio() != null)
            sb.append(String.format(Locale.ROOT, "- RR 比：%.2f%n", candidate.riskRewardRatio().doubleValue()));
        if (candidate.valuationMode() != null)
            sb.append("- 估值模式：").append(candidate.valuationMode()).append("\n");
        if (candidate.reason() != null)
            sb.append("- 入選原因：").append(candidate.reason()).append("\n");

        // 擴充資料（法人流向、報價等）
        if (payloadJson != null && !payloadJson.isBlank() && !payloadJson.equals("{}")) {
            sb.append("\n### 擴充資料（來自系統）\n```json\n")
              .append(payloadJson).append("\n```\n");
        }
        sb.append("\n");

        // 分析請求
        sb.append("""
                ### 請依以下順序進行深度分析

                1. **題材面**：主題是否明確？有延續性嗎？是否為一日題材？
                2. **法人籌碼**：外資/投信/自營動向？是否有倒貨訊號？
                3. **技術面**：
                   - 目前型態（整理/突破/拉回）
                   - 是否站上 5 日均線？量能是否配合？
                   - 近期高點低點結構
                4. **基本面（快速檢視）**：近期法說/財報/大事件？
                5. **風險面**：除息除權日、法說日、爆量出貨訊號？
                6. **結論**：
                   - 操作建議：買進 / 觀望 / 等回測後確認 / 排除
                   - 建議進場條件（以現價為基礎）
                   - 停損參考位
                   - 一句話理由

                禁止輸出「買 X 張」等具體下單指令。
                """);

        return sb.toString();
    }
}
