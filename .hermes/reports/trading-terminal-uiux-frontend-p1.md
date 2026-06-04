# Trading Terminal UI/UX Frontend P1 完成報告

## 1. 修改檔案

- `src/main/resources/static/mobile.html`
- `src/main/resources/static/index.html`

> 僅修改前端靜態頁面；未修改 backend API、未導入新框架、未做大型重構。

## 2. 新增 UI 區塊

### P1-1 Adaptive Exit Review

- Mobile：持股頁每張持股卡新增 `Adaptive Exit Review` 摺疊區塊。
- Mobile：市場總覽中「顯示交易細節」的持股卡同步顯示。
- Desktop：持股頁新增 `Adaptive Exit Review` panel，顯示每檔持股的出場複核資訊。

顯示欄位：

- `recommendation`
- `thesisStatus`
- `thesisSummary`
- `invalidationCondition`
- `themeLifecycle`
- `narrativeHeat`
- `crowdingRisk`
- `wavePhase`
- `rotationStrength`
- `institutionalAlignment`
- `sectorLeadership`
- `themeStaleDays`
- `safetyNote`

### P1-2 Health Data Gaps

- Mobile：持股頁 `PositionHealthHero` 下方新增 `Health Data Gaps` card。
- Mobile：市場總覽持股細節區也同步顯示。
- Desktop：持股健康判斷 v2 panel 內新增 `Health Data Gaps` 區塊。

目標是將 health-v2 的資料缺口明確顯示，避免「資料不足」被誤判成「弱勢」。

### P1-3 Decision Snapshot

- Mobile：決策頁頂部新增 `Decision Snapshot` 區塊，列出最近 snapshot。
- Desktop：決策頁最上方新增 `Decision Snapshot` panel。

顯示內容：

- `tradingDate`
- `aiStatus`
- `aiReadinessMode`
- `fallbackReason`
- `sourceTaskType`
- `preferTaskType`
- selected / rejected / watch symbols summary
- `gateTraceJson` 摘要
- `decisionTraceJson` 摘要

### P1-4 Feature Mode Safety Panel

- Mobile：進階頁新增 `Feature Mode Safety` card。
- Desktop：系統狀態頁新增 `Feature Mode Safety Panel`。

顯示內容：

- `READ_ONLY` 數量
- `SHADOW` 數量
- `LIVE` 數量
- `liveDecisionAffecting`
- `safetyNote`
- 會影響正式 BUY / SELL / HOLD 的功能清單

### P1-5 Mapping Quality

- Mobile：題材 Ops 頁新增 `Mapping Quality` card。
- Desktop：設定 / 題材對應管理上方新增 `Mapping Quality` panel。

顯示內容：

- `taxonomyQualityStatus`
- `missingCategoryCount`
- `lowConfidenceCount`
- `ambiguousSymbolCount`
- `otherCategoryRatio`
- `qualityWarnings`
- `totalReviewItems`
- `byReviewPriority`

## 3. 使用 API

- `GET /api/adaptive-exit-review/open-positions`
- `GET /api/portfolio/health-v2/data-gaps`
- `GET /api/decision-snapshots/recent`
- `GET /api/feature-modes/summary`
- `GET /api/feature-modes`
- `GET /api/themes/mappings/observability`
- `GET /api/themes/mappings/manual-review-queue`

## 4. 每個 API 的 fallback / error behavior

### `/api/adaptive-exit-review/open-positions`

- Mobile：透過 `p1Fetch()` 取得 `{ ok, data, error }`；失敗時不影響持股卡本體，只不顯示該持股的 Adaptive Exit 區塊或顯示 widget error。
- Desktop：透過 `widgetApi()` 包裝；失敗時只在 Adaptive Exit panel 顯示錯誤，不影響持股明細與 health-v2。

### `/api/portfolio/health-v2/data-gaps`

- Mobile：失敗時只顯示 `Health Data Gaps 載入失敗` card；持股頁可正常顯示。
- Desktop：失敗時只在 health panel 內顯示錯誤；不影響 `portfolioHealthPanel`。

### `/api/decision-snapshots/recent`

- Mobile：失敗時決策頁頂部顯示 Decision Snapshot widget error；原決策歷史 tabs 照常顯示。
- Desktop：失敗時只在 `dec-snapshotPanel` 顯示錯誤；不影響決策趨勢、整點 Gate、監控歷史與通知。

### `/api/feature-modes/summary`

- Mobile：失敗時進階頁顯示 Feature Mode Summary widget error；不影響 migration health / config / mappings。
- Desktop：失敗時只在系統狀態 Feature Mode panel 顯示錯誤。

### `/api/feature-modes`

- Mobile：若 summary 成功但 list 失敗，仍顯示 summary counts，正式決策影響清單留空或顯示錯誤。
- Desktop：若 summary 成功但 list 失敗，summary stats 照常顯示，並在 panel 內補一段 Feature Modes 載入失敗。

