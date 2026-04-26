# Mobile Trading Terminal — 設計規格

**目標檔**：`trading-system/src/main/resources/static/mobile.html`
**訪問 URL**：`http://localhost:8080/mobile.html`
**設計日期**：2026-04-26
**設計者**：Austin 台股操盤 AI 系統

---

## 0. 設計目的

這不是 dashboard，不是分析工具，是 **「Austin 在地鐵 / 開盤前 / 盤中 30 秒內做交易決策」** 的畫面。

KPI：
- 開頁 **3 秒**：知道今天要不要操作（ENTER / WAIT / REST）
- 開頁 **5 秒**：知道要買哪 1–3 檔（symbol + 進場區間 + 停損）
- 開頁 **10 秒**：知道風險高不高（持倉離停損多近、AI 是否就緒、有沒有阻擋原因）

不需要思考、不需要滑很多、不需要切頁。

---

## 1. 設計原則

| 原則 | 說明 |
|---|---|
| Mobile First | 視寬 360–420px 為基準（375 = iPhone 主流寬）；不考慮 desktop |
| 一頁完成 | 全部資訊在單一可滑動頁面內，不切 tab、不開 modal（除了卡片就地展開） |
| 高對比 | OLED 黑底 + 純色語意色，方便日光下閱讀 |
| Action First | 「現在能不能做」永遠在最上方 sticky；分析資料藏在點擊展開區 |
| 預設收合 | 卡片只顯示「決策三件事」（symbol / 進場區間 / 停損距離）；點擊才展開 RR / score / thesis |
| 少文字 | 一個原因不超過 10 字；長 thesis、技術指標、成交量一律不顯示在主畫面 |
| 快取友善 | 30 秒 polling，避免電池耗盡；切回前景時主動 refresh 一次 |

---

## 2. 區塊結構（由上至下）

```
┌────────────────────────────────────────┐
│  [sticky] Decision Hero (永遠可見)      │  ← 區 A：3 秒看決策
├────────────────────────────────────────┤
│  AI Status 一行                         │  ← 區 B：1 秒看 AI 是否就緒
├────────────────────────────────────────┤
│  [sticky] Quick Filter chips            │  ← 區 C：一鍵切換視圖
├────────────────────────────────────────┤
│  Action List（行動清單卡片）             │  ← 區 D：5 秒看買哪檔
│    [3189 景碩 🟡 等回測 進場 / 停損]    │
│    [2454 聯發科 🟢 可進場 ...]          │
│    ...                                  │
├────────────────────────────────────────┤
│  Position Cards（持倉風險視圖）          │  ← 區 E：10 秒看持倉風險
│    [2303 聯電 +2.8% 距停損 -1.2% 🔴]    │
│    [HOLD] [REDUCE]                      │
├────────────────────────────────────────┤
│  Capital strip（極簡資金一行）           │  ← 區 F：可選，不擋路
├────────────────────────────────────────┤
│  Footer：上次更新時間 + 手動 refresh     │
└────────────────────────────────────────┘
```

---

## 3. 各區規格

### A. Decision Hero（最上方 sticky）

**永遠顯示**，浮在頁面最頂端，捲動時不消失。

**版型**：
```
┌──────────────────────────────┐
│ 🟢 ENTER（2 檔）              │  ← 大字 / 強色背景
│ 原因：A+ 主升 + 量價齊揚      │  ← 小字 / 最多 10 字
│ 09:30:42                     │  ← 決策產生時間
└──────────────────────────────┘
```

**狀態 → 顏色 / 圖示 / 文字**：
| decision | 圖示 | 背景色 | 標題文字 |
|---|---|---|---|
| `ENTER` | 🟢 | `--c-buy` (綠) | `ENTER（N 檔）` |
| `WATCH` / `WAIT` | 🟡 | `--c-wait` (橘) | `WAIT（不追）` |
| `PLAN` | 🔵 | `--c-plan` (藍) | `PLAN（盤後規劃）` |
| `REST` | ⚫ | `--c-rest` (灰) | `REST（休息）` |
| 未就緒 | 🔴 | `--c-stop` (紅) | `AI 未就緒` |

**資料來源**：`GET /api/dashboard/current` → `finalDecision.decision` / `finalDecision.summary` / `finalDecision.createdAt`，配合 `finalDecision.payloadJson` 內 `selected_count`、`rejected_count`。

**原因文字邏輯**（最多顯示 10 中文字）：
1. 若 `decision=ENTER` → 從 `selected_stocks[0].rationale` 取前 10 字；fallback `summary` 前 10 字
2. 若 `decision=WAIT/REST` → 套用 LineMessageBuilder 的 Top 2 reason 邏輯（從 `rejected_reasons` 挑優先序最高那條），最多 10 字
3. 若資料缺 → 顯示「等待 09:30 決策」

