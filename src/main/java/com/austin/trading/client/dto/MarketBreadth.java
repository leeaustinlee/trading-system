package com.austin.trading.client.dto;

/**
 * 大盤漲跌家數與指數
 *
 * @param advances       上漲家數
 * @param declines       下跌家數
 * @param unchanged      平盤家數
 * @param indexValue     加權指數
 * @param indexChange    指數漲跌點
 * @param indexChangePercent 指數漲跌幅 (%)
 * @param tradeDate      交易日期（yyyyMMdd）
 */
public record MarketBreadth(
        int advances,
        int declines,
        int unchanged,
        Double indexValue,
        Double indexChange,
        Double indexChangePercent,
        String tradeDate
) {
    public int total() {
        return advances + declines + unchanged;
    }

    /** 上漲/下跌比 > 1 為多方主導 */
    public double advanceDeclineRatio() {
        if (declines == 0) return advances > 0 ? 99.0 : 1.0;
        return Math.round((double) advances / declines * 100.0) / 100.0;
    }
}
