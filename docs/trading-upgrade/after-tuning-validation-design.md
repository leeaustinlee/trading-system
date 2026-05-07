# 台股 AI 交易系統｜調參效果驗證系統（After Tracking）設計

> 版本：after-tuning-validation-design v1（2026-05-07）
> 範圍：在 `self-tuning-design.md` 已奠定的「PENDING 建議 → 人工 approve → 寫入 score_config → rollback 可用」閉環之上，補上「**調參套用後，自動於 T+5 / T+10 / T+20 三個窗口比較 before/after 指標、產出 SUCCESS / FAIL / INCONCLUSIVE / INSUFFICIENT_DATA 判定，並把 rollback 建議丟給 Austin 人工裁決**」這條尾段流程。
> 定位：**統計驗證 + 人工審核**。本系統 **不下單**、**不自動 rollback**、**不自動 apply**、**不依單日結果評估**、**樣本不足不出口判定**、**所有 rollback 建議皆需 Austin 明確 KEEP / ROLLBACK 才會生效**。
> 與既有文件關係：本文件**不取代** `self-tuning-design.md` §8.x（原本只在 §11 簡單提到 before/after），而是把它展開成完整可實作的尾段子模組；資料流接 `strategy_tuning_history` 之後。

---

## 0. 設計總原則（Hard Rules，破壞任一條即視為設計失敗）

1. **不自動 rollback**：本系統只能輸出「建議 ROLLBACK」的 evaluation result 與 dashboard banner，唯一寫回 `score_config` 的路徑仍是 `StrategyTuningService.rollbackRecommendation(id, userId)`，且該方法已要求 `X-Austin-Confirm: true`。
2. **不自動 apply**：after tracking 的判定即使 SUCCESS 也不會「自動再加碼套用」，不存在「擴大套用」開關；SUCCESS 只是讓 Austin 安心 KEEP，不會觸發任何寫入。
3. **不准沒有 snapshot 就評估**：每筆 `APPLIED` 紀錄必須在 `tuning_apply_snapshot` 中先持久化 before window 的 5 項指標；找不到 snapshot 的 history 一律標 `INSUFFICIENT_DATA` 並寫入 `evaluation_skip_reason = NO_BEFORE_SNAPSHOT`，**禁止**用「即時重算」方式補 snapshot。
4. **不准單日數據判斷**：所有 evaluation 必須以 ≥ 5 個交易日為最短窗口；T+1 / T+3 不視為有效窗口，僅作 dashboard 展示。
5. **不准樣本不足判 SUCCESS**：每個窗口都必須先通過 `sample_after >= MIN_SAMPLE_AFTER`（預設 10）且 `sample_before >= MIN_SAMPLE_BEFORE`（預設 10）才可進入 success/fail 判定；任一不足 → `INSUFFICIENT_DATA`。**aggressive 方向**（放寬 gate / 提高倉位 / 降停損）的最低樣本門檻自動 +5。
6. **所有評估必須可追溯**：每筆 evaluation 必須持久化 raw query、sample id list、before/after 指標、判定理由與 `evaluator_version`；任何結論皆可由相同 `evaluator_version` 重跑復現。
7. **不可在 LINE 把 PENDING 建議或 INCONCLUSIVE 結果說成「已驗證」**：dashboard / LINE 只能用「✅ 已驗證提升」/「⚠️ 建議回滾」/「⏳ 樣本累積中」/「— 不顯著」等中性描述。
8. **時間口徑統一**：所有 T+N 皆以「交易日」計，跳過週末與 TWSE 休市；資料來源為 `MarketIndexDailyEntity` / `holiday_calendar`。
9. **與 self-tuning Engine 解耦**：本系統消費 `strategy_tuning_history` + tracking 表，**不**修改 `StrategyTuningEngine` 的 rule 結果；engine 不感知 after tracking 結論。
10. **rollback 建議的有效期**：每筆 `BUILD ROLLBACK_SUGGESTED` 預設 14 個自然日有效；過期自動 `EXPIRED_ROLLBACK_SUGGESTION`，避免久遠的失敗結論卡住 dashboard。

---

## 1. Before / After 指標定義

### 1.1 五項核心指標（與 `self-tuning-design.md §7.1` 統計口徑對齊）

| 指標 | 計算 | 資料來源 |
|---|---|---|
| `winRate` | `SUM(t{N}_close_pct > 0) / sample_n` | `candidate_forward_tracking` |
| `avgReturn` | `AVG(t{N}_close_return_pct)` | `candidate_forward_tracking` |
| `avgMFE` | `AVG(mfe_pct)`（最大有利偏移） | `candidate_forward_tracking` |
| `avgMAE` | `AVG(mae_pct)`（最大不利偏移；負值代表回檔） | `candidate_forward_tracking` |
| `avgRelativeReturn` | `AVG(t{N}_close_return_pct - benchmark_t{N}_return_pct)` | `candidate_forward_tracking` × `MarketIndexDailyEntity` (0050) |

