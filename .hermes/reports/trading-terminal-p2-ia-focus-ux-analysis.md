# Trading Terminal P2：Information Architecture / Focus UX 重構分析

## 0. Executive Summary

目前 Trading Terminal 的資料深度已足夠，甚至偏超載。P1 把後端重要 intelligence 補到 web/mobile 後，下一個主要瓶頸不是「缺 widget」，而是：

- 首屏焦點不穩定
- 同一頁同時服務交易、研究、除錯、系統監控
- AI 說明與資料品質資訊過度常駐
- 缺少一致的 urgency / risk ranking
- Mobile 卡片資訊密度已接近不適合盤中快速使用

P2 的方向應該是把 Trading Terminal 從「多資料儀表板」收斂成「急迫性排序的交易 cockpit」。

最重要原則：

1. **盤中只回答：現在能不能打、哪裡有風險、我需不需要動。**
2. **研究、trace、mapping、feature mode、history 全部降到 Review / Drilldown。**
3. **AI 預設輸出不是長解釋，而是 action state + risk reason chips。**
4. **Mobile 預設是 quick-glance，不是完整儀表板。**
5. **所有資訊都要有 urgency ranking；沒有急迫性的資訊不該搶首屏。**

---

## 1. 目前 IA 最大問題 Top 10

### 1. 首頁名義是 Market Mode，但實際混入 Position / Decision / Capital / System

Desktop 首頁已有文字表示「桌面版已收斂為市場模式」，但實際仍同時顯示：

- 今日交易指令
- 最新可操作標的
- 市場狀態
- 持倉卡片
- 觀察清單
- 資金 / 損益
- 題材曝險
- 系統訊號

這讓首頁無法 3 秒回答「今天能不能打」。使用者會被迫在多個任務間切換心智模式。

### 2. 資訊以資料來源分組，而不是以交易任務分組

例如持股頁把資訊分成：

- Health v2
- Adaptive Exit Review
- 持倉明細表
- 歷史持倉

但交易者真正要的是「這一檔現在要續抱、觀察、減碼、出場，還是假跌破」。目前要自行整合多個區塊。

### 3. 決策頁偏歷史 / audit，不像 current decision cockpit

Decision Snapshot、決策趨勢、整點 Gate、監控歷史、通知都在同一頁。資料完整，但第一屏缺少「目前有效決策」的明確層級。

### 4. Review Mode 沒有明確入口

盤後檢討、PnL、missed rallies、forward tracking、strategy tuning、mapping quality、feature modes、shadow validation、postmortem 分散在 AI、決策、系統、設定、隱藏的 PnL 頁中。

### 5. Settings / System / Theme Ops 邊界混亂

`Mapping Quality` 被放到 Settings，但它本質是題材資料品質 / taxonomy observability；`Feature Mode Safety` 放 System 合理，但它若影響正式決策，也應在 Decision / Risk context 中只以異常 badge 呈現。

### 6. AI 資訊過度常駐，形成 AI fatigue

多頁重複出現 AI ready、Claude、Codex、snapshot、trace、summary。正常狀態太常出現會讓真正異常變得不突出。

### 7. 缺少跨 widget 的 single source of urgency

目前 health、adaptive exit、data gaps、decision、system alerts 各自有 badge，但沒有統一排序：

- 哪個要先處理？
- 哪個只是資料不足？
- 哪個是立即風險？
- 哪個只是 debug？

### 8. 同一資訊多處重複呈現但語言不同

持股風險在首頁、持股頁、health-v2、adaptive exit、capital strip 都會出現。Decision 在首頁、決策頁、AI 研究頁也重複出現。重複本身不是問題，問題是各處 summary 不一定同一套語意。

### 9. 歷史與即時資訊混在同一層

例如決策頁同時顯示 current snapshots 與 history；持股頁同時顯示 open position 與 history；系統頁同時顯示 current feature mode 與 probe history。盤中場景應把歷史預設 lazy-load。

### 10. Terminal 缺少「What changed now」層

