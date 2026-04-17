package com.austin.trading.ai.prompt;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * 08:30 盤前分析 Prompt 建構器。
 * <p>
 * 依 Level 4 中短線規則，請 Claude 分析：
 * ① 全球市場 / 台指期方向
 * ② 候選股現況評估
 * ③ 當日操作建議（不直接下單）
 * </p>
 */
public class PremarketPromptBuilder {

    private PremarketPromptBuilder() {}

    public static final String SYSTEM_CONTEXT = """
            你是 Austin 的台股中短線操盤研究員（Level 4 雙 AI 決策架構中的 Claude）。
            職責：對候選股進行基本面、籌碼面、技術面、風險面深度研究，
            輸出「買進/觀望/等回測/排除」建議，但不直接給最終下單張數。
            最終進場決策與倉位由 Codex 決定。
            使用繁體中文回答。
            """;

    /**
     * 建立 08:30 盤前分析提示詞
     *
     * @param date          交易日
     * @param market        昨日市場狀態（可為 null）
     * @param candidates    昨日候選股清單
     * @param txfSummary    台指期近月概況（可為 null）
     * @param globalSummary 全球市場摘要（可為 null，供使用者手動填入）
     */
    public static String build(
            LocalDate date,
            MarketCurrentResponse market,
            List<CandidateResponse> candidates,
            String txfSummary,
            String globalSummary
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 08:30 盤前研究請求 ─ ").append(date).append("\n\n");

        // 全球市場
        sb.append("### 全球市場\n");
        if (globalSummary != null && !globalSummary.isBlank()) {
            sb.append(globalSummary).append("\n");
        } else {
            sb.append("（全球市場資料請自行帶入：美股收盤、SOX、TSM ADR、日韓股市）\n");
        }
        if (txfSummary != null && !txfSummary.isBlank()) {
            sb.append("台指期：").append(txfSummary).append("\n");
        }
        sb.append("\n");

        // 昨日市場狀態
        if (market != null) {
            sb.append("### 昨日市場狀態\n");
            sb.append("行情等級：").append(market.marketGrade())
              .append("，階段：").append(market.marketPhase())
              .append("，決策：").append(market.decision()).append("\n\n");
        }

        // 候選股
        sb.append("### 今日觀察候選股（昨日盤後選出）\n");
        if (candidates.isEmpty()) {
            sb.append("（無候選資料）\n\n");
        } else {
            for (CandidateResponse c : candidates) {
                sb.append("- **").append(c.symbol()).append("** ");
                if (c.stockName() != null) sb.append(c.stockName()).append(" ");
                if (c.entryPriceZone() != null) sb.append("| 進場區：").append(c.entryPriceZone()).append(" ");
                if (c.riskRewardRatio() != null)
                    sb.append(String.format(Locale.ROOT, "| RR：%.2f ", c.riskRewardRatio().doubleValue()));
                if (c.reason() != null) sb.append("| ").append(c.reason());
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 請求
        sb.append("""
                ### 請依以下順序分析

                1. **台指期方向判斷**：今日偏多/偏空/震盪？
                2. **候選股現況**（每檔）：
                   - 昨日走勢是否符合預期
                   - 今日開盤建議（追強/等回測/觀望/排除）
                   - 進場條件更新（現價條件）
                3. **今日整體策略**：建議操作 1-2 檔還是全日觀望
                4. **主要風險**：需特別注意的市場條件

                輸出格式請遵守：各股分段、結論明確、禁止輸出「買 X 張」等具體下單指令。
                """);

        return sb.toString();
    }
}
