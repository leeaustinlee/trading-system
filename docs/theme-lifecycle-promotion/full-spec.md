# Theme Lifecycle Promotion Full Specification

## 1. Executive Summary

本文件定義 Trading System 後續的 **Theme Lifecycle Promotion** 實作規格。

核心決策：

- 不新增全新的 `Theme Memory Engine`。
- 優先 promotion 既有：
  - `ThemeLifecycleEngine`
  - `theme_lifecycle_state`
  - `ThemeReplayTimeline`
  - `ThemeLeaderRetention`
  - `ThemeStrengthEngine`
  - `ThemeSnapshotService`
- P3 階段只做 read-only / shadow / annotation / review-first。
- 不直接接 BUY。
- 不直接接 SELL。
- 不直接改 `ranking.top_n`。
- 不讓 lifecycle override risk gate。
- 不做 production portfolio rotation。

本 flow 的 AI 分工：

| 角色 | 負責 |
|---|---|
| Hermes | orchestration、phase 切分、驗收、DB side-effect check、commit hygiene、回報 |
| Codex | 主要實作、測試、修 build、前端頁面 |
| Claude | 文件 / spec / 設計整理；因額度限制不作為主實作 |

整體方向：

```text
Theme Lifecycle Promotion
  = use existing lifecycle memory/state
  + calibrate it
  + annotate candidate/ranking/exit/portfolio reports
  + shadow review before any production promotion
```

---

## 2. Current Architecture Review

目前已存在的題材生命週期相關模組已足以構成 Theme Memory Layer 的雛形：

| 模組 | 目前用途 | 是否作為 promotion 基礎 |
|---|---|---:|
| `ThemeLifecycleEngine` | 計算 lifecycle stage / score / playbook | 是 |
| `theme_lifecycle_state` | 保存每日題材 lifecycle state | 是 |
| `ThemeReplayTimeline` | replay 題材 timeline / node / edge | 是 |
| `ThemeLeaderRetention` | leader 跨日 retention | 是 |
| `ThemeStrengthEngine` | 題材強度 / tradability / decay | 是 |
| `ThemeSnapshotService` | 題材 snapshot 讀取 / fallback | 是 |

因此不應新建：

```text
ThemeMemoryEngine
ThemeMemoryState
ThemeMemorySnapshot
```

除非未來用實際資料證明既有設計無法支援：

- lifecycle coverage
- stage transition
- leader retention
- predictive calibration
- annotation lookup
- shadow review

目前缺口不是「沒有 memory 原料」，而是：

1. lifecycle coverage 短。
2. `MAINSTREAM` 樣本為 0。
3. `OVERHEATED` 可能混入 strong trend。
4. lifecycle 仍是 replay/advisory-only。
5. candidate / ranking / exit / portfolio 尚未讀 lifecycle annotation。
6. stop washout / TopN / portfolio rotation 尚未和 lifecycle 形成閉環。

---

## 3. Recent Local Data Findings

最近 local DB review 的資料覆蓋：

| Table | Rows | Date Range | 備註 |
|---|---:|---|---|
| `trading_funnel_trace` | 81 | 2026-05-22 ~ 2026-06-18 | P0 funnel trace |
| `theme_admission_shadow_decision` | 81 | 2026-05-22 ~ 2026-06-18 | P0 admission shadow |
| `ranking_topn_shadow_result` | 150 | 2026-04-21 ~ 2026-06-18 | P0 TopN shadow |
| `theme_lifecycle_state` | 76 | 2026-05-22 ~ 2026-06-18 | lifecycle state |
| `paper_trade` | 32 | 2026-04-28 ~ 2026-06-19 | paper trades |
| `stop_washout_outcome` | 1507 | 2026-04-23 ~ 2026-06-17 | exit washout outcome |
| `portfolio_risk_decision` | 397 | mixed | 含 synthetic / future dates，使用前需清理 |

### 3.1 Funnel Findings

