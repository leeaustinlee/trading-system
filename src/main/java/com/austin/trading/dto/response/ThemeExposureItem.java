package com.austin.trading.dto.response;

import java.math.BigDecimal;

/**
 * v2.16 Batch C：單一題材的曝險摘要。
 *
 * @param theme              題材標籤（"半導體/IC" / "PCB/載板/材料" 等；資料缺失為 "UNKNOWN"）
 * @param totalCost          該題材所有 OPEN 持倉的成本總和（qty × avgCost）
 * @param totalMarketValue   該題材所有持倉現值（qty × currentPrice）；無 quote 時 = totalCost
 * @param exposurePct        曝險百分比 = totalCost / sum(totalCost) × 100（0–100）
 * @param limitPct           score_config 設定上限（預設 30）
 * @param warnPct            score_config 設定警告閾值（預設 20）
 * @param status             OK / WARN / OVER_LIMIT
 * @param positionCount      該題材持倉檔數
 */
public record ThemeExposureItem(
        String     theme,
        BigDecimal totalCost,
        BigDecimal totalMarketValue,
        BigDecimal exposurePct,
        BigDecimal limitPct,
        BigDecimal warnPct,
        String     status,
        int        positionCount
) {}
