# 量化策略審計與升級設計 2026-04-28

> 立場：量化交易策略審計師 + 實戰操盤手 + 系統架構師。
> 唯一目標：**能穩定賺錢**。不講教科書。
> 範圍：D:/ai/stock 整套系統，code + 30 天 DB 實證。

---

## 0. 30 天 DB 殘酷事實（先看數字）

| 指標 | 數值 | 評論 |
|---|---:|---|
| `final_decision` 30 天 ENTER 次數 | **0 / 55** | 即使昨天降門檻 B 7.4→6.5 + AI reweight 後，今天仍 0 ENTER |
| 30 天 final_decision 分布 | REST 40 / PLAN 9 / WAIT 5 / WATCH 1 | **73% REST**，剩下都不是 ENTER |
| `execution_decision_log` ENTER | **36 / 93** | **底層 timing gate 觸發 36 次「應該進」，上層 FinalDecisionEngine 全部沒下單** |
| `execution_decision_log` 最後一筆 ENTER | **2026-04-22** | 6 天前；最近 1 週 0 ENTER，連底層都熄火 |
| 30 天 candidate_stock | 70 row / 52 unique / 8 trading days | top 5 重複出現問題未解 |
| max `final_rank_score` 30 天 | **7.00** | 明明已過 B 6.5 門檻但仍沒 ENTER |
| `market_snapshot` regime 30 天 | A-grade ~50%、B-grade ~30%、C-grade **0%** | **系統從不認為市場差到該停手** |
| 4 筆 closed positions（**全是 Austin 手動進場**） | +2,150 / +13,440 / -2,300 / -600 = **淨 +12,690** | 兩筆都是 00631L 槓桿 ETF；勝率 2/4 = 50%；無 system-driven 樣本 |
| paper_trade 累積樣本 | **1 筆**（昨天 8112 real-manual 鏡射，已平倉 -1.24%） | 自動 paper pipeline **從未觸發**（因為 0 ENTER） |
| `monitor_decision` 30 天 | 持續每 5 分一筆 | 監控有跑、但跑了沒用：底下沒倉位 |

---

## 1. 系統致命問題 Top 5（按嚴重性）

### 🔴 #1：「**有引擎、沒油路**」— 30 天 0 ENTER 的真相

不是門檻問題、不是 AI 評分問題。是**架構接線斷裂**：
- 底層 `execution_decision_log` 30 天觸發 36 次 ENTER（timing/setup/risk gate 都通過）
- 上層 `final_decision` 30 天觸發 **0 次** ENTER
- 中間至少有一道**未被命名的隱藏 gate** 把所有 ENTER 訊號降為 REST/WAIT/PLAN

我推測（需精確 trace）的 3 個可能：
1. `FinalDecisionService` 在組裝 candidate 列表時把 `tradabilityTag = "可回測進場候選"`、`includeInFinalPlan=false` 等 flag 當 hard reject
2. Codex / Claude default score 3.0 仍在某條件分支沒被 reweight 修補（比如 `consensusScore` 計算）
3. PLAN/POSTMARKET 階段的 candidate **永遠不被 OPENING 階段認領**（兩個 task_type 之間斷層）

**這是最致命的問題**——其他都是次要。修這一條，30 天 0 ENTER 立刻會變成 5–10 ENTER。

### 🔴 #2：**Market Regime 永遠 A 級**——風險過濾器形同虛設

30 天 market_snapshot 紀錄：A-grade 50% / B-grade 30% / **C-grade 0%**。
- 即使全球科技股大跌（如 04-22 之類）也判 A
- 系統從不啟動「regime kill switch」（C-grade → REST）
- 等於 regime 系統**只能升不能降**，無法保護下檔

連帶問題：
- 多頭、震盪、空頭都用同一套策略
- 沒有「規避型」策略（mean reversion / 完全空手）

### 🔴 #3：**只有「進場」沒有「續抱 / 出場決策」**

30 天 closed positions 4 筆，**全是 Austin 手動進場**，平倉理由：
- 333、334（00631L）：TAKE_PROFIT_2 自動觸發 → 系統有出場
- 332（8112）：STOP_LOSS 自動觸發 → 系統有出場
- 250（2303）：STOP_LOSS 04-23

