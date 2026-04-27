# 策略深度審查 2026-04-27

> 立場：專業 PM + 量化研究員。看的是「能不能賺錢」，不是「code 寫得漂不漂亮」。
> 範圍：D:/ai/stock 整套選股鏈 + 過去 30 天 DB 實際資料。

---

## A. Executive Summary

### 一句話結論
**系統現階段「不能賺錢」**——不是因為策略爛，而是因為**過去 30 天 0 次 ENTER**：所有 candidate 的 `final_rank_score` 都被卡在 7.0 以下，連 B 級門檻 7.4 都沒摸到。系統能評分、能寫研究、能監控持倉，但**結果交不出來下單訊號**。

### Top 3 賺錢機率最大的優勢
1. **Gate 結構乾淨**：Kill switch → MarketGrade=C 阻擋 → DecisionLock → PriceGate → ChasedHigh shadow → Bucket A+/A/B → Allocation。每一層職責清楚，未來要 tune 不會撞到別層。
2. **PriceGate 三態（PASS/WAIT/BLOCK）有 regime-aware 邏輯**：BULL 寬容、BEAR 嚴抓、用 VWAP/量能/距 open 多重判斷，這是真的 swing PM 會做的事。
3. **PositionReview 雙重停損**（v2.12 Fix3：drawdown 7% + 動能轉弱 dual-condition）以及 trailing stop 三段（3%/5%/8%）都對得上 1–2 週波段的節奏，且 STRONG/WEAKEN/EXIT 規則明確。

### Top 3 最嚴重的賺錢漏洞
1. **【最嚴重】`final_rank_score` 結構性過低，永遠進不到 B**：30 天 max = **7.00 < B 門檻 7.4**。Claude/Codex 對絕大多數 candidate 給 default 3.00（沒真正研究），只有極少數（如今天的 2337）有完整三家分數，但 theme decay 又把 final 從理論 7.045 砍到實際 **6.16**。**結論：系統永遠 REST，門檻 + AI 鏈雙重失血。**
2. **「假完成」模組**：
   - `theme_shadow_decision_log` 30 天 = **0 rows**（Theme Engine v2 shadow 寫了沒人記）
   - `paper_trade` 30 天 = **0 rows**（Phase 1 forward-live virtual 完全沒跑）
   - `position_review_log INTRADAY EXIT = 160 次` 但**沒有 auto-close**，純記錄。
3. **唯一一筆成交虧損**：2303 聯電（成本 75.7，2 天賠 600 元出場）。30 天樣本 = 1 trade，0/1 win rate。沒辦法統計勝率。

### 跟得上市場熱度嗎？
**勉強跟得上題材，但跟不上「熱度切換的速度」**。
- 過去 30 天 candidate 題材分佈：半導體/IC 14、記憶體/儲存 8、PCB/載板 4、AI 伺服器 3、軍工 2、散熱 1、機器人 1。**TW 主流題材都有覆蓋**。
- 但同一批 symbols 重複出現（49 unique / 60 picks，6770/3189/8028/2337/2454 各 3 次），新題材切換不夠快。
- 17 檔被丟進 catch-all `其他強勢股`，**題材標籤品質差**，這直接影響 ThemeStrengthService 給分。

### 立刻該調的 2 件事
1. **降 B 門檻或提高 Claude/Codex default 分數策略**：B 從 7.4 降到 6.5，並把「Claude/Codex 缺值」明確當成 PARTIAL_AI_READY（已存在的 enum），讓 Java-only 也能在 B 級觸發小倉。否則永遠 REST。
2. **修「假完成」**：Theme v2 shadow 真的開始寫 log（diff vs legacy），且讓 `paper_trade` 真的接 ENTER 流（即使 LINE 配額不夠，至少 paper 落 DB）。沒有 shadow 數據，永遠不知道 Theme v2 是不是 ready 切 live。

---

## B. 量化分析發現

### B.1 選股因子有效性

**權重設計合理但執行失敗**：
- `scoring.java_weight = 0.40` / `claude_weight = 0.35` / `codex_weight = 0.25` — 1.00 加權。比重合理（Java 量化結構為主、AI 為輔）。
- `JavaStructureScoringEngine` 算分項：RR (0–4 with multiplier 1.3) + 計畫品質 (0–2) + entryType (BREAKOUT 2 / REVERSAL 1.5 / PULLBACK 1) + valuationRisk (0–1) + bonus (0–1)。**設計正確**。
- 但實際 30 天 `stock_evaluation`：
  ```
  max final_rank_score = 7.00
  max java_structure_score = 7.30
  max claude_score = 8.00 (極少數)
  max codex_score = 8.00 (極少數)
  ```