> `t{N}` 中的 N 由評估窗口決定：T+5 取 `t5_close_return_pct`，T+10 取 `t10_close_return_pct`，T+20 由 `extended_forward_tracking`（若無則由 `candidate_forward_tracking` 延伸欄位提供，欄位不足時直接 `INSUFFICIENT_DATA`，不准退化為 T+10）。

### 1.2 Before 視窗

- `before_window = [appliedAt - lookbackBeforeDays trading days, appliedAt - 1 trading day]`
- `lookbackBeforeDays` 預設等於對應評估窗口長度（T+5 取 5 個交易日 ×4 = 20，T+10 取 40，T+20 取 60）；意圖是讓 before 樣本與 after 樣本量級接近。
- 必須**篩選**：`config_key 受影響的 strategy bucket`（見 §1.4）；不得把全市場樣本混進來淡化結果。

### 1.3 After 視窗

- `after_window_t5  = [appliedAt + 1 trading day, appliedAt + 5 trading days]`
- `after_window_t10 = [appliedAt + 1 trading day, appliedAt + 10 trading days]`
- `after_window_t20 = [appliedAt + 1 trading day, appliedAt + 20 trading days]`
- 注意：**T+10 與 T+20 的 after window 含 T+5 樣本**（疊套關係），不是互斥；目的是用更長窗口檢查趨勢是否延續。

### 1.4 Bucket Filter（套用範圍對應）

不同 `config_key` 影響不同 bucket，必須在指標查詢時套對應過濾條件，否則 before/after 不可比：

| config_key 前綴 | bucket filter | 範例 |
|---|---|---|
| `breakout.*` | `primary_strategy = 'BREAKOUT'` | `breakout.min_score`、`breakout.near_high_allowed` |
| `pullback.*` | `primary_strategy = 'PULLBACK'` | `pullback.min_score` |
| `continuation.*` | `primary_strategy = 'CONTINUATION'` | `continuation.rr_min` |
| `scoring.enter_min_score` | `final_decision = 'ENTER'` | 全策略 ENTER 樣本 |
| `scoring.watch_min_score` | `final_decision IN ('WATCH','ENTER')` | |
| `gate.*` | 對應 gateName 命中之 reject 樣本 + 該 gate 對應 strategy bucket 的 ENTER 樣本 | `gate.near_day_high_reject_threshold` |
| `risk.*` | `final_decision = 'ENTER'`，且額外比較 `hit_stop_rate` | `risk.stop_loss_pct` |

> bucket filter 必須在 `tuning_apply_snapshot` 寫入時就**綁死**，不能事後改動；若改動視為「重新評估」，要寫一筆新的 evaluation result，不可覆寫舊的。

---

## 2. 評估窗口

| 窗口 | 評估時機 | 必要條件 | 用途 |
|---|---|---|---|
| **T+5** | `appliedAt + 5 trading days + 1 trading day buffer` 之後 | `sample_after_t5 >= MIN_SAMPLE_AFTER (10)` | 早期警示；MAE 顯著惡化即可建議 rollback |
| **T+10** | `appliedAt + 10 trading days + 1 trading day buffer` 之後 | `sample_after_t10 >= MIN_SAMPLE_AFTER (10)` | 主要 SUCCESS / FAIL 判定窗 |
| **T+20** | `appliedAt + 20 trading days + 1 trading day buffer` 之後 | `sample_after_t20 >= MIN_SAMPLE_AFTER (10)` | 最終結論；T+20 樣本足且方向一致才算「驗證完成」 |

**Buffer 的意義**：T+5 評估其實在第 6 個交易日早上跑（讓 T+5 收盤資料完整回填），避免拿到當天午盤資料就下判定。

**任一窗口若 sample 不足**：標 `INSUFFICIENT_DATA`，但**不會跳過後續窗口**；T+5 不足 ≠ T+10 也不足，三個窗口分別評估。

**T+20 完成前不可標 SUCCESS_FINAL**：T+5 / T+10 SUCCESS 只是中間結論（`SUCCESS_INTERIM`），最終 `SUCCESS_FINAL` 要等 T+20 也通過；UI 必須區分這兩個狀態。

---

## 3. 成功判定規則

### 3.1 Rule 1：正向（SUCCESS）

對單一窗口 W ∈ {T+5, T+10, T+20} 同時滿足：

```
sample_before_W >= MIN_SAMPLE_BEFORE (10)
sample_after_W  >= MIN_SAMPLE_AFTER  (10)
delta_avgReturn  = avgReturn_after  - avgReturn_before  >= +1.0pp   -- 1 個百分點
delta_winRate    = winRate_after    - winRate_before    >= +5.0pp   -- 5 個百分點
delta_avgMAE     = avgMAE_after     - avgMAE_before     >= -1.0pp   -- MAE 是負值，惡化 = 變更負；要求變化 >= -1pp 代表 MAE 沒明顯惡化（容忍 1pp）
```