| Layer | Count |
|---|---:|
| HotGroup Signal | 81 |
| Candidate hit | 10 |
| Watchlist hit | 12 |
| Ranking hit | 5 |
| BUY count | 2 |

Blocked stage：

| Stage | Count |
|---|---:|
| `CANDIDATE` | 71 |
| `RANKING` | 5 |
| `BUY` | 3 |
| `NONE` | 2 |

主要 blocked reasons：

| Reason | Count |
|---|---:|
| not found in `candidate_stock` for date | 71 |
| not found in `stock_ranking_snapshot` for date | 5 |
| no paper trade opened for date | 3 |

判斷：最大斷點仍是 Candidate Admission，但根因不只 admission rule，而是缺 lifecycle annotation / pullback plan / ranking TopN calibration。

### 3.2 Theme Admission Shadow Findings

| Shadow Action | Count |
|---|---:|
| `SHADOW_ONLY` | 57 |
| `WOULD_CREATE_PULLBACK_PLAN` | 18 |
| `WOULD_ADMIT_WATCHLIST` | 4 |
| `WOULD_ADMIT_CANDIDATE` | 2 |

額外合計：

| Field | Count |
|---|---:|
| would candidate | 13 |
| would watchlist | 11 |
| would pullback | 18 |

判斷：P1-A guarded write path 是安全模板，但不是完整解法。尤其 `WOULD_CREATE_PULLBACK_PLAN=18` 需要後續 P3-E shadow plan。

### 3.3 Lifecycle Stage Counts

| Stage | Count |
|---|---:|
| `OVERHEATED` | 47 |
| `EMERGING` | 14 |
| `DISTRIBUTION` | 11 |
| `DEAD` | 4 |
| `MAINSTREAM` | 0 |

重要結論：

- `MAINSTREAM=0` 是 calibration issue。
- `OVERHEATED` 不應直接視為 exit signal。
- 初步資料顯示 `OVERHEATED` 可能代表 strong crowded trend。
- `DISTRIBUTION` / `DEAD` 比較適合 exit review，不適合 auto SELL。

### 3.4 Lifecycle Metric Predictive Power

初步 paper_trade join lifecycle 的 predictive order：

```text
rotation_score
> continuation_days
> lifecycle_score
> leader_count
> breadth
> crowding_score
> institutional_flow_score
> narrative_density
```

限制：樣本數小，且 5D/10D/20D 覆蓋不足。P3-A 的目的就是把此類檢查變成可重跑 report。

### 3.5 Ranking TopN Findings

`ranking_topn_shadow_result` 顯示：

- Top5 / Top10 有 missed winners。
- Top20 後段開始變弱。
- 不應直接把 production `ranking.top_n=3` 改掉。

後續應以 P3-D dynamic TopN shadow / theme quota shadow 驗證。

---

## 4. Root Cause Ranking

| Rank | Root Cause | Evidence | 後續 phase |
|---:|---|---|---|
| 1 | Candidate Admission 斷裂 | 81 signals 中 71 blocked at CANDIDATE | P3-B / P3-E |
| 2 | Lifecycle 未 production-grade calibration | `MAINSTREAM=0`，`OVERHEATED` 語意不清 | P3-A |
| 3 | Ranking TopN 可能過窄 | Top5/Top10 有 missed winners | P3-D |
| 4 | Limit-risk leader 無 pullback plan | `WOULD_CREATE_PULLBACK_PLAN=18` | P3-E |
| 5 | Exit 缺 lifecycle context | stop_washout 有資料但 lifecycle join/report 不足 | P3-C |
| 6 | Portfolio full 沒有 rotation shadow | portfolio data 含 synthetic/future dates | P3-F |

---

## 5. Recommended Architecture