但「續抱 vs 出場 vs 加碼」決策層基本不存在：
- `position_review_log` 跑了 160+ EXIT 訊號（30 天）
- 但沒接到自動平倉路徑（昨天 review 提過）
- Austin 完全靠人眼盯盤決定何時平倉

### 🔴 #4：**Theme 層只能識別、不能輪動**

- 30 天 70 candidate 中 26% 落在「其他強勢股」（catch-all）
- 半導體系列 63% 集中度（無 sector rotation）
- 無「題材熱度衰退偵測」（如「PCB 從 #1 降到 #4 了」）
- 無「資金流動偵測」（外資從 PCB 換到記憶體）
- 結果：**系統永遠在追昨天的主流**，永遠晚一拍

### 🔴 #5：**Forward Testing 已寫好但沒餵資料**

昨天 paper_trade pipeline 4 個 Subagent 全做完，但因為 #1（0 ENTER），整個 pipeline **完全沒觸發**過自動 entry。
- paper_trade 表 30 天累積 1 筆（手動鏡射 8112，非系統自動）
- paper_trade_snapshot 0 筆
- paper_trade_exit_log 0 筆
- PaperTradeStatsService.computeStats(30) 永遠回 zeros

**最大諷刺**：寫了一整套校準系統，因為主管道斷掉，校準系統永遠拿不到資料。

---

## 2. 為什麼賺不到錢（直白 5 句話）

1. **系統 30 天沒下任何一筆單**——不是「賺不到錢」，是「**根本沒在交易**」。Austin 賺的 +12,690 全是手動進場、靠自己判斷，跟系統無關。
2. **底層引擎正常運轉、上層決策層斷線**——execution_decision_log 36 次 ENTER 訊號被 FinalDecisionEngine 攔截下來；不是策略不好，是接線壞了。
3. **Market regime 永遠樂觀**——30 天 0 次 C-grade，等於系統認為任何時候都可以做多；保護下檔的閘門根本沒啟動。
4. **Theme 永遠追同一群**——半導體 63% 集中、頂部 5 檔重複出現 3+ 次；沒有「題材輪動偵測」就永遠買到主流末端。
5. **昨天剛建好的校準系統收不到 input**——paper_trade pipeline 寫得很完整，但因為主管道斷，30 天沒寫進一筆自動樣本，校準系統「能算什麼」其實都白搭。

---

## 3. 升級後的最終策略架構（文字版）

### 🎯 三策略並行（Strategy Multiplexing）

```
                    ┌─────────────────────┐
                    │  Market Regime      │
                    │  Detector (real)    │
                    │  ─────────────────  │
                    │  BULL / RANGE /     │
                    │  BEAR / PANIC       │
                    └────────┬────────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
        BULL/strong     RANGE/chop     BEAR/PANIC
              │              │              │
        ┌─────▼──────┐  ┌────▼─────┐  ┌─────▼──────┐
        │  SETUP     │  │   MEAN   │  │   FREEZE   │
        │  (低頻高勝率)│  │ REVERSION│  │  (空手)    │
        │            │  │ (震盪用)  │  │            │
        │  + Mom-Obs │  │ + 空手   │  │  Watch only│
        └─────┬──────┘  └────┬─────┘  └─────┬──────┘
              │              │              │
              └──────────────┼──────────────┘
                             │
                    ┌────────▼────────────┐
                    │ Position Manager    │
                    │ (Trail / Scale-out  │
                    │  / Force-exit)      │
                    └─────────────────────┘
```

### 📋 三策略職責分工

| 策略 | 啟用條件 | 進場邏輯 | 預期勝率 / RR | 樣本/月 |
|---|---|---|---:|---:|
| **SETUP**（低頻高勝率）| BULL_TREND OR BULL_PULLBACK | A+ ≥ 8.0 + RR ≥ 2.5 + 主升初/中 + 法人連買 + theme top 3 | 60–65% / 1:2.5 | 2–4 |
| **MOMENTUM**（吃波段）| 任何 + B-grade 以上 | 突破 N 日新高 + 量增 1.5x + 收在 95% 日高 + theme top 5 | 45–50% / 1:1.8 | 5–8 |
| **MEAN REVERSION**（震盪用）| RANGE_CHOP only | 跌破布林下軌 + RSI < 30 + 量縮 + 大盤未破前低 | 50–55% / 1:1.2 | 3–5 |
| **FREEZE**（空手）| BEAR / PANIC / consecutive_loss=3 / daily_loss > 5k | 不進場、不出場決策、只觀察 | — | 0 |