→ 標 `SUCCESS`（T+20 視同 `SUCCESS_FINAL`，T+5 / T+10 視為 `SUCCESS_INTERIM`）。

**附加條件**（任一未滿足就降級為 INCONCLUSIVE，避免假陽性）：

- `delta_avgRelativeReturn >= 0`：相對 0050 沒有反向（避免「指標好是因為大盤好」）。
- aggressive 方向建議：另需 `hit_stop_rate_after <= hit_stop_rate_before + 5pp`。

### 3.2 Rule 2：負向（FAIL → 建議 rollback）

對單一窗口 W 滿足下列任一條件即標 `FAIL`：

```
A. delta_avgReturn <= -1.0pp                                     -- 報酬下降
B. delta_winRate   <= -8.0pp                                     -- 勝率明顯下降
C. delta_avgMAE    <= -2.0pp                                     -- MAE 明顯惡化（變更負 2pp）
D. hit_stop_rate_after >= hit_stop_rate_before + 10pp            -- 觸停率暴增
E. delta_avgRelativeReturn <= -2.0pp AND avgReturn_after < 0     -- 跑輸大盤且絕對虧損
```

→ 標 `FAIL`，同時寫入 `rollback_suggested = true`，並把建議理由列在 `evaluation_result.reason`。

**T+5 FAIL 早期警示**：T+5 即觸發 FAIL（特別是 C / D），`rollback_suggested` 立刻寫入；不需等 T+10。

### 3.3 Rule 3：不顯著（INCONCLUSIVE）

不符合 Rule 1 也不符合 Rule 2 的：

```
sample 都足夠
但 |delta_avgReturn| < 1.0pp AND |delta_winRate| < 5.0pp AND MAE 沒明顯動
```

→ 標 `INCONCLUSIVE`；不建議 rollback，但也不算 SUCCESS。

T+5 / T+10 INCONCLUSIVE 要繼續往下個窗口觀察；T+20 仍 INCONCLUSIVE → 標 `INCONCLUSIVE_FINAL`，由 Austin 自行決定是否續抱該調參。

### 3.4 INSUFFICIENT_DATA 明確定義

下列任一即直接標 `INSUFFICIENT_DATA`，**不進**任何 SUCCESS / FAIL / INCONCLUSIVE 判定：

```
1. tuning_apply_snapshot 不存在（NO_BEFORE_SNAPSHOT）
2. sample_before_W < MIN_SAMPLE_BEFORE (10)
3. sample_after_W  < MIN_SAMPLE_AFTER  (10)
4. before/after 任一窗口含 marketGrade='C' 連續 ≥ 3 trading days，且該段樣本佔比 > 40%（市況污染）
5. before/after 任一窗口含「資料品質可疑」row（NULL 比例 > 20%、t{N}_close_pct 缺值 > 30%）
6. config_key 不屬於任何已定義 bucket（unknown bucket）
7. evaluator_version 與 snapshot_version 不一致且未經 migrate
```

→ `evaluation_skip_reason` 必填，列出觸發哪一條（用 enum / code）。

INSUFFICIENT_DATA 的 history 不算「驗證失敗」；UI 顯示為 ⏳ 樣本累積中 / 無法評估，**禁止**呈現為紅燈或建議 rollback。

### 3.5 規則優先序

判定順序（從上到下，遇到滿足者立刻定案）：

```
1. INSUFFICIENT_DATA gate（§3.4）
2. FAIL（§3.2）         -- FAIL 比 SUCCESS 優先：寧可錯殺不可錯放
3. SUCCESS（§3.1）
4. INCONCLUSIVE（§3.3） -- fallback
```

> 為什麼 FAIL 優先 SUCCESS：avgReturn +1.5pp、winRate +6pp 看似 SUCCESS，但若 MAE 惡化 -2.5pp（觸發 FAIL Rule C），仍應視為 FAIL；MAE 惡化代表回檔風險變大，後續可能爆出大虧損，不能因短期均值漂亮就放行。

---

## 4. 評估結果分類

`tuning_evaluation_result.outcome` 的合法值：