- A+ 門檻 8.8 / A 8.2 / B 7.4 對「中位 PM」來說太嚴。實際資料：
  - 今天 2337 旺宏（系統最看好的）：java 6.80 + claude 7.50 + codex 6.80 → 預期 final = 7.045，**實測 final = 6.16**（theme decay 約 -0.88）
  - 其他 9 檔：claude/codex = 3.00（未填回） → final = 2.4–3.0 全 C
- **結論：因子權重沒問題，門檻太嚴 + AI 端 default 值太低**。

### B.2 進場時機準確度

**setup engine 找得到，但 final 不放行**。
- `setup_decision_log` 30 天：**39 BREAKOUT_CONTINUATION valid + 3 PULLBACK_CONFIRMATION valid + 7 invalid**。代表多日 swing setup detector **每天都在 work**。
- `execution_decision_log` 30 天：**36 ENTER (CONFIRMED) + 37 SKIP_BASE_ACTION_SKIP + 19 SKIP_VETOED + 1 CODEX_VETO**。但這是底層 timing/setup/risk 的 ENTER 訊號，**不是 final_decision 的 ENTER**——final_decision 30 天 ENTER = 0。
- 也就是：**底層 pipeline 找到 36 個進場機會，全部被 final 層卡死**。
- PriceGate 沒有單獨 trace 表，無法直接比對 WAIT_CONFIRMATION 的勝率。
- ChasedHigh shadow 沒有獨立 log 表（只在 final_decision payload_json 內），無法獨立統計。
- **結論：執行層發訊號 OK，最終決策層斷掉**。

### B.3 持倉管理績效

**樣本太小，但訊號量很大**：
- 30 天 closed positions = **1 筆**（2303 聯電，hold 2 天，pnl -600）。
- 30 天 open positions = 2 筆（2303 聯電 [新一筆]、00631L 元大正二）。
- `position_review_log` 30 天：
  - INTRADAY: STRONG 180 / EXIT **160** / TRAIL_UP 7
  - DAILY: STRONG 5 / EXIT 3
- **EXIT 訊號 160 次 / 360 = 44%** 但實際 closed 只有 1 筆 → 訊號有發，**沒有自動觸發出場**（純人讀）。
- TradeReviewService MFE/MAE 留空（v1 not implemented），無法算 Sharpe / Kelly。
- **「買對但賺不多」 vs 「該停損沒停損」哪個嚴重？無法判斷**——只有 1 筆樣本。但從 EXIT 訊號 44% 比例看，「該停損沒停損」的潛在風險更高。

### B.4 市場熱度命中率

**題材覆蓋有，個股命中無法驗證**（系統沒留每日強勢股榜）：
- 30 天 unique candidates = **49 檔**，平均每天 ~2 新檔；多數重複出現。
- 題材分佈：半導體/IC 14、記憶體/儲存 8、PCB/載板 4、AI 伺服器 3、軍工 2、散熱 1、機器人 1、其他強勢股 17。**主流 + 副流都有覆蓋**。
- 但 17 檔在「其他強勢股」（catch-all 標籤）→ **ThemeStrengthService 對這些檔給不出 theme 強度**，等於進入評分階段就少了 0.55 × theme 比重的有效訊號。
- Theme Engine v2 shadow_decision_log = **0 筆**，沒辦法跟 legacy 比對。

---

## C. 質化分析發現

### C.1 策略定位一致性
**定位寫的是 1–2 週中短線，但執行偏向「過度保守的選擇權套利感」**：
- `position.review.max_holding_days = 15` ✓（對得上 1–2 週）
- `position.trailing.first_trail_pct = 5%` / `second = 8%` ✓（短波段節奏）
- 但 A+ 8.8 / A 8.2 / B 7.4 + RR 2.5/2.2/2.5 + 必須有題材 + 必須 Codex 不否決 + 必須過 PriceGate + 必須過 ChasedHigh —— **進場條件像在挑選股「黑天鵝套利機會」，不像是 1–2 週波段 PM**。
- 一個正常 PM 一週進場 2–3 次；這個系統 30 天 0 次。**門檻明顯與週期不符**。