### 🎯 Scoring 流程（AI = veto + 加分，不主導）

```
原 score = JavaStructureScore (0-10)              ← 主導，60% 權重
         + AI 加分 (Claude / Codex 一致 → +1.0)   ← 最多 +2.0
         - AI veto (Claude red flag → -3.0)       ← 最多 -3.0（不直接 0）
         + Theme bonus (top 3 + 上升軌 → +1.0)
         + Regime bonus (符合策略 regime → +0.5)
         - Crowding penalty (top symbol 已連 3 天 → -1.0)
         = 0–10 final score

ENTER threshold:
  SETUP:    final ≥ 7.5 + RR ≥ 2.5 + AI 不 veto
  MOMENTUM: final ≥ 6.5 + RR ≥ 1.8 + theme top 5
  MR:       final ≥ 6.0 + RSI < 30
```

### 🚪 進出場決策樹

```
ENTRY:
  candidate 過 strategy 各自門檻
    → 過 PriceGate
      → 過 ChasedHigh (with 60-day high awareness)
        → 過 Crowding check（同 theme 持倉 ≤ 30%）
          → 過 Cooldown check（連續虧損 ≤ 3、日損 ≤ 5k）
            → 通過：ENTER

POSITION REVIEW (每 5 分):
  status = TRAIL_UP   → 上移停利、不出場
  status = STRONG     → 持有、考慮加碼（max 1.5x）
  status = WEAKEN     → LINE 警告、考慮平倉
  status = EXIT       → **自動平倉**（這條目前缺）

FORCE EXIT:
  時間：14 trading days 強制檢視
  風控：daily loss > 5k → 全部平倉
  Regime：BULL → BEAR → 全部平倉
```

### 🛡 風控四層

```
Layer 1 (per-trade): SL 5–6% / TP1 8% / TP2 15%
Layer 2 (daily):     daily_loss > 5k → 當日不再開新倉
Layer 3 (weekly):    consecutive_loss = 3 → 暫停 24 小時
Layer 4 (regime):    BEAR/PANIC → 全部 OPEN positions 強制檢視
```

---

## 4. 具體改動清單（給工程師）

### 🔴 P0 — 立刻做（決定能不能賺錢）

#### P0.1 修「30 天 0 ENTER」斷線（**最關鍵**）
- **問題**：FinalDecisionEngine 與 execution_decision_log 不同步——底層 36 次 ENTER 訊號上不來
- **改成**：寫一個 `python3 trace_one.py <decision_id>` 工具，逐 row 對照 execution_decision_log + final_decision，把 candidate 從 PostmarketWorkflow 寫入到 OPENING decision 之間每個轉換點 log + 是否 reject 的原因暴露出來。Run on 04-22 historical data（execution=ENTER 但 fd=PLAN 那 3 row：6770 / 8028 / 2454）找出究竟哪個 gate 把 ENTER 改 PLAN
- **預期效果**：找出隱藏 gate 後修掉，30 天 0 ENTER 立刻變 ~10 ENTER（樂觀估）
- **工程量**：M（trace tool 1 天 + 修隱藏 gate 半天 + 驗證 1 天）

#### P0.2 Market Regime 真實降級（C-grade 必須能出現）
- **問題**：30 天 0 次 C-grade，等於 regime 沒在做事
- **改成**：MarketRegimeService 加入「**至少 N 個下跌日 + 加權指數低於 60MA + 半導體 5 日報酬 < -3%**」三條件之一即降為 C。同時 daily_loss > 5k 也強制 C-grade
- **預期效果**：未來 BEAR 期間自動 freeze；Austin 不會在大盤跌時還收到 ENTER 推薦
- **工程量**：S（純規則加碼，1 天）