| outcome | 意義 | 對應動作 |
|---|---|---|
| `SUCCESS_INTERIM` | T+5 / T+10 達 SUCCESS 條件，等 T+20 確認 | UI 綠（中等亮度）；不發 LINE 結論訊息 |
| `SUCCESS_FINAL` | T+20 也達 SUCCESS | UI 綠（高亮）；可發一封獨立 LINE「✅ 已驗證提升」 |
| `FAIL` | 任一窗口觸發 FAIL Rule | UI 紅；寫 `rollback_suggested = true`；發 LINE「⚠️ 建議回滾」 |
| `INCONCLUSIVE` | 樣本足但變動不顯著（中間窗口） | UI 灰；不發 LINE |
| `INCONCLUSIVE_FINAL` | T+20 仍 INCONCLUSIVE | UI 灰（深）；發 LINE「— 調參效果不顯著，由你決定是否保留」 |
| `INSUFFICIENT_DATA` | §3.4 任一條件觸發 | UI 灰（透明）；不發 LINE；只在 dashboard 顯示等待中 |
| `EXPIRED_ROLLBACK_SUGGESTION` | FAIL 後 14 自然日 Austin 未處理 | UI 黃；rollback 建議自動 expire；history 仍保留供查詢 |

**狀態轉移**：

```
INSUFFICIENT_DATA  ─►  (樣本累積到位)  ─►  SUCCESS_INTERIM / FAIL / INCONCLUSIVE
SUCCESS_INTERIM    ─►  (T+20 確認)     ─►  SUCCESS_FINAL / FAIL / INCONCLUSIVE_FINAL
FAIL               ─►  (Austin ROLLBACK)─► history 多一筆 type=ROLLBACK；evaluation 凍結為 FAIL_ROLLED_BACK
FAIL               ─►  (Austin KEEP)   ─►  evaluation 凍結為 FAIL_KEPT_BY_USER
FAIL               ─►  (14 日無動作)   ─►  EXPIRED_ROLLBACK_SUGGESTION
```

每筆 history 在三個窗口分別寫一筆 evaluation_result（同 history_id 對 3 筆 result，PK = `(history_id, window)`），不是把三窗合成一列；理由是窗口可獨立過期、獨立補樣本。

---

## 5. Human-in-the-loop 流程

```
 ┌─────────────────────────────┐
 │ Austin APPROVE recommendation│
 │ Austin POST /apply           │
 └──────────┬──────────────────┘
            ▼
 ┌──────────────────────────────────────────────┐
 │ StrategyTuningService.applyApprovedRecommendation │
 │  1. 讀 score_config 當前值                     │
 │  2. 寫 strategy_tuning_history (snapshot)     │
 │  3. **新增：寫 tuning_apply_snapshot**        │
 │     - 計算 before window 五項指標 (T+5/T+10/T+20 各一份)
 │     - bucket filter 綁定
 │     - sample id list 持久化
 │  4. ScoreConfigService.update                 │
 └──────────┬──────────────────────────────────┘
            ▼
 ┌──────────────────────────────────────┐
 │ TuningAfterTrackingJob (每日 19:30)    │
 │  - 掃 strategy_tuning_history.applied_at
 │  - 對每筆 history 跑 3 個窗口的評估（如時點到了）
 │  - 寫 tuning_after_metrics + tuning_evaluation_result
 └──────────┬──────────────────────────────┘
            ▼
 ┌──────────────────────────────────────────┐
 │ Dashboard / LINE「Tuning Validation」分頁 │
 │  - 列出每筆 history 的三窗 outcome        │
 │  - FAIL 的進「建議回滾」清單              │
 └──────────┬──────────────────────────────┘
            ▼
 ┌────────────────────────────────────────────────┐
 │ Austin 點選 KEEP / ROLLBACK                     │
 │  - KEEP  → evaluation_result.user_decision = KEEP
 │  - ROLLBACK → 走既有 StrategyTuningService.rollbackRecommendation
 │             → evaluation_result.user_decision = ROLLED_BACK
 └────────────────────────────────────────────────┘
```

**關鍵控制點**：

- **沒有自動 rollback 路徑**：`TuningAfterTrackingJob` 只能寫 `tuning_evaluation_result`、設 `rollback_suggested = true`；唯一寫 `score_config` 的依然是 `StrategyTuningService.rollbackRecommendation(id, userId)`，需 `X-Austin-Confirm: true` header。
- **沒有自動 apply 路徑**：SUCCESS_FINAL 不會觸發任何 config 寫入；UI 也禁止「按一下擴大套用」按鈕。
- **KEEP 是顯式動作**：Austin 必須在 dashboard 點 KEEP，才會把 FAIL 結果壓住；否則 14 自然日後仍會 EXPIRED。
- **idempotency**：`TuningAfterTrackingJob` 對同一個 `(history_id, window)` 只會寫一次 evaluation_result；重跑只會更新 sample_after / outcome（若 outcome 從 INSUFFICIENT 變成定案）。
- **重評**：若 Austin 想讓系統重算（例如 T+5 INSUFFICIENT 後 T+10 樣本到位），不需手動觸發；job 每日 19:30 自動掃所有未 final 的 result。

---

## 6. 安全限制

