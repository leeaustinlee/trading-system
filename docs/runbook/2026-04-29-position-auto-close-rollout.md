# PositionReview EXIT 自動平倉 — 5 日 shadow 上線 SOP

P0.3 任務：`PositionReviewExitAutoCloseHandler` (在 `PositionReviewService.maybeAutoClosePosition`)
把過去 30 天 `position_review_log` 累積的 160+ 筆 `decision_status='EXIT'` 訊號真的接出口。

為了避免一上線就誤平真倉，第一階段只動 `paper_trade`（shadow window），觀察 5 個交易日後再切真倉。

---

## 起算日

- 上線日（commit 進 master）：2026-04-29（週三）
- shadow window 結束：**2026-05-05（週二）** — 起算日後第 5 個交易日
  - 04/29 (三)、04/30 (四)、05/02 (五)、05/05 (一)、05/06 (二) ← 算 5 天
  - 若有國定假日順延

## 預設 Feature Flag

| key | 預設 | 說明 |
|---|---|---|
| `position.review.auto_close.enabled` | `TRUE` | kill switch；`FALSE` 完全停用 |
| `position.review.auto_close.paper_only` | `TRUE` | shadow mode；`TRUE` 只動 paper_trade，不動真倉 |

兩個 flag 都不寫進 `ScoreConfigService.java` DEFAULTS，由
`scoreConfigService.getBoolean(key, defaultValue)` 直接讀，flag 缺席走預設。

## Shadow window 期間每日要做的事

1. 檢查 LINE：`[Auto-close shadow] symbol XXXX would have closed real position; paper recorded`
2. 檢查 paper_trade 是否有對應 `exit_reason='POSITION_REVIEW_EXIT'` 的 CLOSED row：
   ```sql
   SELECT id, symbol, entry_date, exit_date, entry_price, exit_price,
          simulated_exit_price, pnl_pct, exit_reason
   FROM paper_trade
   WHERE exit_reason='POSITION_REVIEW_EXIT'
     AND exit_date >= '2026-04-29'
   ORDER BY exit_date DESC, id DESC;
   ```
3. 同步檢查真倉是否仍 OPEN（shadow 不該動真倉）：
   ```sql
   SELECT id, symbol, status, review_status, last_reviewed_at
   FROM position
   WHERE status='OPEN' AND review_status='EXIT';
   ```
   如果真倉的 review_status='EXIT' 但仍 OPEN，是預期行為（shadow mode）。

## 第 5 日（2026-05-05）切真倉檢查點

執行 KPI 查詢：

```sql
SELECT
  COUNT(*)                              AS closes,
  SUM(CASE WHEN pnl_pct > 0 THEN 1 ELSE 0 END) AS wins,
  AVG(pnl_pct)                          AS avg_pnl_pct,
  MIN(pnl_pct)                          AS worst,
  MAX(pnl_pct)                          AS best
FROM paper_trade
WHERE exit_reason='POSITION_REVIEW_EXIT'
  AND exit_date >= '2026-04-29';
```

切真倉條件（兩條件 AND）：

- `wins / closes >= 0.50`
- `closes >= 5`

### 條件達標 → 切真倉

```sql
-- 寫一筆 score_config row 把 paper_only 切 false
-- (若還沒這個 key，INSERT 即可；若已經有 row 但是 TRUE，UPDATE)

INSERT INTO score_config (config_key, config_value, description, updated_at)
VALUES (
  'position.review.auto_close.paper_only',
  'false',
  'P0.3 shadow window 結束 (2026-05-05)，paper 勝率達標切真倉',
  NOW()
)
ON DUPLICATE KEY UPDATE
  config_value = 'false',
  description  = 'P0.3 shadow window 結束 (2026-05-05)，paper 勝率達標切真倉',
  updated_at   = NOW();
```

切後 LINE 文案會從 `[Auto-close shadow]` 變成 `[Auto-close OK]` / `[Auto-close FAIL]`。

### 條件未達標 → 延長 shadow 或檢視邏輯

兩種處置：

1. **延長 shadow window 5 天** — 不動 flag，繼續觀察到 2026-05-12。
2. **找出 paper close 的失敗模式** — 例如：
   - `exit_price` 抓錯（live quote 在盤外時可能拿不到）
   - `pnl_pct` 為負且 < -8%（代表 review EXIT 訊號時機太晚）
   - 同一 symbol 反覆 OPEN→CLOSE→OPEN（dedupe gate 沒擋住）