### `/api/themes/mappings/observability`

- Mobile：失敗時題材 Ops 頁只顯示 Mapping Quality widget error；其他題材雷達 / promotion / lifecycle / research 區塊不受影響。
- Desktop：失敗時只在 Mapping Quality panel 顯示錯誤；題材對應管理表格照常載入。

### `/api/themes/mappings/manual-review-queue`

- Mobile：若 observability 成功但 queue 失敗，仍顯示 taxonomy quality 主資訊，review priority 不顯示或顯示空集合。
- Desktop：若 observability 成功但 queue 失敗，主品質 stats 照常顯示，並在 panel 內補 queue error。

## 5. Mobile 測試清單

- 開啟 `/mobile.html#/positions`
  - 應看到 `Health Data Gaps` card。
  - 每張持股卡展開後應看到 `Adaptive Exit Review` 摺疊區。
  - API fail 時持股卡與 health-v2 不應整頁失敗。
- 開啟 `/mobile.html#/overview`
  - 在「顯示交易細節」中應可看到持股相關 P1 資訊。
- 開啟 `/mobile.html#/decisions`
  - 頂部應看到 `Decision Snapshot` 區塊。
  - 切換 09:30 / 整點 Gate / 5 分鐘監控 tabs 不應重打額外 polling timer。
- 開啟 `/mobile.html#/themeops`
  - 應看到 `Mapping Quality` card。
  - 原 Hot Group Radar / Promotion Review / Lifecycle / Research Universe 仍可顯示。
- 開啟 `/mobile.html#/advanced`
  - 應看到 `Feature Mode Safety` card。
  - migration health 與原題材對映摘要仍可顯示。
- 快速切換 mobile route
  - P0 route token / abort guard 應阻止舊 route API response 覆蓋新畫面。

## 6. Desktop 測試清單

- 開啟 `/index.html` → 持股
  - `持股健康判斷 v2` panel 內應看到 `Health Data Gaps`。
  - 持股頁應新增 `Adaptive Exit Review` panel。
  - 持股明細表仍可查詢、編輯、出清。
- 開啟 `/index.html` → 決策
  - 最上方應看到 `Decision Snapshot` panel。
  - 原決策趨勢、整點閘道歷史、監控決策歷史、近期通知仍可顯示。
- 開啟 `/index.html` → 更多 → 系統狀態
  - 應看到 `Feature Mode Safety Panel`。
  - 外部探針、migration health、market snapshot history、probe history 仍可顯示。
- 開啟 `/index.html` → 設定
  - 應看到 `Mapping Quality` panel。
  - 題材對應管理與今日題材快照仍可顯示。
- 切到 hidden tab
  - 既有 `refreshCurrent()` 仍有 `document.hidden` guard；本次沒有新增獨立 polling timer。

## 7. 是否有新增 polling

沒有新增 polling timer。

- Mobile：新 API 都接在既有 per-tab fetch 流程中，沿用 route ctx / abort signal。
- Desktop：新 API 都接在既有 `loadOpenPositions()`、`loadDecisions()`、`loadSystemPage()`、`loadSettingsPage()` 中，由既有 refresh / active page 機制觸發。
- Desktop hidden tab：仍沿用既有 `refreshCurrent()` 的 `document.hidden` guard，沒有新增 hidden tab polling。

## 8. 是否有 stale / race 風險

已降低主要 stale / race 風險：

- Mobile：新 API 使用 `p1Fetch()` / `safeFetch()` 並帶入 `ctx.signal`，render 前仍有 `isRouteCurrent(ctx.token)` guard。
- Mobile：動態子 fetch（例如 advanced config）仍保留 token / connected DOM guard。
- Desktop：沒有新增獨立 timer，因此不會與原 live quote polling 產生重複 polling。
- Desktop：widget API 使用局部 error 容器，API fail 不會造成整頁 fail。

仍需注意：

- Desktop 既有架構本身未全面 route-token 化；本次遵守「不大改架構」限制，未重構 desktop router。風險被限制在 active page reload 範圍內。

## 9. git diff 摘要

```text
src/main/resources/static/index.html  |  265 +++++++--
src/main/resources/static/mobile.html | 1004 ++++++++++++++++++++++++++++-----
2 files changed, 1073 insertions(+), 196 deletions(-)
```

驗證：

- `node --check` mobile script：通過
- `node --check` desktop scripts：通過
- `git diff --check` for modified static files：通過

## 10. commit message 建議

```text
feat(ui): surface P1 trading intelligence widgets

- add adaptive exit review to mobile/desktop positions
- add health-v2 data gaps widgets
- add decision snapshot panels
- add feature mode safety panel
- add theme mapping quality observability
- keep widgets read-only and isolate API failures
```
