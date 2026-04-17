# 台股 5 分鐘監控啟用與通知判斷引擎

本文件是 Austin 台股短線交易系統的 5 分鐘監控規則。任務不是單純回報報價，而是先判斷這一輪監控是否要啟用、是否值得發 LINE、以及是否有真正重要的新事件。

## 核心輸出

每輪監控必須先產生結構化 JSON，供外部程式判斷是否發 LINE：

```json
{
  "market_grade": "A | B | C",
  "market_phase": "開盤洗盤期 | 主升發動期 | 高檔震盪期 | 出貨 / 鈍化期",
  "decision": "ENTER | WATCH | REST",
  "monitor_mode": "OFF | WATCH | ACTIVE",
  "should_notify": true,
  "trigger_event": "NONE | MARKET_UPGRADE | MARKET_DOWNGRADE | ENTRY_READY | BREAKOUT | PULLBACK_HOLD | STOP_LOSS | TAKE_PROFIT | POSITION_MANAGE | CANDIDATE_CANCEL | WATCHLIST_UPDATE",
  "monitor_reason": "一句話說明本輪監控模式",
  "next_check_focus": "一句話說明下一輪重點",
  "decision_lock": "NONE | LOCKED | RELEASED",
  "cooldown_minutes": 10,
  "last_event_time": "HH:mm:ss",
  "last_event_type": "ENTRY_READY",
  "time_decay_stage": "EARLY | MID | LATE",
  "summary_for_log": "給程式記錄用的一句摘要"
}
```

## 決策鎖 Decision Lock

`decision_lock` 只能是：

- NONE：未鎖定。
- LOCKED：鎖定，禁止進場。
- RELEASED：由鎖定解除。

啟動 LOCK 條件：

- market_grade = C
- decision = REST
- hourly_gate = OFF_HARD
- 無持倉且無候選股

LOCK 狀態規則：

- 禁止 decision = ENTER。
- monitor_mode 只能為 OFF 或 WATCH。
- 無持倉時 should_run_5m_monitor = false。

解鎖條件：

- 市場等級 C 升回 B 或 A。
- 出現新主流族群，成交量集中。
- 台積電轉強並帶動市場。
- 候選股出現洗盤轉強。
- trigger_event = MARKET_UPGRADE 或 REOPEN_READY。

## 時間衰減 Time Decay

- EARLY：09:00-10:00，可正常交易，A/B 都可觀察，可 ACTIVE 監控。
- MID：10:00-10:30，無持倉時 A 可 ACTIVE，B 降為 WATCH，C 關閉。
- LATE：10:30 後，若無持倉、無明確進場條件且 market_grade 不等於 A，強制 decision = REST、hourly_gate = OFF_HARD、monitor_mode = OFF、decision_lock = LOCKED。

## Cooldown 通知冷卻

- cooldown_minutes 預設 10。
- 若 trigger_event 與上一輪相同，且距離 last_event_time 小於 cooldown_minutes，should_notify = false。
- 事件升級可突破冷卻，例如 WATCH 到 ENTRY_READY、ENTRY_READY 到 BREAKOUT、BREAKOUT 到 TAKE_PROFIT / STOP_LOSS。
- MARKET_DOWNGRADE 永遠優先通知。

## 優先級

決策優先順序：

1. decision_lock
2. 時間衰減
3. market_grade
4. trigger_event
5. 個股條件

若 decision_lock = LOCKED，無論個股條件如何都不可進場。

若時間超過 10:30 且 market_grade 不是 A，無持倉時不得進場。

## 市場等級

- A = 主升盤：台積電有明確趨勢並帶動市場、主流族群一致、強勢股續強、有洗盤後轉強，不是單一個股表演。
- B = 強勢震盪：市場仍有強勢股，但主流分歧，有些續強、有些轉弱，接近日高但未有效創高。可觀察，不積極追價。
- C = 震盪 / 出貨盤：台積電偏弱或不帶動、主流不一致、強勢股轉弱、接近日高不創高、爆量開高走低、假突破、已過主升段。符合任兩項優先判 C。