**互動**：點擊 hero 展開 / 收合「Top 2 阻擋原因」抽屜（只在 WAIT / REST 才顯示）。

### B. AI Status（一行）

**版型**：
```
🟢 AI READY · Claude OK · Codex OK              09:28
🟡 AI PARTIAL · Codex 未完成                     09:31
🔴 AI 未就緒 · 保守 REST                         09:30
```

**狀態判讀**：
| `aiStatus` | 顯示 |
|---|---|
| `FULL_AI_READY` | 🟢 AI READY |
| `PARTIAL_AI_READY` | 🟡 AI PARTIAL（+ `fallbackReason` 短化） |
| `AI_NOT_READY` | 🔴 AI 未就緒 |

**資料來源**：`GET /api/dashboard/current` → `finalDecision.aiStatus` / `finalDecision.fallbackReason` / `finalDecision.claudeDoneAt` / `finalDecision.codexDoneAt`。

時間：`max(claudeDoneAt, codexDoneAt)` 取 HH:mm 顯示。

**互動**：純資訊，不可點擊。高度 ≤ 28px，不可佔大空間。

### C. Quick Filter chips（sticky 副位）

**版型**：橫排 4 顆 chip，緊接 hero 下方，捲動時也吸頂。
```
[ 全部 ] [ 可買 ] [ 等待 ] [ 持倉 ]
```

**邏輯**：點擊一顆變高亮（其他變淡），下方列表只顯示對應卡片：
| chip | 顯示什麼 |
|---|---|
| 全部 | Action list + 持倉，全部展開 |
| 可買 | Action list 只留 hint=`buy`（finalRankScore ≥ 8.8 + entry zone valid + 不是 vetoed） |
| 等待 | Action list 只留 hint=`wait`（finalRankScore ≥ 7.4 但未到 buy） |
| 持倉 | 只顯示 Position Cards |

**狀態保持**：選擇存 `localStorage('mobile.filter')`，下次開頁記得使用者上次選擇。

### D. Action List（最重要區塊）

**單卡版型（收合預設）**：
```
┌──────────────────────────────┐
│ 3189 景碩            🟡 等回測 │
│ 進場：510–531                  │
│ 停損：494                      │
│ 距停損：-3.1%  🟡              │
└──────────────────────────────┘
```

**單卡版型（展開）**：
```
┌──────────────────────────────┐
│ 3189 景碩            🟡 等回測 │
│ 進場：510–531                  │
│ 停損：494                      │
│ 距停損：-3.1%  🟡              │
│ ───────────────              │
│ RR 1.33 · score 23.7 · PCB    │  ← 新增一行：RR / score / theme
│ thesis：PCB 載板續強，量縮回測  │  ← 一行 thesis（最多 30 字）
└──────────────────────────────┘
```

**hint 邏輯**：
| hint | 條件 | 顯示 |
|---|---|---|
| `buy` 🟢 可進場 | `includeInFinalPlan=true` 且 `finalRankScore >= 8.8`（fallback `score >= 22`）| 綠色 pill |
| `wait` 🟡 等回測 | `score >= 7.4` 或 `entryPriceZone` 有效但 `currentPrice` 已超過區間上緣 | 橘色 pill |
| `watch` 🔵 觀察 | 其他 | 藍色 pill |
| `skip` ⚫ 排除 | `isVetoed=true` | 灰色 pill，不亮 |

**距停損計算**：
- 公式：`(currentPrice - stopLossPrice) / currentPrice × 100`
- 顏色：`< 2%` 🔴；`2-5%` 🟡；`>= 5%` 🟢
- `currentPrice` 從 live-quotes 撈；缺則顯示「資料不足」

**禁止顯示**：成交量、KD、MACD、5MA / 10MA、長段技術說明（>40 字）、理由全文（cap 30 字）。

**資料來源**：
- 主：`GET /api/candidates/current` → 候選股 array
- 補：`GET /api/candidates/live-quotes?symbols=<csv>` → 即時報價（算距停損 %）

### E. Position Cards（持倉區）

**單卡版型**：
```
┌──────────────────────────────┐
│ 聯電 2303     1000 股 +2.8% 🟢 │
│ 距停損：-1.2%  ████░░░░ 🔴     │
│ 風險：高                      │
│ [ HOLD ]  [ REDUCE ]  [ EXIT ] │
└──────────────────────────────┘
```

**距停損 bar**：
- 寬度按 distancePct 對應 0–10% 線性，超過 10% 視為滿
- 顏色：< 2% 紅 / 2–5% 橘 / ≥ 5% 綠