每次刷新後，使用者需要重新掃所有卡片。真正 terminal 應該凸顯：

- 哪個市場狀態變了？
- 哪檔持股從 HOLD 變 REDUCE？
- 哪個 gate 新增阻擋？
- 哪個資料源 stale？

---

## 2. Mobile UX 最大問題 Top 10

### 1. Overview 首屏承載太多任務

Mobile overview 現在有 market intelligence、theme radar、position focus、data warning，以及摺疊中的 decision、action list、positions、capital。即使摺疊處理已經降低噪音，首屏仍缺少單一焦點。

### 2. 持股卡過大且資訊層級過深

單張 PositionCard 同時包含：

- 損益
- 成本 / 現價
- 距停損
- health-v2
- Adaptive Exit
- 操作按鈕
- strategy / review more

掃 3 檔持股時，使用者要讀太多文字。

### 3. 每張持股卡都固定顯示 4 個 action buttons

`HOLD / ADD / REDUCE / EXIT` 同時出現會造成行動噪音，也會稀釋真正建議減碼 / 出場的 urgency。

### 4. AIStatusPill 重複出現在多個 tab

正常 AI 狀態不該每頁佔視覺層級。應降為小型 reliability indicator；異常才升級。

### 5. Decision Snapshot 在 mobile 上太像 debug card

gate trace / decision trace 摘要對審計有價值，但 mobile 決策頁第一屏應先顯示 current effective decision，而非多筆 snapshot trace。

### 6. Theme Ops mobile 過於研究/營運導向

Hot Group Radar、Promotion Queue、Lifecycle、Research Universe、Build Trace、Mapping Quality 同頁顯示，適合 review，不適合盤中快速判斷主流題材。

### 7. 色彩語意負擔高

目前同時存在：

- 狀態語意：buy 綠、stop 紅
- 台股損益語意：漲紅、跌綠

雖然技術上分離，但 mobile 快速掃視仍容易混淆。

### 8. 卡片重要性不明

所有 card 都是類似重量的深色卡片。Risk alert、normal info、debug details 的視覺權重不夠分明。

### 9. 資訊太常用文字與 key-value 呈現

很多資訊應轉成：

- badge
- heat bar
- radar
- risk count
- freshness dot
- lifecycle chip

而不是 key-value 或長句。

### 10. 缺少 Risk Alert Mode

Mobile 最需要的是「如果只有 5 秒，我該看哪裡？」目前沒有一個固定的 top risk queue。

---

## 3. 認知負擔最大來源

### 來源 A：使用者需要自行合併多個 AI 結論

同一檔持股可能同時有：

- health-v2 action tier
- adaptive exit recommendation
- thesisStatus
- themeLifecycle
- distance to stop
- PnL
- reviewStatus

如果系統沒有給出一個 final position action，使用者就要自行判斷哪個優先。

### 來源 B：資料不足與弱勢訊號混在一起

`DATA_MISSING`、`STALE`、`UNKNOWN`、`NO_RECENT_SIGNAL` 容易被視為負面訊號。必須明確區分：

- 資料品質問題
- 真的轉弱
- 沒有新訊號
- 系統未完成

### 來源 C：AI trace 與 AI decision 混在一起

gateTraceJson / decisionTraceJson 是 audit 層資訊，不應在盤中主流程與 action card 同權重顯示。

### 來源 D：歷史資訊和即時資訊同頁競爭

歷史決策、probe history、position history、notification history 都會讓盤中頁面變成資料庫瀏覽器。

### 來源 E：顏色需要使用者二次解讀

紅/綠在「風險」與「台股漲跌」有不同語意，需要形狀、icon、label 輔助。

---

## 4. 最沒必要盤中顯示的資訊

以下不是沒價值，而是不該出現在盤中第一層：

1. `gateTraceJson` 完整或半完整摘要
2. `decisionTraceJson` 完整或半完整摘要
3. Feature mode 全列表
4. Mapping manual review queue 明細
5. Probe history
6. Market snapshot history
7. Scheduler execution logs
8. AI research 長篇內容
9. 歷史持倉表格
10. PnL 長期統計 / drawdown / paper trade performance
11. Build Trace 明細
12. Promotion Review Queue 全列表
13. score_config 大表
14. 題材對應全表
15. 每張卡片重複的 safetyNote 長句

