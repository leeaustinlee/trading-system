# Intelligence Terminal UX Spec

> 本文件為規劃輸出，不包含實作、不變更 backend 策略邏輯、不新增 scheduler / scoring / strategy。

## Goal

將現有 trading-system backend 已經 freeze-ready 的 intelligence backbone，轉成「市場 Intelligence 作戰室」型態的 terminal-first UI/UX prototype。

重點不是券商下單，而是讓使用者在手機與桌面端一眼判讀：
- 市場主流與風險狀態
- 題材輪動與 theme lifecycle
- 持股 thesis 是否成立
- adaptive exit 為何 HOLD / OBSERVE_1D / REDUCE_REVIEW / EXIT_REVIEW
- Narrative / 股癌 / KOL 是否 stale
- intelligence backbone 是否正常更新

## Scope Guardrails

本次明確不做：
- 不新增新的 trading strategy
- 不新增新的 AI scoring
- 不新增新的 selection logic
- 不新增新的 AI flow
- 不新增新的 scheduler
- 不新增新的 DB schema（除非之後 UI 聚合必要，且本輪先不做）
- 不自動 commit

本次 focus：
- Visualization
- UX
- Operational review
- Intelligence readability
- Mobile-first terminal UI

---

## 1. 設計定位：市場 Intelligence 作戰室

不是 broker order entry screen，而是：
- Narrative-aware
- Theme-aware
- Adaptive swing intelligence terminal

使用者要在首頁 5~10 秒內回答：
1. 現在市場是 Risk On 還是 Risk Off
2. 主流 theme 是誰、是否擴散、是否轉弱
3. 哪些題材在發酵、哪些已過熱
4. 持股 thesis 是否仍成立
5. adaptive exit 當前結論與原因
6. Narrative / KOL 資料是否 stale
7. intelligence backbone 今日是否有 build evidence

---

## 2. 現況盤點：前端基礎與可延伸性

### 2.1 現有 UI 基礎

已存在：
- `src/main/resources/static/mobile.html`

判讀：
- 檔案很大（1831 lines）
- 已有 dark terminal 風格、hero / card / badge / chip / mobile-first 設計語彙
- 適合延伸成 intelligence terminal prototype
- 不適合直接在本輪切 React / Vue；因為現有靜態頁與 API 已可快速驗證 UX

結論：
- P1/P2 建議延續 `mobile.html` + vanilla JS
- 先將資訊架構重排，建立 war-room readability
- 等 freeze + daily review 穩定後，再評估是否拆 component 化或導入框架

### 2.2 現有前端技術方向建議

本輪建議：
- HTML + CSS + vanilla JS
- Polling-based data refresh
- 不需要 websocket

原因：
- 當前資料更新節奏不是秒級 tick UI
- 大多是 polling 即可（15s / 30s / 60s）
- 現階段主要問題是 readability / aggregation，不是實時推播延遲
- websocket 會增加 state complexity，但現有 intelligence backbone 不需要那麼高頻互動

---

## 3. API 盤點與 UI 支撐能力

以下只盤點本次需求直接相關的 API。

### 3.1 Market / Theme

1. `/api/theme-intelligence/summary`
- 已可用
- 用途：Theme intelligence snapshot summary
- 可支撐：Theme Radar、Theme drill-down、持股 thesis theme context
- 缺點：較偏 theme snapshot，未必直接是首頁最佳 market context 聚合

2. `/api/ops/theme-freshness`
- 已可用
- 用途：各 intelligence layer freshness + latest build metadata
- 可支撐：Freshness / Build Health Board
- 狀態：本輪已補 latestBuildTraceId / status / duration / counts，可直接用

3. `/api/ops/build-status`
- 已可用
- 用途：某日期 build evidence 與 freshness 彙總
- 可支撐：Freshness Board drill-down

4. `/api/ops/stale-data-report`
- 已可用
- 用途：stale layer 數量與清單
- 可支撐：首頁健康警示 / drill-down 的 stale report