| # | 限制 | 設計如何保證 |
|---|---|---|
| 1 | **不准自動 rollback** | `TuningAfterTrackingJob` 只能寫 `tuning_evaluation_result`，不持有 `ScoreConfigService` 寫入權限；DI 圖只 `@Autowired` Repository，沒注入 service writer |
| 2 | **不准自動 apply** | 本系統完全沒有 ScoreConfig 寫入路徑；單元測試強制驗證 `TuningEvaluationEngine` 的依賴圖 |
| 3 | **不准沒有 snapshot 就評估** | `tuning_apply_snapshot` 由 `StrategyTuningService.applyApprovedRecommendation` 同事務寫入；`TuningAfterTrackingJob` 找不到 snapshot 直接 `INSUFFICIENT_DATA + NO_BEFORE_SNAPSHOT`；不允許「即時計算 before」當 fallback |
| 4 | **不准樣本不足判 SUCCESS** | `MIN_SAMPLE_BEFORE = 10`、`MIN_SAMPLE_AFTER = 10`；aggressive 方向自動 +5；evaluator 函式入口先過 sample gate，不足直接 return INSUFFICIENT_DATA |
| 5 | **不准單日數據判斷** | T+1 / T+3 在 schema 中標 `display_only = true`，`outcome` 永遠為 NULL；evaluator 拒絕用 < 5 trading days 的窗口 |
| 6 | **所有評估必須可追溯** | `tuning_evaluation_result` 必含：`evaluator_version`（語意化版本號）、`raw_query_sql`、`sample_id_list_json`（before/after 各一個 array）、`metrics_before_json`、`metrics_after_json`、`reason`（文字解釋觸發哪一條 rule） |
| 7 | **不准用「重算 before」覆蓋 snapshot** | `tuning_apply_snapshot` 為唯讀；evaluator 連 update 權限都沒有（schema-level 只 grant SELECT） |
| 8 | **bucket filter 一致** | snapshot 寫入時持久化 `bucket_filter_json`；evaluator 讀 after metrics 時必須使用同一份 filter，不可改寫 |
| 9 | **市況污染保護** | §3.4 條件 4 自動觸發 INSUFFICIENT_DATA |
| 10 | **不可在 LINE 把 PENDING 說成已驗證** | LINE 文案模板 hardcoded：SUCCESS_FINAL → ✅；FAIL → ⚠️；INCONCLUSIVE → —；INSUFFICIENT → ⏳；模板有 unit test |
| 11 | **rollback 建議過期** | `EXPIRED_ROLLBACK_SUGGESTION` 14 自然日；過期後 dashboard 從紅燈淡出，但 history 保留供查詢 |
| 12 | **同一 config_key 短時間內多次 apply** | 若同 key 在 30 trading days 內被 apply 兩次，第二次 apply 會把第一次的 evaluation result 標 `SUPERSEDED`（不繼續評估），避免兩次效果交叉污染；snapshot 各自獨立 |

---

## 7. DB / API / Service / Dashboard / Test 驗收建議

> 與 `self-tuning-design.md §6` 既有命名相容；新增的元件全部加 `Tuning` 前綴避免污染。

### 7.1 DB Schema（新增 migration `V30__tuning_after_tracking.sql`）