---

## 5. 最缺少的 urgency / risk ranking

建議建立全系統一致 urgency scale：

### U0：Critical / 立即處理

- HARD_EXIT_ALERT
- EXIT_REVIEW 且距停損 < 2%
- thesis invalidated
- market grade C / risk-off flip
- AI_NOT_READY 但頁面仍顯示交易建議
- critical data gap 影響持股或決策
- quote/API stale 導致現價不可用

呈現：固定置頂、紅色、`!` icon、最多 3 則。

### U1：High / 今日需處理

- REDUCE_REVIEW
- OBSERVE_1D 且轉弱中
- crowdingRisk 高
- themeLifecycle fading / crowded
- fallbackReason 影響正式決策
- liveDecisionAffecting > 0 且功能模式異常

呈現：橘色/黃、清楚 action label。

### U2：Medium / 需觀察

- HOLD 但 data stale
- WAIT / WATCH selected candidates
- theme heat 上升但未進主流
- AI partial ready
- mapping quality warning 不直接影響今天持股

### U3：Low / 正常資訊

- HOLD 且 thesis active
- AI ready
- data fresh
- feature mode read-only
- mapping quality OK

### U4：Debug / Review only

- trace JSON
- full AI logs
- build trace
- scheduler logs
- probe history
- manual review queue detail

---

## 6. 建議的 terminal modes

### 6.1 Market Mode（市場模式）

目的：3 秒知道今天能不能打。

首屏只回答：

- Risk ON / OFF / Neutral
- 市場等級 A/B/C
- 市場階段：開盤 / 主升 / 高檔震盪 / 出貨 / 盤整
- 主流題材是否一致
- AI market bias
- 有沒有資料或系統異常使判斷不可信

建議顯示：

1. **Market Command Strip**
   - `RISK ON/OFF`
   - `Grade A/B/C`
   - `Phase`
   - `AI Ready`
   - `Last update`
2. **Main Bias Card**
   - 一句話：今天可打 / 只觀察 / 休息
   - Top blocker 或 top confirmation
3. **Theme Radar Top 3**
   - heat bar + lifecycle chip + rotation arrow
4. **Action Queue 摘要**
   - 有幾個 BUY / WAIT / RISK
5. **Only-if-abnormal Data Alert**

延後：完整候選股列表、完整持股、資金細節、AI 長解釋。

### 6.2 Position Mode（持股模式）

目的：快速處理風險與持股決策。

首屏回答：

- 哪些危險？
- 哪些續抱？
- 哪些是假跌破？
- 哪些應該減碼？
- 哪些只是資料不足？

建議排序：

1. HARD_EXIT / EXIT_REVIEW
2. REDUCE_REVIEW
3. OBSERVE_1D / thesis weakening
4. distance to stop low
5. HOLD normal

每張持股 compact card 顯示：

- Symbol / name
- final position action
- PnL%
- distance to stop
- thesisStatus
- data quality badge
- one top reason

展開才顯示：health-v2 details、Adaptive Exit details、themeLifecycle、crowding、wave、institutional、safetyNote。

### 6.3 Decision Mode（決策模式）

目的：看懂 AI 最終決策，而不是讀歷史。

首屏回答：

- BUY / WATCH / WAIT / REST
- gate 是否通過
- AI confidence / readiness
- fallback reason
- why selected / why rejected
- risk reason

建議結構：

1. Current Effective Decision
2. Gate Summary：Market / Risk / Position / AI
3. Selected / Rejected / Watch summary
4. Confidence + fallback
5. Drilldown：snapshot list / trace / history

### 6.4 Review Mode（盤後 / 研究模式）

目的：容納低急迫但高價值的資訊。

應移入：

