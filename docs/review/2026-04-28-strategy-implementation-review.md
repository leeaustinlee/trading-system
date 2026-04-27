# 策略實作審查 2026-04-28

審查範圍：`125da34..e98e9d2`（5 commits / 4 feature merges + 1 DEFAULTS consolidation）。
審查者：獨立 senior reviewer（無對話歷史，純看 diff + main 測試）。

---

## A. Verdict

| Item | Status |
|---|---|
| Overall | **FIX_BEFORE_PUSH** |
| Test green (unit) | 644/644 ✓ （新增 42 全綠；97 個錯誤皆為 pre-existing infra `*IntegrationTests`/`*LiveTest` MySQL 未連線，與本 diff 無關） |
| BLOCKERs | **2** |
| NEEDS_FIX | **5** |

**結論**：P0.1 純邏輯（reweight + thresholds）品質良好可以推。但 P0.2 的兩件事——EXIT alert 沒有 dedupe、ThemeShadow 報表未升級——一旦上 production 立刻會踩到。建議先把這 2 個 BLOCKER 修掉再 push，或在 push 同時把 `position.review.exit_alert.enabled` DB 預設關成 `false` 直到加上 dedupe。

---

## B. Per-change scoring

| 變更 | (a) 正確性 | (b) 副作用 | (c) 測試覆蓋 | (d) 風險 | (e) 可回退性 |
|---|---|---|---|---|---|
| **P0.1-thresholds**（8.8/8.2/7.4 → 8.2/7.5/6.5；rr 鬆綁） | OK | NEEDS_FIX（VetoEngine fallback 未同步） | OK | OK | OK |
| **P0.1-AI-reweight**（claude/codex=3.0 → 權重轉 Java） | OK | OK | OK | NEEDS_FIX（弱 java 也可能進 ENTER） | OK |
| **P0.2-theme-shadow**（write-on-every-comparison + 5 新 enum） | OK | **BLOCKER**（舊 ReportService/LineSummary 取數讀錯欄位） | OK | NEEDS_FIX（topConflicts 把 SAME_DECISION_* 當衝突） | OK |
| **P0.2-paper-trade**（ENTER → recordEntry，公開 API） | OK | NEEDS_FIX（recordEntry 未實際接到 ENTER 流程；onFinalDecisionPersisted 路徑無單元測試） | OK | OK | OK |
| **P0.2-exit-alert**（review=EXIT 送 LINE） | OK | **BLOCKER**（5-min monitor 每 tick 都呼叫 → 同一 EXIT 訊號每 5 分鐘重送 LINE） | OK | **BLOCKER** | OK（DB flag 可關） |

---

## C. BLOCKERs

### C.1 — EXIT alert 在 5 分鐘監控每 tick 都送 LINE，沒有 dedupe

- **檔案**：`src/main/java/com/austin/trading/service/PositionReviewService.java:122-125`，呼叫者
  `src/main/java/com/austin/trading/scheduler/FiveMinuteMonitorJob.java:129`。
- **問題**：`reviewAllOpenPositions("INTRADAY")` 每 5 分鐘從 FiveMinuteMonitorJob 觸發，迴圈內會對每檔 EXIT 持倉呼叫 `maybeSendExitAlert()` → `LineSender.send()`。`LineSender` 自身只做 429 retry，**不做 dedupe**。`NotificationFacade` 的 dedupe（如有）也被新路徑繞開。
- **為什麼是 BLOCKER**：盤中（09:00–13:30）約有 54 個 5-min tick。一檔停損後若用戶當下沒處理，會被連送 ~54 通 LINE，而且 v2 規則明文：「5 分鐘監控只在有訊號時才通知」。這直接違反 LINE 通知原則，也會把 LINE quota 燒爆，順便讓真正重要訊號被淹沒。本 PR 同時擴大 ENTER（B 門檻 6.5）增加 OPEN 持倉數，會放大此問題。
- **建議修法（任一即可）**：
  1. 在 `PositionReviewService.maybeSendExitAlert` 比對 `pos.getReviewStatus()`（前一輪狀態）；只有 `prev != EXIT && curr == EXIT` 的 transition 才送 LINE（最簡單，1 行 if）。
  2. 在 `PositionEntity` 增 `lastExitAlertAt`，>=N 分鐘才再送（若希望週期 reminder）。
  3. 在 `FiveMinuteMonitorJob` 那層改用既有 `notifyPositionAlert`（已 dedup），把這個新 alert 整合進去而不另開一條路。
  4. 暫時把 DEFAULTS `position.review.exit_alert.enabled` 改為 `false` 並以 SQL 在 prod 開啟；至少 P0.2 還沒被線上踩到。