5. `/api/theme-replay?date=YYYY-MM-DD`
- 已可用
- 用途：theme replay summary
- 可支撐：Theme Radar、Theme drill-down、rotation panel

6. `/api/theme-replay/themes/{themeTag}/timeline?date=YYYY-MM-DD`
- 已可用
- 用途：單一 theme timeline
- 可支撐：Theme drill-down page

7. `/api/themes/lifecycle?date=YYYY-MM-DD`
- 已可用
- 用途：theme lifecycle 狀態
- 可支撐：Theme Radar lifecycle color mapping

8. `/api/hot-groups/radar?date=YYYY-MM-DD&phase=POSTMARKET`
- 已可用
- 用途：hot themes + signals
- 可支撐：Theme Radar / rotation / mainstream themes

9. `/api/hot-groups/candidate-feed?date=YYYY-MM-DD&phase=POSTMARKET`
- 已可用
- 用途：candidate feed by theme
- 可支撐：theme member drill-down / candidate by theme

### 3.2 Narrative / KOL

10. `/api/kol-signals/dashboard`
- 已可用
- 用途：KOL signal count / theme snapshot / latestSignalDate / staleDays / warning
- 可支撐：Narrative Center / freshness label

11. `/api/narrative/dashboard`
- 已可用
- 用途：narrative rows / latest signal date / stale data warning
- 可支撐：Narrative Center
- 注意：若無新資料仍會有 warning，可用來顯示 `NO_RECENT_SIGNAL`

12. `/api/narrative-thesis/open-positions`
- 已可用
- 用途：open positions 的 narrative thesis
- 可支撐：Portfolio Thesis Board 的 narrative heat / thesis backing

### 3.3 Portfolio / Adaptive

13. `/api/position-thesis-ledger/open`
- 已可用
- 用途：open position thesis ledger
- 可支撐：Portfolio Thesis Board

14. `/api/adaptive-exit-review/open-positions`
- 已可用
- 用途：adaptive exit decision for open positions
- 可支撐：Adaptive Exit Board
- 很關鍵：已有 decision + reasons，適合直接 UI 化

15. `/api/portfolio/review`
- 已可用
- 用途：portfolio review intelligence
- 可支撐：portfolio board 概覽與 review drill-down

16. `/api/positions/open`
- 已可用
- 用途：open positions raw list
- 可支撐：持股基本卡片、現價、成本、停損停利等底層資訊

17. `/api/positions/live-quotes`
- 已可用
- 用途：open positions 即時報價
- 可支撐：mobile portfolio 即時價位

18. `/api/capital/summary`
- 已可用
- 用途：cash / exposure / capital summary
- 可支撐：首頁 market context 下方的 portfolio status strip

### 3.4 Market Context

19. `/api/dashboard/current`
- 已可用
- 用途：市場快照 + trading state + final decision + monitor + candidates
- 是目前最接近首頁聚合 API 的現成入口
- 可支撐：首頁 top board 的基底
- 缺點：命名偏舊 dashboard，資料語義偏交易狀態，不完全等於 intelligence war-room

20. `/api/market/current?preferToday=true`
- 已可用
- 用途：最新市場快照
- 可支撐：Market Context Board 基本市場結構

21. `/api/monitor/current`
- 已可用
- 用途：trading/monitor state
- 可支撐：monitor mode / current operational state

22. `/api/monitor/decisions/current`
- 已可用
- 用途：current monitor decision
- 可支撐：market operation strip / monitor mode badge

23. `/api/candidates/current`
- 已可用
- 用途：current candidates
- 可支撐：首頁 watchlist / candidate preview

24. `/api/candidates/latest/live-quotes` 或 `/api/candidates/live-quotes`
- 已可用
- 用途：候選股即時報價
- 可支撐：candidate quick board

### 3.5 額外相關 API

25. `/api/dashboard/theme-first`
- API: `/api/dashboard/theme-first?date=...`
- 用途：theme-first metadata
- 目前較像輔助 metadata，不是主板核心