### C.2 主升段覆蓋
**抓得到，但放不出**：
- BREAKOUT_CONTINUATION 是主流 setup type（39 valid / 49 total）→ 突破型有抓。
- PULLBACK_CONFIRMATION 只有 3 筆 → **拉回站穩型抓得少**，可能被 PriceGate 的 belowOpen/belowPrevClose 邏輯擋掉。
- 「回測站穩才買」這條進場路徑實際上很弱——對台股 1–2 週波段是缺一支主力武器。
- **結論：系統偏好「強勢續強」，對「拉回低吸」覆蓋不足**。

### C.3 追高 / 套牢風險
**ChasedHigh 開了 shadow，但實際決策是「擋住所有」**——不是擋對了，是因為前面 final_rank 都不到門檻所以根本沒走到 ChasedHigh 那關。
- ChasedHigh threshold 0.02 / warn 0.04（2%/4% 距日高）合理。
- 但**沒有「相對位置」概念**：season high / 60-day high / yearly high 都沒看。一檔股票漲 30% 與漲 5%，系統評分一樣依賴 dayHigh，會在「已經漲多但今天小休」時繼續看好。
- ScoreConfigService 也找不到任何 `season_high` / `52w_high` / `extension` 之類的 key。**追高警示只看 intraday，不看 swing 級別**。
- 連漲 N 天後沒有警示——這是**真正會套牢的場景**。

### C.4 風控完整性
**單檔 + 題材 + 大盤三層曝險都做了，但沒接黑天鵝應急**：
- 單檔上限：TRIAL 10% / NORMAL 20% / CORE 30% ✓
- 題材曝險：`theme.exposure_limit_pct = 30` + 新 `/api/themes/exposure` API ✓
- 大盤曝險：BULL 80% / RANGE 50% / BEAR 20% / PANIC 0% ✓（v2.11）
- **缺**：沒看到「大盤一日跌 3% / 5% 自動觸發 kill switch」的 panic detector。`market_exposure_limit.panic = 0` 是「panic 已發生時的限制」，不是「自動偵測進入 panic 的觸發條件」。
- **缺**：沒看到「連續虧損 N 筆自動 freeze」的執行——`trading.cooldown.consecutive_loss_max = 3` 設定有，但沒驗證實際觸發路徑。

---

## D. 跟同業比較

### vs 典型台股 1–2 週波段 PM
**這套缺**：
1. **每日強勢股榜**——當天前 20 漲幅榜系統沒撈，無法事後驗「該選沒選到」。
2. **法人籌碼日變化**——T86 抓進來了但只是 context payload，沒進評分權重。一個 PM 會把投信連買 3 日當作獨立加分項。
3. **季線 / 半年線 / 年線 相對位置**——完全沒有。
4. **產業輪動偵測**——半導體強→PCB 跟漲→散熱跟漲，這套輪動鏈系統沒寫。
5. **拉回站穩進場**setup type 弱（3 個 valid / 30 天）。

**這套多**：
1. **三方 AI 投票** + score divergence veto（|java-claude| ≥ 2.5 自動否決）——這是同業沒有的，理論上是好事，但實務上因為 AI 鏈交付率低反而成為阻力。
2. **多層 shadow 測試框架**——很多新邏輯都先 shadow 再 live，工程紀律比同業好。
3. **MarketRegime BULL/RANGE/BEAR/PANIC 自動降槓桿**——這是 quant 級別的設計，PM 通常憑感覺，這套有規則。

### 屬於哪一類量化策略？
**混合型，主軸偏「動能 + 題材」（Momentum + Theme），但 Codex/Claude 加進來的等於是「風險審查層」（Risk Overlay）**。
- 純度：**動能 ~50% + 題材 ~30% + 風險審查 ~20%**。
- 不是價值（沒看本益比 / 殖利率）。
- 不是純籌碼（T86 只當 context）。
- 動能因子靠 Java entryType + breakout/pullback。
- 題材靠 ThemeSelection + ThemeStrength。
- **但風險審查層（AI veto + score divergence）權重過大，把好機會也審掉了**。

---

## E. 改善建議（排優先序）

### P0 — 立刻做（影響賺錢機率最大）

#### P0.1 降 B 門檻 + 處理 AI 缺值
- **問題**：`final_rank_score` 30 天 max = 7.00，連 B 7.4 都沒摸到。原因 = Claude/Codex 對 8/10 檔 candidates 沒填分數（保留 default 3.00），即使 Java 6.8 也被拖下去。
- **為什麼**：A+ 8.8 / A 8.2 / B 7.4 是「三家 AI 都肯定」級別，台股 1–2 週波段不需要這麼嚴。
- **建議做法**：
  1. `scoring.grade_b_min` 從 **7.4 → 6.5**
  2. `scoring.grade_a_min` 從 **8.2 → 7.5**
  3. `scoring.grade_ap_min` 從 **8.8 → 8.2**（保留高標）
  4. 在 `FinalDecisionService` 偵測到 Claude/Codex score = default(3.0) 時，把 weight 重分配給 Java（例：0.40 → 0.70），不要把 default 拿去稀釋 final。