## 行情階段

只能選一個：

- 開盤洗盤期
- 主升發動期
- 高檔震盪期
- 出貨 / 鈍化期

## 決策

- ENTER：market_grade = A，且已有明確候選接近進場條件，或有持倉需積極管理。
- WATCH：market_grade = B，或仍有候選股值得盯，但尚未到進場點。
- REST：market_grade = C，或整體無明確機會，或已過主升段且無新轉強訊號。

## 監控模式

- OFF：market_grade = C、decision = REST、無持倉且無候選股、已過主升段且無轉強、或 11:00 後無持倉且無明確新機會。
- WATCH：market_grade = B，有 1-3 檔候選可觀察，等待回測不破、洗盤轉強或第二段量增；有持倉但暫不需積極處理也可 WATCH。
- ACTIVE：market_grade = A 且候選接近進場，或已有持倉接近停損、停利、加減碼條件，或剛出現明確轉強。

## should_notify 規則

目標是避免每 5 分鐘噪音通知。

- monitor_mode = OFF：should_notify = false。
- monitor_mode = WATCH：只有市場升降級、候選接近進場、候選失效、watchlist 明顯變化、明確洗盤轉強、假突破時，should_notify = true；其餘 false。
- monitor_mode = ACTIVE：只有接近進場、突破關鍵價、跌破停損、到達停利、持倉需管理、或市場結構轉強/轉弱時，should_notify = true；價格小幅波動 false。

## trigger_event

每輪只能選最重要的一個事件：

- NONE：沒有新事件，不值得通知。
- MARKET_UPGRADE：市場由弱轉強。
- MARKET_DOWNGRADE：市場由可做轉不可做。
- ENTRY_READY：候選股接近明確進場條件。
- BREAKOUT：突破關鍵價，可積極追蹤。
- PULLBACK_HOLD：回測不破，可續看。
- STOP_LOSS：跌破停損。
- TAKE_PROFIT：到達停利。
- POSITION_MANAGE：已有持倉需管理。
- CANDIDATE_CANCEL：候選失效，應取消觀察。
- WATCHLIST_UPDATE：候選名單有明顯變動。

## 個股內部判斷

每檔候選或持倉都要判斷：

- 是否仍高於開盤
- 是否仍高於昨收
- 是否接近日高
- 是否離高點過遠
- 是否跌破關鍵價
- 是否洗盤後重新站回
- 是否主流族群同步
- 是否屬於假突破

若整體市場不適合交易，即使個股偏強，也不得輕易發高頻通知。

## 時間衰減

- 09:00-10:00：可較積極監控。
- 10:00-11:00：若無持倉，A 才可 ACTIVE，B 通常只保留 WATCH。
- 11:00 後：若無持倉且無新轉強，優先 OFF。

## LINE 通知格式

當 `should_notify = true` 時，才可發送 LINE 行動通知。通知必須結論在最上方，明確告訴 Austin：

- 現在能不能交易
- 監控要 OFF / WATCH / ACTIVE
- 哪些股票只可觀察
- 哪些股票接近進場
- 是否應該休息

若 `should_notify = false`，程式只更新 `monitor-gate-latest.json` 與狀態檔，不發 LINE。

## 現行程式落地

- 腳本：`D:\ai\stock\monitor-tw-stock.ps1`
- 狀態檔：`D:\ai\stock\intraday-state.json`
- 本輪閘門結果：`D:\ai\stock\monitor-gate-latest.json`
- 排程：`AustinTaiwanStockMonitor`

程式流程：

1. 抓即時報價與 watchlist。
2. 更新每檔狀態、行動與前次價格。
3. 判斷 market_grade、market_phase、decision、monitor_mode。
4. 比對上一輪 `last_monitor_gate`，決定 trigger_event。
5. 產生 `monitor-gate-latest.json`。
6. 若 should_notify = false，排程安靜結束。
7. 若 should_notify = true，才發 LINE。