26. `/api/theme-intelligence/{theme}`
- 已可用
- 可作單 theme 詳細頁

27. `/api/themes/lifecycle/{themeTag}`
- 已可用
- 可作單 theme 詳細頁

28. `/api/hot-groups/by-theme`
- 已可用
- 可作 theme member / signal drill-down

---

## 4. 哪些現有 API 已足夠支撐 UI

### 4.1 已足夠直接支撐（可先做 UI，不必等 backend）

1. Freshness / Build Health Board
- `/api/ops/theme-freshness`
- `/api/ops/build-status`
- `/api/ops/stale-data-report`

2. Theme Radar 基礎版
- `/api/theme-intelligence/summary`
- `/api/theme-replay`
- `/api/themes/lifecycle`
- `/api/hot-groups/radar`

3. Narrative Center 基礎版
- `/api/kol-signals/dashboard`
- `/api/narrative/dashboard`
- `/api/narrative-thesis/open-positions`

4. Portfolio Thesis Board
- `/api/position-thesis-ledger/open`
- `/api/positions/open`
- `/api/positions/live-quotes`

5. Adaptive Exit Board
- `/api/adaptive-exit-review/open-positions`

6. 基本首頁 Context Strip
- `/api/dashboard/current`
- `/api/market/current?preferToday=true`
- `/api/capital/summary`
- `/api/candidates/current`

### 4.2 目前不足，建議之後補 aggregation endpoint 的地方

以下不是本輪要做，但規劃上應標示：

1. Market Context Board 專用 aggregation endpoint
建議未來補：
- `/api/intelligence/market-context`

原因：
目前首頁要拼很多來源：
- dashboard/current
- market/current
- monitor/current
- monitor/decisions/current
- candidates/current
- capital/summary
- possibly theme replay / hot-groups

前端可以先組，但若正式化，應有單一 market-context 聚合 API。

2. Theme Radar unified endpoint
建議未來補：
- `/api/intelligence/theme-radar`

原因：
目前 Theme Radar 需要 join：
- theme-intelligence/summary
- theme-replay
- themes/lifecycle
- hot-groups/radar
- maybe kol-signals/themes
- maybe narrative/dashboard

前端可先 merge，但後續正式化會需要單一 terminal-oriented payload。

3. Portfolio Intelligence Board aggregation endpoint
建議未來補：
- `/api/intelligence/portfolio-board`

原因：
目前要 join：
- positions/open
- positions/live-quotes
- position-thesis-ledger/open
- adaptive-exit-review/open-positions
- narrative-thesis/open-positions
- portfolio/review

4. Narrative Center unified endpoint
建議未來補：
- `/api/intelligence/narrative-center`

原因：
目前需 join：
- kol-signals/dashboard
- narrative/dashboard
- narrative-thesis/open-positions

但本輪先不要求 backend 新增。

---

## 5. 首頁資訊架構（Home / War Room）

### 首頁應放的資訊（必須一眼看到）

1. Market Context Board
2. Theme Radar（top 3~6 themes）
3. Portfolio Thesis Board（只列 open positions）
4. Adaptive Exit Board（只列 open positions）
5. Freshness / Build Health Board
6. Narrative Center 摘要（最新 2~3 個 narrative/KOL 卡片）

### 應 drill-down 的資訊

1. Theme 詳細 timeline
- `/api/theme-replay/themes/{themeTag}/timeline`
- `/api/theme-intelligence/{theme}`
- `/api/themes/lifecycle/{themeTag}`
- `/api/hot-groups/by-theme`

2. Narrative 詳細列表
- `/api/narrative/dashboard`
- `/api/kol-signals/dashboard`
- `/api/kol-signals/themes`

3. Portfolio 單檔詳情
- `/api/position-thesis-ledger/{symbol}`
- `/api/adaptive-exit-review/{symbol}`

4. Build health 詳細頁
- `/api/ops/build-status`
- `/api/ops/build-traces`
- `/api/ops/stale-data-report`