**風險等級**：
- 距停損 < 2% → 「高」（紅）
- 2–5% → 「中」（橘）
- ≥ 5% → 「低」（綠）

**按鈕行為**（不下單，只是 UI 紀錄）：
- `HOLD` → 純資訊提示 alert
- `REDUCE` → `confirm` 後 `POST /api/positions/{id}/note`（暫定，本次只 alert，不真打）
- `EXIT` → `confirm` 後 `alert` 提示去券商出場

**資料來源**：
- 主：`GET /api/positions/open?size=10`
- 補：`GET /api/candidates/live-quotes?symbols=<symbols>` → 即時 currentPrice 算 PnL%

### F. Capital strip（一行）

```
資金 76.3 萬 · 現金比 50% · 未實現 +1.94 萬
```

**資料來源**：`GET /api/capital/summary` → `totalAssets` / `cashRatio` / `unrealizedPnl`。

**互動**：點擊展開「主題曝險（top 3）」抽屜（從持倉前端聚合 themeTag）。

### G. Footer

```
更新：09:28:42  [↻ 手動更新]   來源：Trading System
```

點擊 ↻ 強制重抓所有 API。

---

## 4. 視覺 Design Tokens

### Colors（CSS variables）
```css
:root {
  /* 背景 */
  --bg-base: #0a0e1a;          /* 純黑底，OLED 友善 */
  --bg-card: #141a2a;          /* 卡片 */
  --bg-card-2: #1c2438;        /* 卡片 hover/expand */
  --bg-sticky: rgba(10,14,26,.92); /* sticky 半透明 backdrop */

  /* 文字 */
  --ink: #e8edf5;              /* 主文字 */
  --muted: #7a8499;            /* 次文字 */
  --dim: #4d5670;              /* 邊框 / 弱化 */

  /* 語意色（決策） */
  --c-buy: #22c55e;            /* 可進場 */
  --c-wait: #f59f00;           /* 等待 */
  --c-watch: #3b82f6;          /* 觀察 */
  --c-plan: #8b5cf6;           /* 盤後規劃 */
  --c-rest: #6b7280;           /* 休息 */
  --c-stop: #ef4444;           /* 停損 / 危險 */
  --c-up:   #22c55e;           /* 漲（綠，與台股相反但沿用 dashboard 一致語意） */
  --c-dn:   #ef4444;           /* 跌（紅） */
}
```

> 註：台股傳統紅漲綠跌，但本系統 dashboard 已採綠漲紅跌（國際慣例）。Mobile UI 沿用，不破壞既有語意一致性。

### Typography
- 主字體：`-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang TC", "Microsoft JhengHei", sans-serif`
- mono：`"SF Mono", "JetBrains Mono", Menlo, Consolas, monospace`（價格、symbol、時間）
- 字級：
  - hero title `28px / 800`
  - hero reason `14px / 600`
  - card symbol `18px / 700`
  - card label `12px / 600 uppercase`
  - body `14px / 500`
  - mono price `15px / 600`

### Spacing
- 卡片 padding：`12px 14px`
- 卡片間距：`8px`
- 區塊間距：`16px`
- sticky 區內 padding：`10px 14px`
- 圓角：`10px`（卡片）/ `999px`（chip / pill）

### Touch targets
- 所有可點擊元素 min-height `40px`、min-width `40px`
- chip 高度 `36px`、橫向 padding `14px`
- action button（HOLD/REDUCE/EXIT）高度 `40px`，全寬均分

---

## 5. 互動細節

| 動作 | 行為 |
|---|---|
| 點擊 Action card 主體 | toggle 展開 / 收合詳細區（RR / score / thesis） |
| 點擊 Position card 主體 | toggle 展開（顯示 entry / TP1 / TP2 / strategyType） |
| 點擊 hero | 若是 WAIT/REST，展開 Top 2 阻擋原因 |
| 點擊 quick filter chip | 過濾下方列表並存到 localStorage |
| 點擊 ↻ refresh | 強制 re-fetch 所有 API（不等 30s 自動 polling） |
| 持倉按鈕 HOLD/REDUCE/EXIT | 跳 alert（不真下單） |
| 滑動 | 純捲動，無 swipe gesture（避免誤觸） |

**Polling**：30 秒 setInterval；`document.visibilitychange` 切回 visible 時主動 refresh 一次。

**錯誤處理**：任何 API 失敗，對應區塊顯示「unavailable」+ 紅點，但不阻塞其他區塊載入。

---

## 6. 資料來源對照表