```text
Existing lifecycle sources
  ├─ theme_lifecycle_state
  ├─ theme_replay_snapshot/node
  ├─ theme_leader_retention
  ├─ theme_strength_decision
  └─ theme_snapshot
       ↓
P3-A Lifecycle Calibration
       ↓
P3-B Lifecycle Annotation
       ↓
P3-C Exit Review Shadow Hook
       ↓
P3-D Ranking TopN Shadow Calibration
       ↓
P3-E Limit-Up Pullback Plan Shadow
       ↓
P3-F Portfolio Rotation Shadow
       ↓
P4 Guarded Production Promotion Review
```

### Safety Contract

Every P3 response/table must clearly mark one of：

```text
readOnly=true
shadowOnly=true
annotationOnly=true
advisoryOnly=true
doesNotAffectBuySell=true
```

P3 不允許：

- auto buy
- auto sell
- stop mutation
- risk override
- ranking production score override
- portfolio production rotation

---

## 6. Backend Phases

## P3-A Lifecycle Calibration

### Goal

把 lifecycle calibration 從人工 SQL / ad-hoc review 變成可重跑 read-only diagnostics。

### Current status

已完成並 commit：

```text
2d1c213 feat(theme): add lifecycle calibration diagnostics
```

API：

```http
GET /api/theme-lifecycle/calibration?days=60
GET /api/theme-lifecycle/data-gaps?days=60
```

Response safety fields：

```json
{
  "readOnly": true,
  "advisoryOnly": true,
  "doesNotAffectBuySell": true
}
```

### Report contents

- requestedDays
- actualAvailableDays / actual date range
- dataCoverage
- stageDistribution
- funnelSummary
- themeAdmissionSummary
- topNShadowSummary
- lifecycleMetricPredictivePower
- calibrationFindings
- dataGaps

### Required findings

| Code | Meaning |
|---|---|
| `MAINSTREAM_ZERO` | request window has no MAINSTREAM sample |
| `OVERHEATED_STRONG_TREND_WARNING` | available data shows OVERHEATED positive realized return / PnL |
| `PORTFOLIO_DATA_DATE_WARNING` | portfolio data contains future-dated rows |

### Non-goals

- 不新增 table。
- 不寫 production table。
- 不改 BUY / SELL / ranking / risk。

---

## P3-B Lifecycle Annotation

### Goal

讓 Candidate / Watchlist / Ranking / Position / Exit report 都能看到 lifecycle context，但不改 decision。

### Proposed service

```text
ThemeLifecycleAnnotationService
```

### Proposed endpoints

```http
GET /api/theme-lifecycle/annotations/candidates?date=YYYY-MM-DD
GET /api/theme-lifecycle/annotations/watchlist?date=YYYY-MM-DD
GET /api/theme-lifecycle/annotations/ranking?date=YYYY-MM-DD
GET /api/theme-lifecycle/annotations/positions?date=YYYY-MM-DD
```

### Annotation DTO

```json
{
  "symbol": "2327",
  "stockName": "國巨",
  "themeTag": "MLCC",
  "stage": "OVERHEATED",
  "previousStage": "EMERGING",
  "stageChanged": true,
  "stageConfidence": 0.82,
  "lifecycleScore": 0.81,
  "breadth": 11,
  "leaderCount": 2,
  "continuationDays": 5,
  "rotationScore": 0.32,
  "crowdingScore": 0.83,
  "recommendedPlaybook": ["WAIT_PULLBACK"],
  "avoidPlaybook": ["CHASE_LEADER"],
  "advisoryAction": "NO_CHASE_WAIT_PULLBACK",
  "annotationOnly": true,
  "doesNotAffectBuySell": true
}
```

### Safety

- 不寫 candidate/watchlist。
- 不改 ranking score。
- 不改 final decision。
- 不改 paper_trade。
- 不改 position。

---

## P3-C Lifecycle Exit Review Hook

### Goal

建立第一個 guarded production-like hook：只產生 exit review，不自動 SELL。

### Proposed table

```text
lifecycle_exit_review_shadow
```

### Stage rule

