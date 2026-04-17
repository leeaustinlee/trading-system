package com.austin.trading.client.dto;

/**
 * TWSE MIS API 即時報價
 *
 * @param symbol       股票代號
 * @param name         股票名稱
 * @param market       "tse"（上市）或 "otc"（上櫃）
 * @param currentPrice 最新成交價（盤中為空返回 null）
 * @param prevClose    昨日收盤
 * @param open         開盤價
 * @param dayHigh      日高
 * @param dayLow       日低
 * @param bidPrice     最佳買價
 * @param askPrice     最佳賣價
 * @param volume       成交張數
 * @param tradeDate    交易日期（yyyyMMdd）
 * @param tradeTime    最後成交時間（HH:mm:ss）
 * @param available    是否有有效報價（盤中已成交）
 */
public record StockQuote(
        String symbol,
        String name,
        String market,
        Double currentPrice,
        Double prevClose,
        Double open,
        Double dayHigh,
        Double dayLow,
        Double bidPrice,
        Double askPrice,
        Long volume,
        String tradeDate,
        String tradeTime,
        boolean available
) {
    /** 現價相對昨收漲跌幅 (%)，無資料時返回 null */
    public Double changePercent() {
        if (currentPrice == null || prevClose == null || prevClose == 0) return null;
        return Math.round((currentPrice - prevClose) / prevClose * 10000.0) / 100.0;
    }
}