| UI 區塊 | API endpoint | 用到的欄位 | 缺資料時的 fallback |
|---|---|---|---|
| Decision Hero | `GET /api/dashboard/current` | `finalDecision.decision / summary / createdAt / payloadJson.selected_count / rejected_reasons` | 「等待 09:30 決策」灰底 |
| AI Status | `GET /api/dashboard/current` | `finalDecision.aiStatus / fallbackReason / claudeDoneAt / codexDoneAt` | 「AI 狀態未知」黃底 |
| Action List | `GET /api/candidates/current`<br>`GET /api/candidates/live-quotes?symbols=...` | `symbol / stockName / score / entryPriceZone / stopLossPrice / takeProfit1 / riskRewardRatio / themeTag / reason / includeInFinalPlan / isVetoed` + live `currentPrice` | 卡片仍顯示，距停損標「資料不足」 |
| Position Cards | `GET /api/positions/open?size=10`<br>`GET /api/candidates/live-quotes?symbols=...` | `id / symbol / stockName / qty / avgCost / stopLossPrice / takeProfit1 / takeProfit2 / strategyType` + live `currentPrice` / `changePercent` | 持倉卡顯示，PnL%/距停損 標「資料不足」 |
| Capital strip | `GET /api/capital/summary` | `totalAssets / cashRatio / unrealizedPnl` | 「資金資料 unavailable」 |

**全部使用既有 API，本次不新增 endpoint**。

---

## 7. 與既有 `index.html` 的關係

### 保留
- `index.html`（整個 desktop dashboard）— 原樣保留，桌機照用
- 所有 API endpoint — 完全沿用

### 不刪
即使 mobile.html 上線，`index.html` 仍是 Austin 在桌機 / 大螢幕的主介面，因為 mobile UI 故意不顯示分析資料（trace 詳情、scheduler、orchestration、研究記錄、損益走勢）。

### 改的小建議（非本次範圍）
- `index.html` 預設 viewport 沒設 mobile-first，桌機 layout 在手機上會橫向擠壓 — 可在 `<head>` 加註 `viewport meta`，但**本次不動**避免破壞桌機 layout。
- 若未來想統一入口，可在 `index.html` 偵測 `window.innerWidth < 480` 時 redirect 到 `mobile.html`。**本次不做**，避免桌機誤跳。

---

## 8. 不做的事（明確劃線）

- ❌ 不重寫 desktop `index.html`
- ❌ 不引入 React / Vue / build step（純 HTML + CSS + vanilla JS）
- ❌ 不新增 API endpoint
- ❌ 不做下單功能（按鈕只是 alert / 提醒）
- ❌ 不做 PWA / service worker（本次太重，先驗證 UX）
- ❌ 不做手勢（swipe / pinch / pull-to-refresh）— 怕誤觸交易
- ❌ 不做動畫（最多 transform/opacity 200ms）— 要快
- ❌ 不做暗 / 亮主題切換 — 預設暗（盤中 / 通勤環境最實用）

---

## 9. 驗收標準

1. iPhone 12/13/14（375×812）打開能完整看到 Decision Hero 不被 notch 遮
2. 開頁 `<3` 秒（含一次 fetch + render）能看到決策
3. 滑到底再滑回頂，sticky hero 始終可見
4. 切換 quick filter，下方列表 < 100ms 切換成功
5. 拔網路 / API 500：每個區塊獨立顯示「unavailable」，整頁不白屏
6. polling 30s，背景切換回前景立即更新
7. 觸控 hit target 全部 ≥ 40px
8. 全頁 < 1MB（純文字 / inline CSS / inline JS）

---

## 10. Follow-up（本次不做、列入下個迭代）

| 項目 | 為什麼後做 | 大致工程量 |
|---|---|---|
| 風險等級欄位後端化 | 目前由前端聚合 distancePct 推導；後端 `position_review_log` 已有更精細的 STRONG/WEAKEN/EXIT，可直接 expose 進 `/api/positions/open` | S |
| 主題曝險 API（`GET /api/themes/exposure`） | 目前由前端聚合 positions × themeTag，缺 `theme_exposure_limit` 觸發判定 | S |
| 5 分鐘 monitor 推播 | mobile UI 是 pull-only；要做 push 需 PWA 或 LINE，超出本次 scope | M |
| 一鍵下單 ` POST /api/positions/quick-open` | 牽涉券商 API、下單合規、資金鎖定。系統未證明能賺錢前不做 | L |
| Action Card 內嵌 sparkline | 需要 intraday tick history endpoint | M |
| 操作習慣統計（hero KPI strip） | 接 `BenchmarkAnalyticsEngine` 的 alpha 計算 | M |

---

## 11. 入口

- 直接訪問：`http://localhost:8080/mobile.html`
- 桌機書籤備援：`http://<你 LAN IP>:8080/mobile.html` 從手機連 WiFi 即可開
- 之後可 QR code 印在小卡上，掃一下開