- missed rallies
- forward tracking
- strategy tuning
- mapping quality
- feature modes
- shadow validation
- postmortem
- PnL / paper trades
- history positions
- AI long research
- scheduler / build / probe logs

Review Mode 可以資料密度高，但不應污染盤中 Market / Position / Decision。

---

## 7. 建議的首頁重構

### 目前問題

首頁現在同時像：市場頁、候選股頁、持股摘要、資金頁、系統頁。

### 建議首頁改成 Market Command Center

首屏高度目標：desktop 一屏內完成；mobile 1.5 屏內完成。

#### 第一層：Command Strip

- Market Risk：ON / OFF / Neutral
- Market Grade：A / B / C
- Phase：開盤 / 主升 / 高風險 / 盤整 / 崩盤
- AI：Ready / Partial / Not Ready
- Data：Fresh / Stale / Gap

#### 第二層：Today Decision

- 大字：`今日：可交易 / 只觀察 / 休息`
- 一句理由
- selected count / rejected count

#### 第三層：Theme Radar Top 3

- 題材名稱
- heat bar
- lifecycle chip
- rotation strength arrow
- crowding badge

#### 第四層：Action Queue

- BUY / WAIT / Position Risk 各最多 3 個
- 沒有就顯示「無需動作」

#### 從首頁移除

- 完整持股卡
- 完整資金 / PnL
- 觀察清單長列表
- 系統訊號明細
- AI trace / snapshot detail
- build / feature / mapping 細節

---

## 8. 建議的持股頁重構

### 核心原則

持股頁不應按資料來源分區，而應按「持股處理優先順序」分區。

### 建議結構

#### A. Position Risk Summary

- 持股總數
- exit count
- reduce count
- observe count
- hold count
- data gap count

#### B. Risk Queue

只顯示 U0 / U1：

- symbol
- action
- reason
- next step

#### C. Position Cards sorted by urgency

Compact card：

- Symbol
- PnL%
- distance to stop
- final action
- thesis badge
- data badge

Expanded card：

- health-v2 technical detail
- Adaptive Exit detail
- invalidation condition
- theme lifecycle
- institutional alignment
- safety note

#### D. History / Audit

預設 collapsed / lazy load。

### 建議整合 Health + Adaptive Exit

不要讓 Health v2 與 Adaptive Exit 各自成為大 panel。應合成每檔的：

- `Price/Stop health`
- `Thesis health`
- `Theme health`
- `Data quality`
- `Final suggested action`

---

## 9. 建議的決策頁重構

### 目前問題

Decision Snapshot 直接列多筆，容易讓第一屏變成 audit log。

### 建議結構

#### A. Current Effective Decision Card

- `BUY / WATCH / WAIT / REST`
- source：OPENING / MIDDAY / POSTMARKET
- aiReadinessMode
- fallbackReason
- last updated
- confidence / reliability

#### B. Gate Trace Summary（非 raw trace）

四顆 gate：

- Market Gate：PASS / BLOCK
- Risk Gate：PASS / BLOCK
- Position Gate：PASS / BLOCK
- AI Gate：READY / PARTIAL / FAIL

#### C. Selected / Rejected / Watch

- selected top 3
- rejected top 3 reason categories
- watch top 3

#### D. Drilldown

- Recent snapshots
- gateTraceJson
- decisionTraceJson
- decision timeline
- hourly / monitor histories
- notifications

---

## 10. 建議的 mobile quick-glance mode

### 目標

打開手機 3–5 秒內回答：

1. 今天能不能交易？
2. 有沒有持股要處理？
3. AI / data 是否可信？
4. 主流題材是否還在？

### Layout

#### Top fixed mini strip

- `RISK ON/OFF`
- `A/B/C`
- `AI Ready`
- `Fresh 09:31`

#### First card：Need Action?

- 若有 U0/U1：列 Top 1–3
- 若無：顯示 `無需立即處理`

#### Second card：Market Bias

- `可交易 / 只觀察 / 休息`
- 一句理由

#### Third card：Theme Heat

- Top 3 heat bars

#### Fourth card：Position Summary