- **預期效果**：30 天估計可產生 5–10 次 ENTER（B 級小倉），開始有勝率 / RR 樣本。
- **工程量**：S（純 config + 1 個小邏輯改動）

#### P0.2 修「假完成」三件
- **問題**：`theme_shadow_decision_log = 0 rows`、`paper_trade = 0 rows`、`position_review_log` 160 EXIT 訊號無 auto-close。
- **為什麼**：沒 shadow 資料 → 永遠不能切 v2 live；沒 paper trade → 永遠驗不了策略；EXIT 訊號沒接出場 → 持倉風控只是裝飾。
- **建議做法**：
  1. ThemeSelectionEngine 寫 shadow log 那段——確認 `theme.shadow_mode.enabled=true` 真的有路徑進到 `theme_shadow_decision_log`，沒有就接上。
  2. `paper_trade` 表接 ENTER 流：每次 final_decision = ENTER 就寫一筆 paper_trade（成本 = 進場價、stop = 系統建議停損、TP1/TP2 = 建議停利），每日盤後 update 收盤價計算未實現損益。即使 LINE 配額沒了也能跑。
  3. `position_review_log` 的 EXIT 訊號至少接「自動關 LINE 警示 + dashboard 紅標」，先別自動下單。
- **預期效果**：3 週後可第一次量化驗證 Theme v2 / paper trade / 持倉風控的真實效果。
- **工程量**：M（3 件各 1–2 天）

### P1 — 一週內

#### P1.1 加「相對位置」因子
- **問題**：系統只看 dayHigh，不看 60-day / 季高 / 年高，會在「股票已經漲 30%」時還繼續加分。
- **建議做法**：
  1. ScoreConfigService 加 `scoring.weight_relative_position = 0.10`
  2. JavaStructureScoringEngine 加 helper：取近 60 日最高，計算現價 / 高點 比例。≥ 95% 扣分，80–95% 中性，≤ 80% 加分（拉回機會）。
  3. ChasedHighEntryEngine 同步加 「相對 60 日高 ≥ 95% AND RR < 2.0」 → BLOCK。
- **預期效果**：減少高檔追單套牢，覆蓋拉回低吸。
- **工程量**：M

#### P1.2 加每日強勢股榜 + 命中率回溯
- **問題**：系統候選 vs 當日強勢股無法事後對比，沒辦法迭代。
- **建議做法**：
  1. 新增表 `daily_top_movers (trading_date, symbol, change_pct, volume_ratio, rank)`
  2. 收盤後 batch job 抓 TWSE 漲幅前 30，落表
  3. Dashboard 加「今日候選 vs 強勢股榜重疊率」指標
  4. 連續一週重疊 < 30% → LINE 警示「策略漂移」
- **預期效果**：第一個量化「跟得上熱度嗎」的硬指標。
- **工程量**：M

#### P1.3 拉回站穩 setup type 補強
- **問題**：30 天只有 3 筆 PULLBACK_CONFIRMATION valid，幾乎被 PriceGate 擋掉。
- **建議做法**：
  1. SetupDecisionEngine 加 `PULLBACK_HIGHER_LOW`（拉回更高低點）setup type
  2. PriceGate 對這類 setup 放寬 belowOpen 限制——拉回必然 below 開盤
  3. config: `priceGate.allow_pullback_setup_below_open = true`
- **預期效果**：覆蓋 1/3 一般 PM 會抓的拉回低吸機會。
- **工程量**：M

### P2 — 兩週內

#### P2.1 法人籌碼進評分
- **問題**：T86 只當 context payload，沒進權重。
- **建議做法**：JavaStructureScoringEngine 加項：投信連買 ≥ 3 日 +0.5、外資連買 ≥ 5 日 +0.5、自營商連賣 ≥ 3 日 -0.5。
- **預期效果**：增加籌碼動能訊號權重，台股慣例。
- **工程量**：M

#### P2.2 加「連續虧損 freeze」自動觸發
- **問題**：`trading.cooldown.consecutive_loss_max = 3` 有設定，但實際路徑沒驗證。
- **建議做法**：補一個 PnlGuardService 監聽 position close → 計算連續虧損 → 寫 `trading.status.allow_trade=false`。已有測試 LineSenderRetryTests 模式，仿一份 PnlGuardServiceTests。
- **預期效果**：黑天鵝自動 cut。
- **工程量**：S