#### P0.3 PositionReviewLog EXIT 自動平倉（已寫但沒接）
- **問題**：position_review_log 30 天 160+ EXIT 訊號，沒人接
- **改成**：PositionReviewService 在 EXIT 訊號時呼叫 PositionService.closePosition()，標 exit_reason=REVIEW_EXIT。**先做 paper_trade（已有架構）驗證 1 週，再切真倉**
- **預期效果**：Austin 不再需要手動盯盤，系統自動執行出場
- **工程量**：M（接線 + flag 控制 1 天，paper 驗證 5 個交易日）

### 🟡 P1 — 一週內（補強選股 + 三策略並行）

#### P1.1 三策略並行框架
- **問題**：目前只有 SETUP，沒有 MOMENTUM live + 沒有 MEAN_REVERSION
- **改成**：
  - 新增 `StrategySelectorService` — 根據 regime + market_phase 決定啟用哪 1–2 個策略
  - MOMENTUM 從 observation 切 live（先 paper-mode）
  - 寫 MeanReversionStrategy 占位（先 paper-only，不 live）
- **預期效果**：BULL 多 SETUP、RANGE 多 MR、震盪多 MOM、總交易頻率 2–4x
- **工程量**：L（核心新類別 + 測試 + 接線，5–7 天）

#### P1.2 Crowding（同題材持倉 + 同股連選）懲罰
- **問題**：top 5 symbols 在 8 天內各出現 3+ 次，持倉容易擠爆同 sector
- **改成**：scoring 加入 `crowding_penalty`：同 theme 持倉 > 30% → 該 theme candidate 全部 -1.0；同 symbol 連續 3 天進候選 → -0.5
- **預期效果**：分散 sector beta、減 60% 集中半導體
- **工程量**：S（規則加碼，半天）

#### P1.3 補 stock_dailybar 表 + 5/20/60 MA 計算
- **問題**：選股只用 1 根 K（昨天 review 主結論）
- **改成**：每日抓 TWSE 收盤資料、加均線結構判斷
- **預期效果**：把「末升段最後一根」與「主升中繼」分開
- **工程量**：M（資料落表 + 1 個 indicator service，3 天）

### 🟢 P2 — 兩週內（風控 + 校準閉環）

#### P2.1 連虧 / 日損自動 freeze
- **問題**：cooldown 規則設定有但沒驗證實際觸發路徑
- **改成**：FinalDecisionGuardService 監聽 PositionClosedEvent → 統計 consecutive_loss 與 daily_loss → 觸發 trading.status.allow_trade=false
- **預期效果**：黑天鵝下檔保護；Austin 連虧 3 筆強制休息一天
- **工程量**：S（一個 listener service，1 天）

#### P2.2 Theme 輪動偵測
- **問題**：永遠追昨天主流
- **改成**：ThemeEngine 加入「7 日題材分數變化」指標 — top theme 從 #1 降到 #4 → 該 theme candidates score -1.0
- **預期效果**：抓得到題材換軌的時刻
- **工程量**：M（一個 trend detection job，2 天）

#### P2.3 Forward Testing 升級（對照昨天 paper_trade 實作）
- **昨天已實作**：paper_trade entity（含 entry_grade / regime / payload_json）+ exit job + snapshot + stats endpoint + mobile UI
- **問題**：因 0 ENTER，pipeline 從未自動觸發
- **改成**（修 P0.1 後即生效）：
  - 確認 `FinalDecisionPersistedEvent` 真的會 fire 當 decision=ENTER
  - 加 `paper_trade_metric_per_strategy` view → 區分 SETUP / MOMENTUM / MR 各自勝率 RR
  - mobile.html 加「策略分流績效卡片」（哪個策略賺最多 → 自動加權重）
- **預期效果**：3 週累積 30 筆樣本後，可由系統自動建議「下週把 SETUP 配 60% / MOM 30% / MR 10%」這種權重
- **工程量**：S（追加 SQL view + UI，2 天）

### 🔵 P3 — 觀察數據後再說

#### P3.1 自適應 sizing（Kelly / win-rate scaling）
- **觀察前提**：P2.3 累積 ≥ 50 筆 closed paper_trade
- 改 CapitalAllocationService 用實際勝率 + RR 計算每筆下注比例

#### P3.2 結合外部資料（法說會 calendar、月營收）
- catalyst-driven entry：法說後 24h 內 + 突破前高 → +1.5 score

