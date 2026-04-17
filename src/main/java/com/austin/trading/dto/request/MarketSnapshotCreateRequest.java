package com.austin.trading.dto.request;

import java.time.LocalDate;

/**
 * 手動建立 / 覆蓋市場快照。
 *
 * <p>tradingDate 若不傳，Service 層補上今日。
 * marketGrade: A（主升）/ B（強勢震盪）/ C（出貨/震盪）
 * marketPhase: PREMARKET / 開盤洗盤期 / 主升發動期 / 高檔震盪期 / 出貨鈍化期 / CLOSE
 * decision:    ENTER / WATCH / REST
 * </p>
 */
public record MarketSnapshotCreateRequest(
        LocalDate tradingDate,
        String marketGrade,
        String marketPhase,
        String decision,
        String payloadJson
) {
}