```sql
-- 7.1.1 套用瞬間 snapshot：before window 已計算好的 5 項指標 + 樣本 id list
CREATE TABLE IF NOT EXISTS tuning_apply_snapshot (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    history_id          BIGINT NOT NULL,                -- FK strategy_tuning_history.id
    config_key          VARCHAR(100) NOT NULL,
    applied_at          DATETIME    NOT NULL,
    bucket_filter_json  JSON        NOT NULL,           -- 例：{"primary_strategy":"BREAKOUT"}
    window_label        VARCHAR(8)  NOT NULL,           -- T5 / T10 / T20
    lookback_before_days INT        NOT NULL,
    sample_before       INT         NOT NULL,
    sample_id_list_json JSON        NOT NULL,           -- ["20260411-2330","20260412-3293",...]
    win_rate_before        DECIMAL(8,4),
    avg_return_before      DECIMAL(8,4),
    avg_mfe_before         DECIMAL(8,4),
    avg_mae_before         DECIMAL(8,4),
    avg_relative_return_before DECIMAL(8,4),
    snapshot_version    VARCHAR(16) NOT NULL,           -- evaluator 版本鎖
    raw_query_sql       TEXT        NOT NULL,
    created_at          DATETIME    NOT NULL,
    UNIQUE KEY uk_history_window (history_id, window_label),
    INDEX idx_apply_snapshot_history (history_id),
    INDEX idx_apply_snapshot_key (config_key, applied_at),
    CONSTRAINT fk_tas_history FOREIGN KEY (history_id)
        REFERENCES strategy_tuning_history(id)
);

-- 7.1.2 套用後實測指標（每窗口一列；可被覆寫直到 outcome final）
CREATE TABLE IF NOT EXISTS tuning_after_metrics (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    history_id          BIGINT NOT NULL,
    window_label        VARCHAR(8) NOT NULL,            -- T5 / T10 / T20
    measured_at         DATETIME   NOT NULL,
    sample_after        INT        NOT NULL,
    sample_id_list_json JSON       NOT NULL,
    win_rate_after        DECIMAL(8,4),
    avg_return_after      DECIMAL(8,4),
    avg_mfe_after         DECIMAL(8,4),
    avg_mae_after         DECIMAL(8,4),
    avg_relative_return_after DECIMAL(8,4),
    hit_stop_rate_after   DECIMAL(8,4),
    raw_query_sql       TEXT       NOT NULL,
    UNIQUE KEY uk_after_history_window (history_id, window_label),
    INDEX idx_after_metrics_history (history_id),
    CONSTRAINT fk_tam_history FOREIGN KEY (history_id)
        REFERENCES strategy_tuning_history(id)
);

-- 7.1.3 評估結果（每窗口一列，記錄每次評估的最終 outcome）
CREATE TABLE IF NOT EXISTS tuning_evaluation_result (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    history_id          BIGINT NOT NULL,
    window_label        VARCHAR(8)  NOT NULL,
    evaluated_at        DATETIME    NOT NULL,
    evaluator_version   VARCHAR(16) NOT NULL,
    outcome             VARCHAR(40) NOT NULL,           -- SUCCESS_INTERIM / SUCCESS_FINAL / FAIL / INCONCLUSIVE / INCONCLUSIVE_FINAL / INSUFFICIENT_DATA / EXPIRED_ROLLBACK_SUGGESTION / FAIL_ROLLED_BACK / FAIL_KEPT_BY_USER / SUPERSEDED
    delta_win_rate         DECIMAL(8,4),
    delta_avg_return       DECIMAL(8,4),
    delta_avg_mfe          DECIMAL(8,4),
    delta_avg_mae          DECIMAL(8,4),
    delta_avg_relative_return DECIMAL(8,4),
    rollback_suggested  BOOLEAN     NOT NULL DEFAULT FALSE,
    rollback_suggested_at DATETIME,
    rollback_expires_at DATETIME,                       -- FAIL 後 +14 自然日
    user_decision       VARCHAR(24),                    -- NULL / KEEP / ROLLED_BACK
    user_decision_at    DATETIME,
    user_decision_by    VARCHAR(64),
    evaluation_skip_reason VARCHAR(64),                 -- INSUFFICIENT_DATA 時的原因 code
    reason              VARCHAR(2000) NOT NULL,         -- 文字解釋觸發哪條 rule
    metrics_before_json JSON        NOT NULL,
    metrics_after_json  JSON,
    UNIQUE KEY uk_eval_history_window (history_id, window_label),
    INDEX idx_eval_outcome (outcome, evaluated_at),
    INDEX idx_eval_rollback (rollback_suggested, rollback_expires_at),
    CONSTRAINT fk_ter_history FOREIGN KEY (history_id)
        REFERENCES strategy_tuning_history(id)
);
```

> `strategy_tuning_history.after_metrics_json` / `before_metrics_json` 既有欄位**保留**（向後相容），但新流程不再寫入；資料以 `tuning_apply_snapshot` / `tuning_after_metrics` / `tuning_evaluation_result` 為準。

### 7.2 Service / Engine / Job 責任邊界

| 元件 | 責任 | 不可做的事 |
|---|---|---|
| `TuningEvaluationEngine` | 對單筆 `(history_id, window)` 計算 sample / 跑 §3 規則 / 回傳 `EvaluationResult` 物件 | 不寫任何表；不呼叫 ScoreConfigService |
| `TuningSnapshotService` | 在 `applyApprovedRecommendation` 同事務內寫 `tuning_apply_snapshot`；提供 `findSnapshot(historyId, windowLabel)` | 不可在 apply 後重算 snapshot；不可被 evaluator 寫入 |
| `TuningAfterMetricsService` | 計算 after window 指標、寫 `tuning_after_metrics`；唯一可寫該表的 service | 不做 outcome 判定（那是 engine 的事） |
| `TuningEvaluationService` | 把 engine 結果寫入 `tuning_evaluation_result`；提供 `recordUserDecision(id, KEEP/ROLLED_BACK)` | 不直接呼叫 `ScoreConfigService.update`；rollback 還是走 `StrategyTuningService.rollbackRecommendation` |
| `TuningAfterTrackingJob` | 每日 19:30 掃 history、調度 evaluator | 不會自動 rollback；發現 FAIL 只寫 `rollback_suggested = true` |
| `TuningEvaluationController` | REST API（list / detail / KEEP / 重評） | 不做業務邏輯 |

`StrategyTuningService.applyApprovedRecommendation` 需擴充：