修補後再延長一次 shadow，直到達標。

## 緊急回滾

如果 shadow 期間發現 paper 平倉行為異常（例如價格抓錯），立刻：

```sql
UPDATE score_config
SET config_value = 'false',
    description  = 'P0.3 緊急停用 (描述問題)',
    updated_at   = NOW()
WHERE config_key = 'position.review.auto_close.enabled';
```

`enabled=false` 時 `maybeAutoClosePosition` 會在最開頭 return，啥都不動。

## 切真倉後 1 週的監控

- 真倉 CLOSED 是否都有對應的 `exit_reason='POSITION_REVIEW_EXIT'`：
  ```sql
  SELECT id, symbol, closed_at, close_price, exit_reason, realized_pnl
  FROM position
  WHERE exit_reason='POSITION_REVIEW_EXIT'
    AND closed_at >= '2026-05-05'
  ORDER BY closed_at DESC;
  ```
- 真倉 ledger 是否正確（`SELL_CLOSE` + `FEE` + `TAX`）— 由 `PositionService.close` 自動處理，不需手動驗。

## Transition gate 設計回顧

`maybeAutoClosePosition` 沿用 `maybeSendExitAlert` 昨天剛上線的 dedupe pattern：

```java
String prevStatus = pos.getReviewStatus();
if ("EXIT".equalsIgnoreCase(prevStatus)) return;
```

5 分鐘 monitor 一檔 EXIT 一天會跑 ~54 次；不 dedupe 會反覆寫 paper_trade、反覆送 LINE。
盤中 monitor 在每輪結束會把 `pos.review_status` 更新成本輪 status，下一輪就會被 gate 擋下。

`maybeAutoClosePosition` 必須在 `pos.setReviewStatus(decision.status().name())` **之前**呼叫，
否則 gate 會誤判（curr 的 reviewStatus 已經是 EXIT，prev 也變 EXIT）。

### NEEDS_FIX (P0.3 reviewer 2026-04-29) — `prev=null` 首次審查誤觸發

**已修復於 commit `ea27fb9`（branch `fix/p03-auto-close-prev-null`）。**

原 gate 只擋 `prev == "EXIT"`；當 `prev == null`（首次審查、`last_reviewed_at IS NULL`）時，
`"EXIT".equalsIgnoreCase(null) == false`，gate 會誤判為「狀態轉換」→ 首次審查就觸發 auto-close。
shadow（`paper_only=true`）期間無害，但 5/5 把 `paper.auto_close.live_mode.enabled` 切真倉那一刻，
**所有現存 `review_status IS NULL` 的 OPEN position 會被一次刷掉**（race scenario：首次審查若拿到
盤外 / stale 報價得到 EXIT，下一輪可能就回 HOLD，但持倉已被平掉）。

修法（Option A：明確跳過 prev=null）：

```java
String prevStatus = pos.getReviewStatus();
if (prevStatus == null) {
    log.info("[PositionReview] auto-close skipped (first review, prev=null) symbol={}", pos.getSymbol());
    return;  // 首次審查不平倉；review 主流程仍會 setReviewStatus，下一輪才有資格觸發
}
if ("EXIT".equalsIgnoreCase(prevStatus)) {
    log.debug("[PositionReview] auto-close skipped (already EXIT) symbol={}", pos.getSymbol());
    return;
}
```

`maybeSendExitAlert` 不動：首次審查就 EXIT 仍要送 LINE alert，使用者要立刻知道。
`PositionDecisionEngine` 不動，決策邏輯不變，只改 transition gate。

5 個新測試 case 在 `PositionReviewServiceAutoCloseTransitionTests`：
1. `prev=null + curr=EXIT` → **不**觸發
2. `prev=HOLD + curr=EXIT` → 觸發
3. `prev=EXIT + curr=EXIT` → 不觸發（dedupe）
4. `prev=null + curr=HOLD` → 不觸發
5. `prev=WEAKEN + curr=EXIT` → 觸發

切真倉 SQL（5/5 之後執行）已可安全執行：所有現存 OPEN position 在切之前至少跑過一輪 review，
`review_status` 已不再是 NULL，gate 會正常 dedupe；新建的 position 首次審查也只會記錄 review_status，
不會誤平倉。
