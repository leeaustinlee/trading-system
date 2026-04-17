package com.austin.trading.dto.response;

/**
 * 即時報價回應（整合候選股資料 + TWSE MIS 即時行情）。
 *
 * @param symbol        股票代號
 * @param stockName     股票名稱
 * @param market        "tse"（上市）/ "otc"（上櫃）
 * @param currentPrice  最新成交價；盤外或尚未成交為 null
 * @param prevClose     昨收
 * @param open          開盤價
 * @param dayHigh       日高
 * @param dayLow        日低
 * @param changePercent 漲跌幅 (%)；無法計算時為 null
 * @param changeAmount  漲跌金額；無法計算時為 null
 * @param volume        成交張數
 * @param tradeTime     最後成交時間（HH:mm:ss）
 * @param available     是否有有效報價（盤中已成交）
 */
public record LiveQuoteResponse(
        String symbol,
        String stockName,
        String market,
        Double currentPrice,
        Double prevClose,
        Double open,
        Double dayHigh,
        Double dayLow,
        Double changePercent,
        Double changeAmount,
        Long   volume,
        String tradeTime,
        boolean available
) {}