### C.2 — Theme Shadow 新 enum 沒人讀；ReportService / LineSummary 全部會顯示 0

- **檔案**：
  - 寫入端：`src/main/java/com/austin/trading/service/ThemeShadowModeService.java:170-207`（新 5 類 enum）
  - 讀取端 1：`src/main/java/com/austin/trading/service/ThemeShadowReportService.java:289-294`
    （只 `getOrDefault(SAME_BUY/SAME_WAIT/.../CONFLICT_REVIEW_REQUIRED, 0)`）
  - 讀取端 2：`src/main/java/com/austin/trading/service/ThemeLineSummaryService.java:58-63`（同樣只讀舊 6 類）
  - 讀取端 3：`src/main/java/com/austin/trading/service/ThemeShadowReportService.java:175`（topConflicts 過濾條件）
- **問題**：`ThemeShadowModeService.classify()` 改寫後，**所有** 落 log 的列都標記為新 5 類。但 `ThemeShadowDailyReportEntity` 的欄位（`sameBuyCount`/`sameWaitCount`/`legacyBuyThemeBlockCount`/...）只對應舊 6 類。聚合迴圈 `countByType` 用 `parseOrConflict()` 會正確得到新 enum 物件，但 `upsertDailyReport()` 只塞舊 enum slot，所以 6 個欄位全部會是 0。盤後 LineSummary 顯示「同買 0 / 同停 0 / L買T擋 0 …」，看起來像 shadow mode 沒寫資料。
- **附加問題**：`topConflicts()` 第 175 行 `t != SAME_BUY && t != SAME_WAIT` 對新 enum 都成立，所以連 `SAME_DECISION_SAME_SCORE`（明明是「相同沒衝突」）也會被當成 conflict 列入 top。
- **為什麼是 BLOCKER**：本次升級的整個目的是「每筆 candidate × theme 比對都落 log，盤後好分析」。但盤後的彙總/通知產品從新 row 拿不到資訊，等同「寫了不讀 = 沒寫」，整個 P0.2 theme shadow 改動的價值幾乎歸零。
- **建議修法**：擇一
  1. **最完整**：給 `ThemeShadowDailyReportEntity` 加 5 個新欄位（`sameDecisionSameScoreCount` …）+ migration；`upsertDailyReport()` / `LineSummaryService` 同步使用新欄位（舊欄位保留或 deprecated）。
  2. **過渡**：在 `upsertDailyReport()` 把 `SAME_DECISION_SAME_SCORE` + `SAME_DECISION_SCORE_DIFF` map 到 `SAME_BUY`/`SAME_WAIT`（依 legacyDecision），`V2_VETO_LEGACY_PASS` map 到 `LEGACY_BUY_THEME_BLOCK`，`LEGACY_VETO_V2_PASS` map 到 `LEGACY_WAIT_THEME_BUY`，`DIFF_DECISION` map 到 `CONFLICT_REVIEW_REQUIRED`。可保住舊欄位語意。同步修 `topConflicts` 過濾條件。
  3. 至少要改 `ThemeLineSummaryService` 顯示新 5 類，避免 LINE 看起來像「全 0」。