---

## 6. UI 區塊規格

## 6.1 Market Context Board

### 目的
首頁最上方，用一句話 + 4~6 個 compact metrics 告訴使用者現在市場的 operational regime。

### 建議資料來源
優先：
- `/api/dashboard/current`
補充：
- `/api/market/current?preferToday=true`
- `/api/monitor/current`
- `/api/monitor/decisions/current`
- `/api/capital/summary`

### 顯示欄位
- Market Grade / 市場強弱
- Risk On / Risk Off
- Market Breadth（若現有 payload 無，先以 marketGrade + decision + theme breadth proxy 顯示）
- TAIEX 結構（若 payload 有 marketPhase / marketState，先映射）
- Leadership（目前主流 theme）
- Rotation（前 2~3 個輪動方向）
- Monitor Mode
- Open Positions Count / Exposure / Available Cash

### UI 形式
手機：
- Hero card + 2x2 quick metrics + one-line rotation ticker

桌面：
- 上方橫條 + 左 Hero + 右側 metrics grid

### 示例文案
- Risk: ON
- Breadth: Strong
- Leadership: AI Server
- Rotation: CPO → AI Server → Low Orbit

### 缺口
- 真正的 Breadth / Rotation 摘要目前沒有單一聚合 payload
- 前端 P1 可先用 hot-groups + theme replay summary 推導文本
- 後續才考慮補 `/api/intelligence/market-context`

---

## 6.2 Theme Radar（核心）

### 目的
這是整套 terminal 的核心區塊。

### 每個 Theme 卡片顯示
- Theme Name
- Lifecycle
- Heat
- Crowding
- Rotation Strength
- Narrative Heat
- Wave Strength
- Leadership
- Direction arrow（↑ / → / ↓）

### 建議 color mapping
- EARLY = 藍
- EMERGING = 青
- ACTIVE = 綠
- MAINSTREAM = 黃
- CROWDED = 橘
- FADING = 紅
- DEAD = 灰

### 建議資料合成
- lifecycle: `/api/themes/lifecycle`
- heat/rotation/wave proxy: `/api/theme-replay`
- mainstream/theme members: `/api/hot-groups/radar`
- narrative heat proxy: `/api/theme-intelligence/summary` + `/api/kol-signals/dashboard`

### UI 形式
手機：
- 垂直堆疊卡片
- 每卡頂部：Theme + lifecycle badge + arrow
- 中段：heat/crowding/wave 3-bar compact visualization
- 下段：leadership / narrative / members count

桌面：
- 左側列表 + 中央 heatmap table
- 支援按 lifecycle / heat / rotation 排序

### 首頁只顯示
- top 3~6 themes

### drill-down 顯示
- 單 theme timeline
- members
- why hot / why fading
- latest narrative backing

---

## 6.3 Narrative Center

### 目的
讓使用者一眼知道：
- 股癌/KOL 最近主題
- 最新 signal date
- stale 幾天
- 現在的 narrative 是否還在支撐主流 theme

### 資料來源
- `/api/kol-signals/dashboard`
- `/api/narrative/dashboard`
- `/api/narrative-thesis/open-positions`

### 顯示內容
- 最新 2~3 筆 narrative / KOL topic
- 最新 signal date
- staleDays
- warning / NO_RECENT_SIGNAL

### 無資料時 UX 規則
不要空白，顯示：
- NO_RECENT_SIGNAL
- Last updated X days ago

### UI 形式
手機：
- Timeline 卡片
- 每張卡簡寫：EP / topic / freshness status

桌面：
- 左 narrative timeline
- 右 freshness panel

---

## 6.4 Portfolio Thesis Board

### 目的
每檔持股一眼看到 thesis 是否還成立。

### 資料來源
- `/api/position-thesis-ledger/open`
- `/api/positions/open`
- `/api/positions/live-quotes`
- `/api/narrative-thesis/open-positions`