| Stage | Review Action | Safety |
|---|---|---|
| `DISTRIBUTION` | `TIGHTEN_STOP_REVIEW` | review only |
| `DEAD` | `HIGH_PRIORITY_EXIT_REVIEW` | review only |
| `OVERHEATED` | `NO_ADD_NO_CHASE` | not exit |
| `MAINSTREAM` | `HOLD_THESIS` | not buy |
| `EMERGING` | `WATCH_RESEARCH_ONLY` | not buy |

### Required integration context

Exit review must display：

- lifecycle state
- ThemeExitLayer state
- StructureAwareExitArbiter tier
- price state
- structure state
- data gaps

### Safety

- 不自動賣。
- 不改 stop。
- 不改 position。
- 不改 paper_trade status。

---

## P3-D Ranking TopN Shadow Calibration

### Goal

驗證 production `ranking.top_n=3` 是否過窄，但不直接改 production ranking。

### Shadow scenarios

| Scenario | Meaning |
|---|---|
| Current Top3 | 現行 |
| Top5 | 放寬到 5 |
| Top10 | 放寬到 10 |
| Top20 | 放寬到 20 |
| Dynamic TopN | 依 market / theme breadth 動態 |
| Theme quota | Tier 1 theme 配 quota |
| Lifecycle-aware TopN | lifecycle-supported candidate 優先 |

### Endpoint

```http
GET /api/ranking/topn-shadow/calibration?days=60
GET /api/ranking/topn-shadow/missed-winners?days=60
GET /api/ranking/topn-shadow/theme-quota?days=60
```

### Safety

- 不改 `ranking.top_n`。
- 不改 production ranking score。
- 不改 setup eligibility。
- 不影響 BUY。

---

## P3-E Limit-Up Pullback Plan Shadow

### Goal

處理 `WOULD_CREATE_PULLBACK_PLAN=18` 的主流題材 leader / second leader。

### Proposed table

```text
theme_pullback_shadow_plan
```

### Status

```text
OBSERVING
MATURED
EXPIRED
REJECTED
RETRIGGERED
```

### Trigger

From `theme_admission_shadow_decision` where：

```text
would_create_pullback_plan = true
```

### Conditions

| Condition | Meaning |
|---|---|
| VWAP reclaim | 重新站回 VWAP |
| 5MA pullback | 拉回 5MA |
| open stabilized | 隔日開盤穩定 |
| not limit-up chase | 不追漲停 |
| volume cooling | 爆量後量縮 |
| lifecycle supported | lifecycle 不是 DEAD / severe DISTRIBUTION |

### Safety

- 不寫 candidate。
- 不寫 watchlist。
- 不 BUY。
- 不 bypass risk。

---

## P3-F Portfolio Rotation Shadow

### Goal

處理 `PORTFOLIO_FULL` hard block 與換股機會，但只做 shadow review。

### Proposed service

```text
PortfolioRotationShadowService
```

### Metrics

| Metric | Meaning |
|---|---|
| new candidate score | 新候選分數 |
| weakest holding score | 最弱持股分數 |
| lifecycle differential | 新題材 vs 舊題材 lifecycle 差異 |
| opportunity delta | 新標的後續表現 - 舊持股後續表現 |
| shadow action | SHADOW_ROTATE / SHADOW_REDUCE / HOLD |

### Safety

- 不動 position。
- 不自動減碼。
- 不自動換股。
- 不改 portfolio risk gate。

---

## 7. Web Dashboard Specification

## P3-A Web Calibration Dashboard

### Current status

已完成並 commit：

```text
ef9fe7b feat(ui): add lifecycle calibration dashboard
```

### Location

```text
src/main/resources/static/index.html
```

### UI route

Desktop tab：

```text
Lifecycle
```

Page id：

```text
pageLifecycle
```

### API dependency

```http
GET /api/theme-lifecycle/calibration?days=60
GET /api/theme-lifecycle/data-gaps?days=60
```

### Required panels