- Exit / Reduce / Hold counts

### 不顯示

- long AI summary
- full position details
- feature modes
- mapping queue
- trace JSON
- history tables

---

## 11. 建議的 card hierarchy

### Level 0：Command / Alert

用途：立即處理。

樣式：最高對比、置頂、最多 3 則。

### Level 1：Decision Card

用途：當前模式結論。

樣式：大字 + 一句理由 + 主要 action。

### Level 2：Compact Data Card

用途：列表掃視。

樣式：symbol、badge、1–2 指標、短理由。

### Level 3：Expanded Explanation

用途：點開看更多。

樣式：3–6 個 key-value，不放長文。

### Level 4：Audit / Debug

用途：研究 / 盤後 / 除錯。

樣式：collapsible / lazy-load / secondary page。

---

## 12. 建議的 AI visualization

### 用 flags 取代 prose

預設只顯示：

- `HOLD`
- `OBSERVE_1D`
- `REDUCE_REVIEW`
- `EXIT_REVIEW`
- `RISK_OFF`
- `STALE_DATA`
- `AI_PARTIAL`

### 用 reason chips 取代長句

最多 3 個：

- `Theme ACTIVE`
- `Structure intact`
- `Crowding high`
- `Below invalidation`
- `Data stale 2D`
- `Gate blocked`

### 用 confidence / reliability indicator

- `AI Ready`
- `Partial AI`
- `Fallback`
- `Data Gap`
- `Stale`

### Full explanation 只放 drawer

- gateTraceJson
- decisionTraceJson
- long rationale
- raw AI research

---

## 13. 建議的 color / risk system

### 風險色彩與 PnL 色彩分離

#### Risk / action colors

- Critical：紅 + `!`
- High：橘
- Medium：黃
- Info：藍
- Safe：綠
- Debug / inactive：灰

#### PnL colors

- 台股慣例：漲紅、跌綠可保留
- 但必須加 `+/-`、箭頭、PnL label，避免和 risk color 混淆

### 不只依賴顏色

所有 critical/warn 都應同時有：

- icon
- label
- position / border priority
- short reason

### 建議避免

- 大量 neon glow 常駐
- warn/info 都使用類似亮色
- normal AI ready 使用高權重顏色

---

## 14. 建議的 compact / expanded interaction

### Compact 預設

所有列表頁預設 compact：

- Market：top 3 themes
- Position：每檔一行 / 一小卡
- Decision：current decision + gate summary

### Expand on demand

點擊卡片展開：

- 只展開該卡
- 顯示 reason chips + 3–5 key facts
- 不顯示完整 raw JSON / 長文

### Drilldown

再點「詳情」進入：

- full trace
- full research
- history
- audit

### Risk alert exception

U0/U1 可自動展開或置頂，但最多 3 則，避免 alert spam。

---

## 15. 哪些 widget 應 lazy load

建議 lazy-load：

1. Decision history
2. Hourly gate history
3. Monitor decision history
4. Notification history
5. Position history
6. PnL history / long-term stats
7. Paper trade stats
8. AI research logs
9. Full Decision Snapshot list
10. gateTraceJson / decisionTraceJson detail
11. Feature mode full list
12. Mapping manual review queue
13. Theme mapping table
14. Build trace
15. Scheduler logs
16. Probe history
17. Market snapshot history

---

## 16. 哪些資訊應 event-driven 顯示

### 進入頁面才載入

- Position health
- Adaptive exit
- Decision snapshot
- Mapping quality
- Feature mode safety

### 展開才載入

- history
- trace
- full research
- manual queue
- logs

### 狀態變更才提示

- AI ready → partial / fail
- market grade changes
- monitor mode changes
- position action tier changes
- data freshness becomes stale
- quote unavailable
- selected/rejected count changes materially

### 手動刷新才更新

- Review data
- settings/config data
- scheduler logs

---

## 17. 哪些資訊應只在異常時顯示