### 每檔顯示
- Symbol
- Theme
- Lifecycle
- WavePhase
- ThesisStatus
- Narrative Heat
- Current PnL / price vs entry
- manual confirm / production safety flags（若已有）

### 示例
- 2303
- Theme: AI Server
- Lifecycle: ACTIVE
- Wave: MID_TREND
- Thesis: ACTIVE
- Narrative: Warm

### UI 形式
手機：
- 每檔一張 compact card
- 頂部顯示 symbol / thesis status
- 中部顯示 theme/lifecycle/wave
- 下部顯示 narrative heat / PnL

桌面：
- table-like cards 或 dense grid

---

## 6.5 Adaptive Exit Board

### 目的
這是系統 edge，必須非常清楚顯示「結果 + 原因」。

### 資料來源
- `/api/adaptive-exit-review/open-positions`
- 補充可串 `/api/portfolio/review`

### 每檔顯示
- Symbol
- Decision: HOLD / OBSERVE_1D / REDUCE_REVIEW / EXIT_REVIEW
- Why（至少 2~3 bullet reasons）
- Theme status
- Structure status
- Historical washout / replay evidence（若 payload 有）

### UX 規則
結果一定大字顯示，原因一定可直接看到，不要藏太深。

### 示例
1582
OBSERVE_1D
Reason:
- structure intact
- theme ACTIVE
- historical washout evidence

### UI 形式
手機：
- 單卡大 decision badge + reason bullets

桌面：
- 左 decision columns，右 reason drawer

---

## 6.6 Freshness / Build Health Board

### 目的
確認 intelligence backbone 是否正常更新。

### 資料來源
- `/api/ops/theme-freshness`
- `/api/ops/build-status`
- `/api/ops/stale-data-report`

### 顯示內容
每個 layer 顯示：
- layer name
- LIVE / STALE / EMPTY
- last build
- last data date
- staleDays
- latest build status

### 示例
- ThemeReplay LIVE
- HotGroup LIVE
- Narrative STALE
- KOL EMPTY

### UI 形式
手機：
- compact status list
- 顏色標記 LIVE/STALE/EMPTY

桌面：
- right rail health panel

---

## 7. Mobile Layout 草圖

```text
┌──────────────────────────────┐
│ Intelligence War Room        │
│ Risk ON · Breadth Strong     │
│ Leadership AI Server         │
│ Rotation CPO → AI Server     │
├──────────────────────────────┤
│ Market Context Board         │
│ Grade A | Monitor WATCH      │
│ Exposure 42% | Cash 38%      │
├──────────────────────────────┤
│ Theme Radar                  │
│ AI Server   ACTIVE      ↑    │
│ Low Orbit   EMERGING    ↑    │
│ CPO         CROWDED     ↓    │
├──────────────────────────────┤
│ Portfolio Thesis Board       │
│ 2303 ACTIVE / MID_TREND      │
│ 3017 ACTIVE / EXTENDED       │
├──────────────────────────────┤
│ Adaptive Exit Board          │
│ 1582 OBSERVE_1D              │
│ - structure intact           │
│ - theme ACTIVE               │
├──────────────────────────────┤
│ Narrative Center             │
│ EP663 AI Server 資金回流      │
│ EP662 低軌開始發酵            │
│ STALE 2 days / NO_RECENT...  │
├──────────────────────────────┤
│ Freshness / Build Health     │
│ ThemeReplay LIVE             │
│ HotGroup LIVE                │
│ Narrative STALE              │
└──────────────────────────────┘
```

### Mobile UX 原則
- 一屏先看到 Market Context + Theme Radar 頭部
- 其餘往下滑
- 卡片高度短、資訊密度高
- dark mode + terminal typography

---

## 8. Desktop Layout 草圖