| Panel | Purpose |
|---|---|
| Safety badges | readOnly / advisoryOnly / doesNotAffectBuySell |
| Summary cards | requestedDays, available days, lifecycle rows, funnel signals, findings |
| Stage Distribution | stage count and avg metrics |
| Funnel Summary | conversion and blocked reasons |
| Theme Admission Summary | shadow actions and would-* counts |
| TopN Shadow | Top buckets and missedByTop3 |
| Predictive Power | correlations / spread / sample count |
| Data Coverage | row count / date range / gap reason |
| Calibration Findings | MAINSTREAM / OVERHEATED / portfolio warnings |
| Data Gaps | explicit data gap list |

### Required wording

```text
Read-only diagnostics
Review / calibration only
不影響 BUY / SELL
OVERHEATED currently may behave like strong crowded trend; do not treat as sell signal
```

### Safety

- 不顯示 BUY / SELL action button。
- 不提供交易操作。
- 不寫 DB。

---

## Future Web Dashboard Panels

### P3-B Annotation UI

Add to Lifecycle page or candidate/ranking panels：

- Candidate lifecycle panel
- Ranking lifecycle panel
- Position lifecycle panel
- Stage badges
- Data gap badges

### P3-C Exit Review UI

Add：

- Position exit review panel
- Review action badges
- Details drawer

Wording must say：

```text
Review suggested
```

Not：

```text
Sell signal
```

### P3-D Ranking UI

Add：

- TopN comparison table
- missed winner attribution
- Top3 vs Top5 / Top10 comparison
- lifecycle-supported missed winners

### P3-E Pullback UI

Add：

- Pullback watch panel
- Pullback status badges
- Symbol detail

### P3-F Rotation UI

Add：

- Rotation shadow panel
- new candidate vs weakest holding comparison
- opportunity delta table

---

## 8. Mobile Page Specification

Mobile is later phase, not P3-A.

### Route

Either：

```text
/mobile/lifecycle
```

or add Theme tab to existing mobile dashboard.

### Mobile layout

```text
Theme Mobile Dashboard
  ├─ 今日主題摘要
  ├─ 主戰場題材 cards
  ├─ 升溫 / 退潮 tabs
  ├─ 持股 Exit Review
  ├─ Pullback Watch
  └─ Missed Winner Alerts
```

### Mobile stage wording

| Stage | Wording |
|---|---|
| `EMERGING` | 觀察升溫 |
| `MAINSTREAM` | 主流延續 |
| `OVERHEATED` | 強但勿追 |
| `DISTRIBUTION` | 退潮警戒 |
| `DEAD` | 題材結束 |
| `DATA_GAP` | 資料不足 |

### Mobile safety

- 不出現「買進」按鈕。
- 不出現「賣出」按鈕。
- 只顯示 review / watch / no chase / wait pullback。

---

## 9. API Contract Summary

| Phase | API | Side effect |
|---|---|---|
| P3-A | `GET /api/theme-lifecycle/calibration?days=60` | none |
| P3-A | `GET /api/theme-lifecycle/data-gaps?days=60` | none |
| P3-B | `GET /api/theme-lifecycle/annotations/candidates?date=...` | none |
| P3-B | `GET /api/theme-lifecycle/annotations/watchlist?date=...` | none |
| P3-B | `GET /api/theme-lifecycle/annotations/ranking?date=...` | none |
| P3-B | `GET /api/theme-lifecycle/annotations/positions?date=...` | none |
| P3-C | `GET /api/theme-lifecycle/exit-review?date=...` | none |
| P3-C | `POST /api/theme-lifecycle/exit-review/rebuild?date=...` | writes review/shadow rows only |
| P3-D | `GET /api/ranking/topn-shadow/calibration?days=60` | none |
| P3-E | `GET /api/theme-pullback-shadow/plans?days=60` | none |
| P3-F | `GET /api/portfolio/rotation-shadow?days=60` | none |

---

## 10. Feature Flags