#### P2.3 MFE/MAE 真的算
- **問題**：TradeReviewService line 127 註解「v1 不計算」。
- **建議做法**：每日盤後對 OPEN positions 取當日 high/low → 維護 position.high_water、position.low_water → 平倉時寫 trade_review.mfe/mae。
- **預期效果**：可算 Sharpe / Kelly，是 P3 自適應 sizing 的前置條件。
- **工程量**：M

### P3 — 觀察數據後再說

#### P3.1 自適應 sizing（Kelly / win-rate scaling）
- **觀察前提**：P0.1 + P2.3 完成後，累積 ≥ 30 筆 closed trades 才有意義。
- 否則 Kelly 公式分母 (1-p)/p 會被小樣本扭曲。

#### P3.2 Theme v2 切 live
- **觀察前提**：P0.2.1 完成後 shadow log 累積 ≥ 100 個 diff 樣本，比對 v2 vs legacy 勝率。
- 不要 cargo-cult 切換。

#### P3.3 每週 / 每月策略 retrospective
- 把這份 review 變成週報自動產生。看 4 週後再評估。

---

## 附錄：DB 證據快照

```
過去 30 天 final_decision 分布：
  REST  : 38  (76%)
  PLAN  :  8  (16%)   ← 全是 "明日無主規劃標的（評分全 C）"
  WAIT  :  3   (6%)
  WATCH :  1   (2%)
  ENTER :  0   (0%)   ★ 最關鍵發現

過去 30 天 stock_evaluation 最高分：
  max final_rank_score = 7.00       ← B 門檻 7.4 達不到
  max java_structure   = 7.30
  max claude_score     = 8.00 (極少)
  max codex_score      = 8.00 (極少)

今天 (2026-04-27) 各 candidate 分數：
  2337 旺宏 : java 6.80 + claude 7.50 + codex 6.80 → final 6.16  (theme decay -0.88)
  3189 景碩 : java 5.20 + claude 3.00 + codex 3.00 → final 3.00  (claude/codex 未填)
  其餘 7 檔 : java 5.20-6.80 + claude 3.00 + codex 3.00 → final 2.4-3.0

過去 30 天 setup_decision_log 有效 setup：
  BREAKOUT_CONTINUATION  : 39 valid
  PULLBACK_CONFIRMATION  :  3 valid   ← 嚴重不足
  invalid                :  7

過去 30 天 execution_decision_log：
  ENTER (CONFIRMED)      : 36   ← 底層找到 36 個機會
  SKIP (BASE_ACTION_SKIP): 37
  SKIP (VETOED)          : 19
  SKIP (CODEX_VETO)      :  1   ← final 層全部斷掉

過去 30 天 position_review_log：
  INTRADAY STRONG : 180
  INTRADAY EXIT   : 160   ← 沒接 auto-close
  INTRADAY TRAIL  :   7
  DAILY STRONG    :   5
  DAILY EXIT      :   3

過去 30 天 closed positions = 1
  2303 聯電：成本 75.7 / 1000 股 / hold 2 days / pnl -600

過去 30 天 paper_trade = 0           ← 假完成
過去 30 天 theme_shadow_decision_log = 0   ← 假完成

題材覆蓋（30 天）：
  半導體/IC          : 14
  記憶體/儲存        :  8
  PCB/載板/材料      :  4
  AI 伺服器/電腦週邊  :  3
  軍工/航太          :  2
  散熱/機構          :  1
  機器人/自動化      :  1
  其他強勢股        : 17   ← catch-all 過多

49 unique symbols / 60 picks (30d)：5 個 symbol 重複 3 次（6770/3189/8028/2337/2454）
```

---

## 結語

這套系統**工程紀律 8 / 10，賺錢能力 2 / 10**。

問題不是 code 寫不好——FinalDecisionEngine、PriceGate、ThemeStrengthService 都是同業裡相對深的設計。問題是**評分結果出不去 final 那一關**，而這一關的卡點 90% 來自三件事：（1）AI 鏈交付率低；（2）門檻設定按「黑天鵝套利」級別；（3）shadow / paper / 風控訊號沒人接。

P0 做完，這個系統 30 天內會從「0 ENTER」變「5–10 ENTER 小倉」，那時才有資格談量化績效。在那之前，它就是一個寫得很好的研究 + 觀察 dashboard。