```text
┌────────────────────────────────────────────────────────────────────┐
│ Intelligence War Room                                             │
│ Risk ON | Breadth Strong | Leadership AI Server | Rotation ...    │
├───────────────────────────────┬────────────────────────────────────┤
│ Market Context Board          │ Freshness / Build Health Board     │
│ - market grade                │ ThemeReplay LIVE                   │
│ - monitor mode                │ Lifecycle LIVE                     │
│ - exposure / cash             │ Narrative STALE                    │
├───────────────────────────────┼────────────────────────────────────┤
│ Theme Radar                   │ Narrative Center                   │
│ theme list / heatmap          │ latest episodes / stale warnings   │
│ AI Server ACTIVE ↑            │ EP663 ...                          │
│ Low Orbit EMERGING ↑          │ EP662 ...                          │
├───────────────────────────────┼────────────────────────────────────┤
│ Portfolio Thesis Board        │ Adaptive Exit Board                │
│ symbol / theme / thesis       │ HOLD / OBSERVE_1D / why            │
│ lifecycle / wave / pnl        │ reason bullets                     │
└───────────────────────────────┴────────────────────────────────────┘
```

### Desktop UX 原則
- 左邊看市場與 theme
- 右邊看 freshness 與 narrative
- 下方看 portfolio 與 adaptive exit

---

## 9. Theme Radar 設計細節

### Card 欄位排序
1. Theme Name
2. Lifecycle badge
3. Direction arrow
4. Heat bar
5. Crowding bar
6. Narrative heat chip
7. Leadership / member count
8. last update

### 互動
- 點擊 theme → drill-down
- 顯示 timeline / members / related narratives

### 排序優先級（首頁）
1. ACTIVE / EMERGING 優先
2. heat 高但未過熱優先
3. replay / hot-group / intelligence 三方交集優先

---

## 10. Narrative Center 設計細節

### 首頁摘要模式
- 最多 3 張 narrative item
- 每張包含：source / episode / theme / freshness

### 空狀態規則
- 不留白
- 顯示最後更新時間與 staleDays
- 明確標 `NO_RECENT_SIGNAL`

### drill-down
- narrative timeline
- KOL themes
- open positions narrative thesis link

---

## 11. Portfolio Thesis Board 設計細節

### 首頁摘要模式
每檔卡片：
- symbol / stockName
- thesis status
- theme / lifecycle
- wave phase
- current pnl
- narrative backing brief

### drill-down
- 單檔 thesis ledger
- adaptive exit detail
- review notes

---

## 12. Adaptive Exit Board 設計細節

### 首頁摘要模式
每檔只顯示：
- symbol
- decision
- top 2~3 reasons

### 顏色語意
- HOLD = 綠
- OBSERVE_1D = 黃
- REDUCE_REVIEW = 橘
- EXIT_REVIEW = 紅

### drill-down
- historical washout evidence
- theme + structure context
- linked review item

---

## 13. Freshness Board 設計細節

### 首頁摘要模式
每列：
- layer
- LIVE / STALE / EMPTY badge
- staleDays
- latestBuildStatus
- lastDataDate

### drill-down
- build traces list
- stale data report
- latest error / warning if exists

---

## 14. API Mapping Table

| UI 區塊 | 主要 API | 補充 API | 是否現況可用 | 備註 |
|---|---|---|---|---|
| Market Context Board | `/api/dashboard/current` | `/api/market/current?preferToday=true`, `/api/monitor/current`, `/api/monitor/decisions/current`, `/api/capital/summary`, `/api/candidates/current` | 可用 | 前端需做輕量聚合 |
| Theme Radar | `/api/theme-intelligence/summary` | `/api/theme-replay`, `/api/themes/lifecycle`, `/api/hot-groups/radar` | 可用 | 建議未來補 unified endpoint |
| Theme Drill-down | `/api/theme-intelligence/{theme}` | `/api/theme-replay/themes/{themeTag}/timeline`, `/api/themes/lifecycle/{themeTag}`, `/api/hot-groups/by-theme` | 可用 | 可先用 |
| Narrative Center | `/api/kol-signals/dashboard` | `/api/narrative/dashboard`, `/api/narrative-thesis/open-positions` | 可用 | 空狀態需前端 UX 處理 |
| Portfolio Thesis Board | `/api/position-thesis-ledger/open` | `/api/positions/open`, `/api/positions/live-quotes`, `/api/narrative-thesis/open-positions` | 可用 | 前端需 merge |
| Adaptive Exit Board | `/api/adaptive-exit-review/open-positions` | `/api/portfolio/review` | 可用 | 原因顯示是核心 |
| Freshness Board | `/api/ops/theme-freshness` | `/api/ops/build-status`, `/api/ops/stale-data-report`, `/api/ops/build-traces` | 可用 | backbone ready |
| Candidates Preview | `/api/candidates/current` | `/api/candidates/live-quotes` | 可用 | 首頁次要區塊 |
| Capital Strip | `/api/capital/summary` | - | 可用 | 首頁摘要 |