---

## D. NEEDS_FIX（post-push 可接受）

### D.1 — VetoEngine fallback 與新 DEFAULTS 不一致（rr）
- **檔案**：`src/main/java/com/austin/trading/engine/VetoEngine.java:165-166`
- 仍用 `new BigDecimal("2.2")`/`new BigDecimal("2.5")` 作為 `scoring.rr_min_grade_a`/`rr_min_grade_b` 的 fallback，但 DB DEFAULTS 已改為 `2.0`/`1.8`。正常啟動 DB 會載入 DEFAULTS，影響有限；但 unit-test / 缺 row 場景會走 fallback，與意圖不一致。
- 修法：把兩個 fallback 改 `new BigDecimal("2.0")` / `new BigDecimal("1.8")`，與 ScoreConfigService DEFAULTS、FinalDecisionEngine、FinalDecisionService.computeBucket 對齊。

### D.2 — StrategyRecommendationEngine display fallback 仍是 8.8
- **檔案**：`src/main/java/com/austin/trading/engine/StrategyRecommendationEngine.java:204`
- `currentConfig().getOrDefault("scoring.grade_ap_min", "8.8")` 是顯示給人看的「目前值」，會在勝率低時建議「提高進場門檻」並把 8.8 印出來，但實際 default 已是 8.2。雖只影響推薦訊息文字，但會造成「目前 8.8 → 建議調更高」的誤導。
- 修法：改 default 字串為 `"8.2"`（或讀真實值前不要寫 fallback）。

### D.3 — `recordEntry` 公開 API 沒有 production 呼叫者
- **檔案**：`src/main/java/com/austin/trading/service/PaperTradeService.java:201`
- commit message 說「P0.2 paper_trade pipeline for ENTER」，實作上是新增了 `recordEntry()`，**但沒有任何上游呼叫它**。實際 ENTER → paper_trade 仍走原本的 `onFinalDecisionPersisted` event listener。這個改動主要等於「event listener gate 改用 DB flag + 多了一個 manual API」。
- 風險：`recordEntry` 與 `openOne` 的 `entryDate` 語意不同（`LocalDate.now()` vs `event.tradingDate()`），未來呼叫者搞混時會在跨日邊界寫錯日期。
- 修法：要嘛把 `recordEntry` 改 `(symbol, entryPrice, ..., LocalDate entryDate)` 強迫上游傳；要嘛在 javadoc 大字寫「entryDate 永遠 = LocalDate.now()，跨日場景請改用 onFinalDecisionPersisted」。
- **測試補洞**：完全沒有單元測試覆蓋 `onFinalDecisionPersisted`（PR 內也沒新增）。建議補一個 mock `FinalDecisionPersistedEvent` 走完整路徑的測試，確保 DB-flag 切換有效。

### D.4 — AI reweight：javaScore null + 兩 AI default 時 aiWeighted = 0
- **檔案**：`src/main/java/com/austin/trading/service/FinalDecisionService.java:156-178`
- `applyAiDefaultReweight` 在 BOTH default 時返回 `(1.0, 0, 0)`，若 javaScore=null，`computeWeightedAiScoreWithOverride` 會回 `BigDecimal.ZERO`。原 `WeightedScoringEngine.computeAiWeightedScore(null,3,3)` 也會回 0（兩 default 視為唯一參與軸 → 3.0…等等，其實會回 3.0，而非 0）。差別不大但行為已偏移。
- 修法：在 `applyAiDefaultReweight` 入口加 `if (javaScore == null) return new AiWeightOverride(javaW, claudeW, codexW, "JAVA_NULL_NO_REWEIGHT");`，把 null java 場景排除在 reweight 之外。