| Flag | Default | Purpose | Side effect |
|---|---:|---|---|
| `trading.lifecycle.calibration.enabled` | true | P3-A diagnostics | none |
| `trading.lifecycle.annotation.enabled` | false | P3-B annotation | none |
| `trading.lifecycle.exit-review.enabled` | false | P3-C review generation | review rows only |
| `trading.lifecycle.ranking-shadow.enabled` | false | P3-D dynamic TopN shadow | none |
| `trading.pullback.shadow.enabled` | false | P3-E pullback plan | shadow only |
| `trading.rotation.shadow.enabled` | false | P3-F rotation review | shadow only |
| `trading.lifecycle.production-buy-impact.enabled` | false | forbidden in P3 | should stay false |
| `trading.lifecycle.auto-sell.enabled` | false | forbidden in P3 | should stay false |

---

## 11. Testing Plan

### P3-A backend tests

Already added：

```text
ThemeLifecycleCalibrationServiceTest
ThemeLifecycleCalibrationControllerTest
```

Required checks：

- service calculation helpers
- controller JSON safety fields
- endpoint returns `readOnly/advisoryOnly/doesNotAffectBuySell`
- no production table count changes during API smoke

### P3-A UI tests / checks

Already verified：

- inline JS parse via `node --check`
- `mvn -q package -DskipTests`
- browser click Lifecycle tab
- API smoke
- DB count before/after unchanged

### All later phases

Every phase must verify no mutation to：

```text
candidate_stock
watchlist_stock
final_decision
paper_trade
position
stock_ranking_snapshot
portfolio_risk_decision
```

unless the phase explicitly allows a shadow/review table.

---

## 12. Rollback Plan

### P3-A backend

Rollback commit：

```text
git revert 2d1c213
```

No DB rollback needed because read-only.

### P3-A UI

Rollback commit：

```text
git revert ef9fe7b
```

No DB rollback needed because static UI only.

### Future shadow phases

Rollback pattern：

```text
feature flag false
delete shadow/review rows by run_id/date if needed
production tables unchanged
```

---

## 13. Commit Plan

Completed：

```text
2d1c213 feat(theme): add lifecycle calibration diagnostics
ef9fe7b feat(ui): add lifecycle calibration dashboard
```

Next suggested commits：

```text
docs(theme): add lifecycle promotion full specification
feat(theme): add lifecycle annotation diagnostics
feat(ui): add lifecycle annotation panels
feat(exit): add lifecycle exit review shadow
feat(ui): add lifecycle exit review dashboard
feat(shadow): add dynamic topn calibration
feat(shadow): add lifecycle pullback plans
feat(shadow): add portfolio rotation review
```

---

## 14. Exact Next Implementation Scope: P3-B Backend Annotation

### Codex task

Implement P3-B backend lifecycle annotation diagnostics.

### Scope

- Add `ThemeLifecycleAnnotationService`.
- Add annotation DTOs.
- Add read-only endpoints:

```http
GET /api/theme-lifecycle/annotations/candidates?date=YYYY-MM-DD
GET /api/theme-lifecycle/annotations/watchlist?date=YYYY-MM-DD
GET /api/theme-lifecycle/annotations/ranking?date=YYYY-MM-DD
GET /api/theme-lifecycle/annotations/positions?date=YYYY-MM-DD
```

### Required response safety fields

```json
{
  "annotationOnly": true,
  "advisoryOnly": true,
  "doesNotAffectBuySell": true
}
```

### Hard constraints

- No BUY.
- No SELL.
- No final decision change.
- No production ranking score change.
- No risk gate change.
- No candidate/watchlist write.
- No position mutation.

### Acceptance

- Tests pass.
- API smoke works.
- DB side-effect before/after counts unchanged.
- Diff does not touch production trading paths.

---

## 15. Final Direction

The system should evolve from：

```text
daily stock picking only
```

to：

```text
persistent theme lifecycle context
  → candidate / ranking / exit / portfolio annotation
  → shadow review
  → guarded production review
```

The lifecycle layer is a context layer, not an execution layer.

P3 goal is to make lifecycle visible, measurable, and reviewable.

P4 is the earliest point where guarded production promotion should be discussed.