---

## 5. 預期改善（量化對比）

| 指標 | 現在（30 天） | 升級後（estimate） |
|---|---:|---:|
| **System ENTER 次數 / 月** | **0** | **8–15** |
| 月勝率 | N/A（無樣本） | 50–55%（混合三策略） |
| 平均單筆獲利 | N/A | +3–4%（trail + scale-out 加持） |
| 平均單筆虧損 | -2.6%（8112 唯一手動樣本） | -2%（嚴格 SL 不放） |
| Profit Factor | N/A | 1.5–2.0 |
| 月最大回撤 | N/A | -3% 到 -5%（4 層風控保護） |
| 月化報酬 | N/A | +2.5% 到 +4%（保守） |
| 年化 vs 0050（8–10%） | 跑輸（0% return） | 樂觀 +12 至 +20%、悲觀 +6 至 +10% |
| Austin 盯盤時間 | 8h+/日（全手動） | 2h/日（自動 ENTER + 自動 EXIT，只看 LINE） |

### 三策略權重建議（升級後初始）

| 策略 | 月初配比 | 60 天回測後可動態調整到 |
|---|---:|---|
| SETUP | 50% | 30–70%（依勝率）|
| MOMENTUM | 35% | 20–50% |
| MEAN REVERSION | 15% | 10–30% |
| FREEZE 機率 | ~5–8 天/月 | 視 regime 而定 |

### 最大改進槓桿是哪 1 件？

**P0.1（修「30 天 0 ENTER」斷線）**。

理由：所有後續改進都依賴系統「真的有在做事」這個前提。沒修 P0.1，加再多策略、加再多 regime、加再多風控都是空的——系統繼續 0 ENTER、Austin 繼續手動操盤、paper_trade 繼續累積 0 樣本、自適應永遠不啟動。

修這一條，整個架構**從「研究系統」變成「交易系統」**。其他 P0-P2 是優化、P0.1 是「**讓系統開始活著**」。

---

## 附錄：DB 證據快照

```
30 天 final_decision 分布:
  REST   : 40 (73%)
  PLAN   :  9 (16%)   ← T86_TOMORROW + POSTMARKET 規劃，沒進場
  WAIT   :  5 (9%)
  WATCH  :  1 (2%)
  ENTER  :  0 (0%)    ★ 30 天 0 ENTER（fd=ENTER 計數）

30 天 final_decision 按 source 分布:
  PREMARKET   : WAIT * 4
  OPENING     : REST * 11 / WAIT * 2 / WATCH * 1
  MIDDAY      : REST * 4
  POSTMARKET  : PLAN * 5 / REST * 2
  T86_TOMORROW: PLAN * 6 / REST * 1

30 天 execution_decision_log 分布:
  ENTER (CONFIRMED)         : 36   ← 底層 36 次說「應該進」
  SKIP  (BASE_ACTION_SKIP)  : 37
  SKIP  (VETOED)            : 19
  SKIP  (CODEX_VETO)        :  1
  → 中間掉了 36 次 ENTER 訊號，最後 1 週連底層也熄火

30 天 closed positions（全 Austin 手動進場）:
  333 00631L +2150 TP2     ← 槓桿 ETF 抓中波段
  334 00631L +13440 TP2    ← 槓桿 ETF 抓中大波段
  332 8112   -2300 STOP    ← 今日當沖 -2.62%
  250 2303   -600 STOP     ← 04-23 swing
  total = +12,690 NTD（淨賺 12.69k，但勝率 2/4，且全靠 Austin 個人判斷）

30 天 market_snapshot regime 分布（mostly A）:
  A : 19 (50%)   ← 半數天 A-grade
  B : 12 (32%)
  C :  0 (0%)    ★ 從未降為 C，regime 風險過濾形同虛設
  NULL: 19      （PREMARKET / CLOSE 階段未填 grade）

30 天 stock_evaluation max scores:
  max final_rank_score = 7.00
  max java_structure   = 7.30
  max claude_score     = 8.00（極少數）
  max codex_score      = 8.00（極少數）
  is_vetoed=true       :  0   ← 沒有顯式 veto，但 ENTER 仍 0
```
