package com.austin.trading.client.dto;

/**
 * TWSE T86 三大法人買賣超
 *
 * @param symbol          股票代號
 * @param name            股票名稱
 * @param foreignNet      外資（含外資自營商）淨買超股數
 * @param investTrustNet  投信淨買超股數
 * @param dealerNet       自營商淨買超股數
 * @param totalNet        三大法人合計淨買超股數
 */
public record InstitutionalFlow(
        String symbol,
        String name,
        Long foreignNet,
        Long investTrustNet,
        Long dealerNet,
        Long totalNet
) {
    /** 是否呈現外資 + 投信同向買超（強多訊號） */
    public boolean foreignAndTrustBothBuy() {
        return foreignNet != null && foreignNet > 0
                && investTrustNet != null && investTrustNet > 0;
    }
}
