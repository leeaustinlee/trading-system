# Trading Terminal P2.1：Market Command Center 實作報告

## 1. 修改檔案

- `src/main/resources/static/mobile.html`
- `src/main/resources/static/index.html`

未修改 backend、未新增 API、未導入 framework、未 commit。

---

## 2. 首頁改了哪些資訊順序

### Mobile `/mobile.html#/overview`

首頁改為 Market Command Center，預設主層只顯示 5 個主要區塊：

1. **Market Command Hero**
   - 今日狀態：`CAN_TRADE / CAUTION / REST / UNKNOWN`
   - 市場階段
   - 風險等級
   - AI readiness
   - 一句 human readable reason
   - 最重要 action

2. **Urgency Strip**
   - 只取前 3 個最急迫事件
   - 來源包含：持股風險、主流題材、有效決策、AI/fallback、data gap/stale warning

3. **Theme Radar Compact**
   - 只顯示 Top 3 themes
   - 顯示 theme name、heat、rotation、crowding、direction

4. **Position Risk Compact**
   - 只顯示 Top 3 risk positions
   - 顯示 symbol、PnL、risk/action tier、adaptive exit recommendation、reason 一句話

5. **Effective Decision Compact**
   - 顯示 BUY/WATCH/WAIT/REST summary
   - AI readiness
   - fallback reason if exists
   - top selected / rejected reason

完整候選股、完整持股卡、health hero、data gaps、capital strip 改放在首頁底部的 drilldown collapsible：

- `Drilldown：交易細節 / P1 widgets`

### Desktop `/index.html` dashboard

Dashboard 上方收斂為：

1. 原 hero 升級為 Market Command decision context
2. 新增 **Urgency Strip**
3. 新增 **Effective Decision** panel
4. Action Queue 只顯示 Top 5 candidates
5. Position panel 只顯示 Top 5 risk positions
6. 原觀察清單區改成 **Theme Radar Top 5**

---

## 3. 哪些資訊被移到 Review/System/drilldown

### Mobile Review Mode

新增 `/mobile.html#/review`，放入：

- Decision Snapshot
- Mapping Quality
- Feature Mode Safety
- Logs / History
- 原 AI 研究與長紀錄保留於既有 `research` view，可從 route 直接進入；底部 tab 改為回顧/系統分層

### Mobile System Mode

新增 `/mobile.html#/system`，放入：

- AI status
- Health Data Gaps
- Feature Mode Safety
- migration health summary
- scheduler status

### Mobile overview drilldown

以下不再預設出現在首頁主層，改到 collapsible：

- long AI status pills
- MarketPulseHero 舊摘要
- full candidate list
- full position cards
- Health Data Gaps 詳細卡
- PositionHealthHero
- capital strip

### Desktop dashboard

- 長候選股清單收斂為 Top 5
- 持股卡收斂為 Top 5 risk positions
- 觀察清單區改為 Theme Radar Top 5
- 詳細資訊仍保留在原子頁：題材、持股、決策、AI、系統狀態、設定

---

## 4. 新增哪些前端 ranking helper

### Mobile helpers

- `toneForUrgency(score)`
- `aiReadinessMeta(fd)`
- `getMarketCommandState()`
- `adaptiveMetaForSymbol(symbol)`
- `positionUrgency(p)`
- `topPositionRisks(limit)`
- `marketUrgencyItems()`
- `decisionSummaryMeta()`
- `buildUrgencyStrip()`

### Mobile render helpers

- `MarketCommandHeroMobile()`
- `UrgencyStripMobile()`
- `PositionRiskCompactMobile(limit)`
- `EffectiveDecisionCompactMobile()`

### Desktop helpers

- `mccTone(score)`
- `mccAiMeta(decision)`
- `mccMarketCommand(dash)`
- `mccPositionScore(p)`
- `mccDecisionMeta(dash)`
- `renderMccUrgencyStrip(dash, pos, candidates)`
- `renderMccEffectiveDecision(dash)`
- `renderMccThemeRadar(candidates)`

Ranking 規則對應需求：

- Position urgency：出場/硬性警報、adaptive exit、health tier、停損距離、未實現損失、題材 stale/crowding
- Market urgency：REST/high risk、AI not ready/fallback、stale data、data gaps、無候選
- Decision urgency：fallback、AI readiness、selected/rejected summary

---

## 5. Mobile 測試清單

建議手測：

1. 開 `/mobile.html#/overview`
   - 首屏應看到 Market Command Hero
   - 主層不超過 5 個主要卡片
   - Urgency Strip 最多 3 則
   - Theme Radar 只顯示 Top 3
   - Position Risk 只顯示 Top 3
   - Effective Decision 不顯示 trace

2. 展開 `Drilldown：交易細節 / P1 widgets`
   - 仍可看到候選股完整清單
   - 仍可看到完整持股卡
   - Health Data Gaps 與 PositionHealthHero 仍保留
   - capital strip 仍保留

3. 底部 tab
   - 市場、持股、決策、題材、回顧、系統 皆可切換
   - `/review` 可看到 Decision Snapshots、Mapping Quality、Feature Mode Safety、Logs / History
   - `/system` 可看到 AI status、Data Gaps、Feature Safety、migration/scheduler 摘要

4. 快速切換 route
   - 舊 response 不應覆蓋新 route
   - route abort / stale guard 應維持有效

---

## 6. Desktop 測試清單

建議手測：

1. 開 `/index.html` dashboard
   - Hero 下方應看到 Urgency Strip
   - Effective Decision panel 應顯示目前決策摘要
   - Action Queue 只顯示 Top 5 candidates
   - Position panel 只顯示 Top 5 risk positions
   - Theme Radar Top 5 顯示題材聚合摘要

2. 子頁功能確認
   - 題材頁、持股頁、決策頁、AI 頁、系統狀態、設定仍可使用
   - P1 widgets 在原頁面仍保留

3. Dashboard refresh
   - 不應新增額外 polling timer
   - hidden tab guard 維持既有行為

---

## 7. 是否新增 polling

沒有新增 polling timer。

- Mobile：仍沿用 route view fetch 流程與 `safeFetch/p1Fetch + AbortController + route token guard`
- Desktop：仍接在既有 `loadDashboard()` 與既有 refreshCurrent 流程；未新增 timer

---

## 8. 是否仍遵守 P0 stale guard

是。

Mobile P2.1 新首頁仍在：

- `fetchCommon(ctx)`
- `fetchIntelligenceP1(ctx)`
- `fetchOverview(ctx)`

每段後維持：

- `if (!isRouteCurrent(ctx.token)) return;`

新增 Review/System 也使用 route context 並在 await 後檢查 token。

Desktop 沒有全面 route-token 化，維持既有架構限制；本次未新增 polling/race 來源。

---

## 9. git diff 摘要

```text
src/main/resources/static/index.html  |  379 ++++++++--
src/main/resources/static/mobile.html | 1226 ++++++++++++++++++++++++++++-----
2 files changed, 1372 insertions(+), 233 deletions(-)
```

驗證：

- `git diff --check -- src/main/resources/static/mobile.html src/main/resources/static/index.html`：通過
- 抽出 HTML script 後執行：
  - `node --check /tmp/mobile.html.js`：通過
  - `node --check /tmp/index.html.js`：通過

---

## 10. commit message 建議

```text
feat(ui): add market command center focus mode

- refocus mobile overview into market command hero and urgency strip
- add compact theme, position risk, and effective decision cards
- add mobile Review/System mode tabs for drilldown intelligence
- refocus desktop dashboard around urgency, decision, positions, and themes
- add frontend urgency ranking helpers without new APIs or polling
```
