package com.austin.trading.client.dto;

/**
 * 台指期近月報價
 *
 * @param contract       合約代號（例如 "TX"）
 * @param currentPrice   最新成交價
 * @param prevClose      前日收盤 / 前日結算
 * @param change         漲跌點數
 * @param changePercent  漲跌幅 (%)
 * @param volume         成交量（口）
 * @param tradeTime      最後成交時間（HH:mm:ss）
 * @param available      是否有有效報價
 */
public record FuturesQuote(
        String contract,
        Double currentPrice,
        Double prevClose,
        Double change,
        Double changePercent,
        Long volume,
        String tradeTime,
        boolean available
) {
    public boolean isUp() {
        return change != null && change > 0;
    }

    public boolean isDown() {
        return change != null && change < 0;
    }
}