1. Feature Mode Safety：只有 liveDecisionAffecting > 0 或模式異常時進入盤中頁
2. Mapping Quality：只有影響今日候選 / 持股題材時顯示 alert
3. Data Gaps：沒有 gap 時只顯示小綠點，不顯示整張卡
4. AIStatus：正常只顯示小 badge；partial/fail 才展開
5. Probe / external health：失敗才顯示
6. Build Trace：失敗/partial 才顯示
7. Scheduler：任務 overdue/fail 才顯示在首頁
8. Long safetyNote：只在 review/debug 顯示
9. No recent signal：只有與持股 thesis 有關才提示
10. Stale narrative：只有 staleness 會影響決策時升級

---

## 18. 哪些資訊應該從首頁移除

建議從首頁移除或降級：

- 完整持股卡 → 改成持股風險摘要
- 完整資金 / 損益 → 改成 exposure summary
- 觀察清單長列表 → 移到題材 / 候選頁
- 系統訊號明細 → 只保留 critical count
- full AI status pills → 正常狀態縮成一個 badge
- Decision trace / snapshot detail → 決策頁 drilldown
- Feature modes → System / Review；異常才首頁 alert
- Mapping quality → Theme / Review；異常才首頁 alert
- Build trace / scheduler logs → Review / System
- History tables → Review

---

## 19. TradingView / Bloomberg / IBKR / crypto terminal 可借鏡模式

### Bloomberg

可借鏡：

- Command-first terminal
- 高密度但 layout 穩定
- 短 label 優先於長文
- 狀態條常駐

應用：

- 用 top command strip 統一 Market / AI / Data / Risk
- 用固定位置顯示 critical alerts
- 降低卡片跳動與資訊重排

### IBKR / TWS

可借鏡：

- Portfolio risk 優先
- 持倉、損益、風險、order/action 清楚分層
- 交易操作不被研究敘事淹沒

應用：

- Position Mode 以風險排序，不以資料來源排序
- Adaptive Exit 必須轉成 action state
- 研究內容放 drilldown

### TradingView

可借鏡：

- watchlist / heatmap / chart-first scan
- 視覺化趨勢與題材熱度
- Drilldown on demand

應用：

- Theme Radar 用 heat bars / lifecycle chips / rotation arrows
- 候選股用 watchlist compact row
- AI rationale 不在主列表長文顯示

### Crypto terminal

可借鏡：

- 強烈的 real-time urgency
- 「what changed now」
- alert queue
- risk/liquidation style warnings

應用：

- 加入 Changed Since Last Check 概念
- risk alert mode 置頂
- 市場 regime flip / position tier change 即時凸顯

---

## 20. 最終 Trading Terminal Vision

Trading Terminal 應該不是「把所有 AI intelligence 都顯示出來」的 dashboard，而是：

> 一個以急迫性排序的台股 AI 交易 cockpit，盤中讓使用者 3 秒知道能不能打、哪裡有風險、是否需要行動；盤後才展開研究、審計、trace、mapping、策略調校。

### Vision 架構

#### Market Mode

一句話：今天可不可以承擔風險。

- Risk ON/OFF
- Market phase
- Mainstream theme
- AI bias
- Data reliability

#### Position Mode

一句話：持股哪些要處理。

- Risk queue
- Position action state
- Thesis / health / data quality combined
- Expand for reasoning

#### Decision Mode

一句話：AI 為什麼給這個結論。

- BUY/WATCH/WAIT/REST
- Gate pass/block
- confidence / fallback
- selected/rejected reason chips
- Trace only on drilldown

#### Review Mode

一句話：今天學到什麼、明天怎麼調。

- PnL
- missed rallies
- forward tracking
- strategy tuning
- mapping quality
- feature modes
- shadow validation
- postmortem

### North Star

每次盤中打開 terminal，只需要回答四件事：

1. **市場：現在能不能打？**
2. **持股：哪個風險最大？**
3. **決策：AI 結論是否可信？**
4. **行動：我現在要做什麼？**

任何不能幫助這四個問題的資訊，都應該延後、collapse、drilldown、lazy-load，或只在異常時出現。