---

## 15. 建議技術架構

### 本輪建議
- 延續 `src/main/resources/static/mobile.html`
- 採用 vanilla JS
- API polling
- 不導入 websocket
- 不切 React/Vue

### 原因
1. 現有 mobile.html 已有 terminal dark mode 基底
2. 本輪任務是 UX prototype，不是 SPA 架構重寫
3. API 大多已 ready，先驗證資訊架構比較重要
4. websocket 不是當前必要條件

### 若要做 component 分層（仍用 vanilla）
可拆以下模組：
- `market-context-board`
- `theme-radar`
- `narrative-center`
- `portfolio-thesis-board`
- `adaptive-exit-board`
- `freshness-board`
- `api-client`
- `state-store`

### Polling 建議
- Market Context：15~30 秒
- Positions live quotes：15~30 秒
- Theme / Narrative / Freshness：60 秒
- Build health：60 秒

### 何時才需要 websocket
只有以下情況才值得：
- 真的要秒級 market pulse
- 要 push build completion toast
- 要多人協作/同屏更新

目前不需要。

---

## 16. 哪些資訊放首頁，哪些 drill-down

### 首頁必放
- Risk / Market grade / Monitor mode
- top themes（3~6）
- open positions thesis
- adaptive exit decisions
- freshness summary
- narrative freshness + latest topics

### drill-down
- 單一 theme timeline
- 單一持股 thesis 詳情
- adaptive exit 全理由
- stale/build trace 歷史
- narrative full timeline

---

## 17. 建議實作順序

### P1
- Market Context Board
- Theme Radar
- Freshness Board

理由：
- 先把「市場現在怎樣」與「backbone 有沒有活著」做到一眼可判讀
- 這是 intelligence war room 的第一層

### P2
- Narrative Center
- Portfolio Thesis Board

理由：
- 再把 narrative backing 與持股 thesis 串上
- 讓市場脈絡與持倉 thesis 對齊

### P3
- Adaptive Exit Visual Layer
- Theme / Position drill-down pages

理由：
- 這是 edge layer
- 必須在首頁可讀後再深化原因視覺化

### P4（可選，freeze 後再評估）
- 統一 aggregation endpoints：
  - `/api/intelligence/market-context`
  - `/api/intelligence/theme-radar`
  - `/api/intelligence/portfolio-board`
  - `/api/intelligence/narrative-center`
- 再考慮桌面優化與 component 化

---

## 18. 結論

目前 backend intelligence backbone 已經足以支撐一版 high-readability 的 intelligence terminal prototype。

最重要結論：
1. 現有 API 已足夠做第一版 UI prototype
2. 最大缺口不是資料不存在，而是 payload 分散，需要前端 aggregation
3. 本輪不需要 websocket，也不需要 React/Vue 重寫
4. 最快路徑是延伸 `mobile.html`，先做 war-room 資訊架構重排
5. 真正需要新增的 backend，不是 strategy，而是未來若要正式化再補 terminal-oriented aggregation endpoints

本次規劃定位正確方向應是：
- 先用現有 API 做 intelligence readability prototype
- freeze + daily review 期間驗證資訊密度與判讀速度
- 之後再進 UI/UX overhaul