```java
// 偽碼
@Transactional
public StrategyTuningRecommendationDto applyApprovedRecommendation(Long id, String appliedBy) {
    // ...既有寫 strategy_tuning_history、score_config 流程不變...
    // 新增：寫三窗 snapshot
    snapshotService.persistApplySnapshots(history.getId(), recommendation.getTargetParameter(), appliedAt);
    // 同事務；任一失敗則 score_config 也 rollback
}
```

### 7.3 REST API

```http
GET  /api/tuning-evaluation/results?historyId=...
GET  /api/tuning-evaluation/results/{id}
GET  /api/tuning-evaluation/rollback-suggestions     -- 列出所有 outcome=FAIL 且未 user_decision 的
POST /api/tuning-evaluation/results/{id}/keep        -- 標 user_decision=KEEP（凍結 FAIL 不再提醒）
       Body: { reason?: "..." }
POST /api/tuning-evaluation/history/{historyId}/reevaluate
       Body: { windowLabel: "T5"|"T10"|"T20" }       -- admin only；強制重算

-- rollback 仍由既有 endpoint
POST /api/strategy-tuning/recommendations/{id}/rollback   -- 自 self-tuning v1
```

**安全**：
- `keep` / `reevaluate` 必須 `X-Austin-Confirm: true`。
- `reevaluate` 只允許在 outcome ∈ {INSUFFICIENT_DATA, INCONCLUSIVE} 的 result 上執行；SUCCESS_FINAL / FAIL / FAIL_ROLLED_BACK 一律拒絕（避免結論被反覆推翻）。

### 7.4 Dashboard

於既有 `Self Tuning` 分頁下新增子頁 `Tuning Validation`：

| 區塊 | 欄位 | 顏色 / 互動 |
|---|---|---|
| **Pending Validation** | history_id、config_key、applied_at、距 T+5/T+10/T+20 還剩幾天 | 灰底，純資訊 |
| **Rollback Suggestions** | history_id、config_key、觸發的 FAIL Rule、delta_avgReturn / delta_winRate / delta_avgMAE、剩餘天數（rollback_expires_at - now） | 紅底；按鈕 `KEEP` / `ROLLBACK`（後者跳轉到既有 rollback 對話框） |
| **Validated Successes** | 過去 90 日 SUCCESS_FINAL；含三窗指標差 | 綠底 |
| **Inconclusive** | 過去 90 日 INCONCLUSIVE_FINAL；按 Austin 自行決定保留與否的數量計分 | 灰底 |
| **Validation Heatmap** | `config_key × 月份`，顏色為 outcome 分布（綠：SUCCESS_FINAL；紅：FAIL；灰：其他） | 用於檢視「哪些 key 一直 fail」→ 反映 rule engine 信號好壞 |
| **Job Run Log** | `TuningAfterTrackingJob` 每次跑的：history 掃描數、新增 result 數、INSUFFICIENT_DATA 數、FAIL 數 | 灰底 |

**LINE 通知**：
- 每日 19:35（job 完成後）若有「**新增**」FAIL → 一封獨立 LINE：「⚠️ 今日新增 N 筆建議回滾的調參，請至 dashboard 確認」。
- 每日 19:35 若有「新增」SUCCESS_FINAL → 一封獨立 LINE：「✅ N 筆調參已驗證提升」。
- INCONCLUSIVE / INSUFFICIENT 不發 LINE。
- 主訊息區（09:30 / 11:00 / 15:30）**不混入**驗證結果，避免干擾交易訊息。

**禁止呈現方式**：
- ❌ 不可在主 dashboard 首頁寫「系統已自我優化 X 項並全部驗證成功」。
- ❌ 不可把 SUCCESS_INTERIM 顯示為「✅ 已驗證提升」（必須區分 interim / final）。
- ❌ 不可把 INSUFFICIENT_DATA 顯示為紅燈或建議 rollback。

### 7.5 測試驗收

#### Unit Tests

| 測試類別 | 測試範圍 |
|---|---|
| `TuningEvaluationEngineTests` | §3 各規則 happy / boundary / opposite 案例：例如 avgReturn +1.0pp 剛好命中 SUCCESS、+0.99pp 不命中；MAE 惡化 -2.0pp 必觸發 FAIL 即使 SUCCESS 條件全中 |
| `TuningSnapshotServiceTests` | 寫 snapshot 時 bucket_filter 正確套用；同 history_id + window 重複寫應 throw（資料完整性） |
| `TuningAfterMetricsServiceTests` | 三窗 sample 計算、benchmark 拉 0050 同期、市況污染（C 級連續 ≥ 3 天 > 40%）正確標 INSUFFICIENT_DATA |
| `TuningEvaluationControllerTests` | `keep` / `reevaluate` 無 `X-Austin-Confirm` header → 403；reevaluate 在 SUCCESS_FINAL 上 → 400 |
| `TuningAfterTrackingJobTests` | 跳過週末與 TWSE 假日；T+5 評估時點 = appliedAt + 6 trading days；`SUPERSEDED` 在同 key 30 日內二次 apply 時觸發 |