### D.5 — AI reweight 風險：弱 java（6.7）在兩 AI default 時也能變 ENTER
- **影響**：B 門檻 6.5 + reweight 後 `aiWeighted = javaScore` → java=6.6 也能 B_TRIAL。過去 0.4*6.6 + 0.35*3 + 0.25*3 = 4.14 會被擋；現在 6.6 直接過 B 門檻。**這正是設計目的**（避免被 default 稀釋），但同時也代表「Claude/Codex 沒研究」的低分股最容易溜進 B_TRIAL（試單）。
- 建議：在 reweight 觸發時把 `payload_json.scoring_trace` 已紀錄 `ai_weight_override_reason`（已實作 ✓），但建議在 ScoringWorkflow / Codex review 端加一個盤後監控：count of `ai_weight_override_reason != "NO_OVERRIDE"` 中最終 ENTER 的數量；過閾值 alert。本 PR 不需改 code，只是 follow-up 觀察。

---

## E. Strengths worth noting

1. **Feature flag 三件套都到位**（`final_decision.ai_default_reweight.enabled`、`trading.paper_mode.enabled`、`position.review.exit_alert.enabled`），rollback 容易；且 `e98e9d2` 把它們集中放進 ScoreConfigService DEFAULTS，符合「DB-side single source of truth」。
2. **AI reweight 純邏輯化、static method、有 record 類型 trace**：`AiWeightOverride.reason()` 寫進 `payload_json.scoring_trace.ai_weight_override_reason`，盤後可 SQL 反推；測試 `FinalDecisionAiReweightTests` 7 個 case 把 epsilon 邊界、null、BOTH/單邊/none 全部覆蓋，是這次 5 個變更中品質最好的一塊。
3. **Threshold 邊界測試 `FinalDecisionGradeBoundaryTests`** 用 6 個 ±0.01 案例（8.20/8.19、7.50/7.49、6.50/6.49）精準驗證每個門檻的兩側行為，並刻意 `mainStream=false` 排除 +0.3 boost 干擾。是教科書級 boundary test。
4. **PaperTradeService 用 `ObjectProvider<ScoreConfigService>` 解循環依賴** + `getIfAvailable()` fallback 到 `staticEnabled`，這個 pattern 在 Spring 後加依賴的場景很乾淨；測試也確實 cover idempotency / closed→reopen / blank symbol / non-positive price。
5. **Theme shadow 新分類器邏輯本身寫得很清晰**：`scoreDiff <= 0.1` 用 `BigDecimal.compareTo` 而非 `equals`，null score 視為 0 已測試（`nullScoreDiff_treatedAsZero`），edge case 周到。

---

## F. Strategy-level concern（≤ 3）

1. **EXIT alert spam（重複 BLOCKER）**：本 PR 同時放寬 ENTER 門檻（B=6.5）讓持倉變多 + 加上 EXIT alert 沒 dedupe，兩件事相乘下來 LINE 會炸。即使 BLOCKER C.1 修掉，仍建議在 prod 灰度先把 `position.review.exit_alert.enabled = false`，觀察一週再開。

2. **AI reweight 把弱 java 推進 B_TRIAL**：reweight + 6.5 門檻組合下，「Claude/Codex 都沒研究 + java 6.6」就能進 B_TRIAL 試單，這是過去 7.4 門檻 + 三軸加權擋下的情境。短期影響不大（試單 0.5x），但建議加一條 follow-up 規則：`ai_confidence_mode=JAVA_ONLY` AND `bucket=B_TRIAL` 時自動 -0.5 或加額外 codex review gate；否則本 PR 等於把「沒人 AI 研究」的候選股放進 ENTER 池。

3. **Theme shadow 報表系統未升級（重複 BLOCKER）**：write-on-every-comparison 本意是用 30 天樣本驗證 v2 vs legacy 一致性，但盤後 daily report / LINE summary 是真正的決策入口。BLOCKER C.2 不修，等於 30 天累積 1000+ 列資料卻被總表顯示「全 0」，反而會誤導判斷「v2 啟動有效」。這個風險不在 code 而在「上游決策者讀錯數據」。