#### Integration Tests

| 測試類別 | 場景 |
|---|---|
| `TuningAfterTrackingFlowIntegrationTests` | `apply → 模擬 5/10/20 trading days 過後 → 跑 job → 驗 outcome 寫入正確` 的完整路徑（含 SUCCESS / FAIL / INSUFFICIENT_DATA 三條分支） |
| `TuningRollbackFlowIntegrationTests` | FAIL → Austin 點 ROLLBACK → 驗 score_config 還原、evaluation_result.user_decision = ROLLED_BACK、history 多一筆 type=ROLLBACK |
| `TuningKeepFlowIntegrationTests` | FAIL → Austin 點 KEEP → user_decision=KEEP、不會再產生新的 LINE 通知 |
| `TuningExpirationJobTests` | FAIL 後 14 自然日無動作 → outcome 自動轉 EXPIRED_ROLLBACK_SUGGESTION |
| `TuningSupersedeIntegrationTests` | 同 key 30 trading days 內二次 apply → 第一次 evaluation_result 全部標 SUPERSEDED |
| `TuningSnapshotImmutabilityTests` | DB-level 驗證 `tuning_apply_snapshot` 只能 INSERT，無 UPDATE 路徑（Spring data jpa repository 沒暴露 save(existing)） |
| `TuningSafetyTests` | 反向驗證：mock `TuningAfterTrackingJob` 跑一輪 → `score_config` / `position` / `paper_trade` 行數不變；DI 圖檢查 `TuningEvaluationEngine` 沒有 `ScoreConfigService` 依賴 |

#### 不影響既有測試

- `mvn -q test` 全綠（含 `StrategyTuningEngineTests`、`StrategyTuningServiceTests`、`StrategyTuningFlowIntegrationTests`）。
- 既有 `strategy_tuning_history.before_metrics_json` / `after_metrics_json` 欄位的 read 路徑不可被破壞（向後相容）。

---

## 8. Phase 實作順序（建議給 Codex）

| Phase | 範圍 | 預估工作量 |
|---|---|---|
| **P0** | DB migration `V30__tuning_after_tracking.sql`、Entity / Repository（read-only for evaluator） | 1 工作日 |
| **P1** | `TuningSnapshotService.persistApplySnapshots` + 接到 `applyApprovedRecommendation` 同事務 | 1 工作日 |
| **P2** | `TuningAfterMetricsService` 計算三窗指標 + bucket filter + benchmark relative return | 1.5 工作日 |
| **P3** | `TuningEvaluationEngine` 規則 §3 + INSUFFICIENT_DATA gates + 規則優先序 | 1.5 工作日 |
| **P4** | `TuningAfterTrackingJob` 排程 + idempotency + supersede 邏輯 | 1 工作日 |
| **P5** | REST API + `keep` / `reevaluate` 端點 + X-Austin-Confirm | 1 工作日 |
| **P6** | Dashboard `Tuning Validation` 子頁 + LINE 模板 | 1.5 工作日 |
| **P7** | E2E：模擬 25 trading days 資料 → 觀察 SUCCESS / FAIL / INSUFFICIENT 三分支 | 1 工作日 |

> 建議在 P0 ~ P4 完成後先跑 30 日「乾驗證」（只寫 evaluation_result，不發 LINE、UI 隱藏），確認 outcome 分布合理後再開 LINE 通知與 KEEP/ROLLBACK 入口。

---

## 9. 開放議題（待 Austin 確認）

1. **MIN_SAMPLE_AFTER 預設 10 是否夠？** 中短線一週只 3-5 檔，T+5 後 sample 可能僅 3-5 筆。是否容忍 `MIN_SAMPLE_AFTER = 6` 但 outcome 上限為 INCONCLUSIVE / INTERIM（不可 FINAL）？
2. **T+5 即觸發 FAIL 是否太早？** 早期警示有助止血，但可能誤殺。是否限制 T+5 只能對 MAE / hit_stop_rate 規則 (Rule 2 C/D) 觸發 FAIL，其他規則一律延到 T+10？
3. **rollback 建議的 14 天有效期是否適中？** Austin 旅行回來可能錯過。是否改為「30 天 + 第 7 / 14 / 28 天提醒」？
4. **SUCCESS_FINAL 後是否該凍結 evaluation？** 例如同 key 後續再 apply 應該另外開 evaluation chain，還是讓 SUCCESS_FINAL 紀錄持續往前接？本文件預設「同 key 二次 apply → 第一次標 SUPERSEDED 結束」。
5. **是否允許 Austin 自定義 SUCCESS / FAIL 閾值？** 例如把 `delta_avgReturn` 從 +1pp 改成 +2pp。預設不開放（避免事後調動讓結論失真），等 v2 再考慮。

---

**結束。等 Austin / Codex review。**
