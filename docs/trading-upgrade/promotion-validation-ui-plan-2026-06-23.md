# Promotion Validation / Report-only 接線與 UI/UX 改善計畫（2026-06-23）

## 結論摘要

目前功能不是完全沒跑，而是分成兩層：

1. **Theme Ops / Promotion Review Queue 已經有資料**
   - 2026-06-22：promotion review queue 共有 265 筆
   - 2026-06-23：promotion review queue 共有 298 筆

2. **Promotion Validation Daily Summary 預設查 `CANDIDATE_POOL_SHADOW`，所以顯示 0 筆**
   - 目前 queue 裡主要狀態是：
     - `RESEARCH_ONLY`
     - `PENDING_REVIEW`
   - 沒有任何 `CANDIDATE_POOL_SHADOW`
   - 因此 validation summary 用預設 status 跑時，結果是 `items=0 / NEED_MORE_EVIDENCE`

這代表：
- 監控 / validation / report-only pipeline 有接上。
- 但 validation summary 預設看的狀態不符合目前 queue 實際資料，所以看起來像「沒有資料」。
- 目前完全沒有接到正式選股權重、FinalDecision、candidate_stock 或 production score。

---

## Part 1：資料與排程接線調查

### 1.1 目前怎麼跑

#### ThemeOpsDailyBuildJob

目的：每日建立 Theme Ops 與 Promotion Review Queue 的上游資料。

程式：
- `src/main/java/com/austin/trading/scheduler/ThemeOpsDailyBuildJob.java`
- `src/main/java/com/austin/trading/service/BuildOperationsService.java`

排程：
- Java `@Scheduled` 預設：`0 25 15 * * MON-FRI`
- 實際時間：15:25

它會跑：
1. theme replay
2. lifecycle
3. replay metrics
4. hot group radar
5. research universe
6. promotion review
7. theme intelligence snapshot

#### PromotionValidationDailySummaryJob

目的：每日做 report-only validation summary。

程式：
- `src/main/java/com/austin/trading/scheduler/PromotionValidationDailySummaryJob.java`
- `src/main/java/com/austin/trading/service/PromotionValidationDailySummaryService.java`

排程：
- `0 0 16 * * MON-FRI`
- 實際時間：16:00

流程：
1. `bridgeForwardTracking(date, date, status)`
2. `backfillReturns(14)`
3. `validationReport(date, date, status)`

預設 status：
- `CANDIDATE_POOL_SHADOW`

### 1.2 昨天為什麼看起來沒跑

實際上昨天有跑：

- `ThemeOpsDailyBuildJob`
  - lastRunAt：2026-06-22 15:25
  - status：SUCCESS
  - reason：`themeOpsDailyBuild SUCCESS date=2026-06-22 layers=7`

- `PromotionValidationDailySummaryJob`
  - lastRunAt：2026-06-22 16:00
  - status：SUCCESS
  - reason：`promotionValidationDailySummary SUCCESS date=2026-06-22 overallStatus=NEED_MORE_EVIDENCE items=0`

問題是它「有跑但查錯狀態」。

### 1.3 實際資料狀態

2026-06-22 promotion queue：265 筆

狀態分布：
- `RESEARCH_ONLY`：248
- `PENDING_REVIEW`：17
- `CANDIDATE_POOL_SHADOW`：0

2026-06-23 promotion queue：298 筆

狀態分布：
- `RESEARCH_ONLY`：281
- `PENDING_REVIEW`：17
- `CANDIDATE_POOL_SHADOW`：0

所以 daily summary 預設查 `CANDIDATE_POOL_SHADOW` 時，必然 0 筆。

### 1.4 為什麼 queue 有資料但 validation 沒資料

目前 validation API 預設：

```text
GET /api/promotion-review/validation-report?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD&status=CANDIDATE_POOL_SHADOW
```

但 queue 目前還沒有任何資料被人工或系統改成 `CANDIDATE_POOL_SHADOW`。

若改查：

```text
status=RESEARCH_ONLY
status=PENDING_REVIEW
```

就會看到資料，但多數仍是：

```text
BLOCKED_BY_DATA_GAP
```

原因：forward tracking 的 T1/T5/T10 報酬尚未累積完成，尤其當日資料本來就不可能有 T5。

### 1.5 後端修正建議

#### Phase B1：讓 report-only summary 顯示「真實 queue 狀態」

目標：避免 items=0 造成誤判。

建議改法：
- Daily summary 不應只看 `CANDIDATE_POOL_SHADOW`。
- 改成同時摘要：
  - `PENDING_REVIEW`
  - `RESEARCH_ONLY`
  - `CANDIDATE_POOL_SHADOW`
  - `WATCH_ONLY`
  - `BLOCKED_BY_RISK`
- 顯示每個 status 的 itemCount / dataGap / evidenceReady。

不改：
- FinalDecision
- candidate_stock
- production score
- 選股權重

#### Phase B2：補一個「All Status Validation Summary」API

新增或擴充：

```text
GET /api/promotion-review/validation-summary?date=YYYY-MM-DD
```

回傳：

```json
{
  "reportOnly": true,
  "doesNotAffectFinalDecision": true,
  "doesNotWriteCandidateStock": true,
  "doesNotWriteProductionScore": true,
  "date": "YYYY-MM-DD",
  "totalQueueItems": 298,
  "byStatus": [
    {"status":"RESEARCH_ONLY", "itemCount":281, "dataGapCount":281},
    {"status":"PENDING_REVIEW", "itemCount":17, "dataGapCount":17},
    {"status":"CANDIDATE_POOL_SHADOW", "itemCount":0}
  ],
  "overallStatus": "BLOCKED_BY_DATA_GAP"
}
```

#### Phase B3：排程時間顯示與文件修正

目前 Java listJobs 顯示 ThemeOpsDailyBuildJob cron 為空字串，但實際 `@Scheduled` 有 default `15:25`。

建議：
- 在 `application.yml` 明確加入：

```yaml
trading:
  scheduler:
    theme-ops-daily-build-cron: "0 25 15 * * MON-FRI"
```

這樣 UI/API 會顯示清楚，不會以為沒排程。

---

## Part 2：UI/UX 與前端改善計畫

### 2.1 主要問題

雙 AI read-only 檢查一致結論：

1. 英文 code/status 直接外露，使用者看不懂。
2. Report-only / Shadow-only / Review-only 安全邊界對工程師清楚，但對使用者不直覺。
3. Promotion / Validation / Graduation 流程沒有被包成一個清楚工作台。
4. 桌機表格太寬，欄位太多。
5. 手機只顯示 Promotion Queue，沒有完整 validation / graduation 摘要。
6. 桌機與手機 formatter、badge、status mapping 不一致。
7. 按鈕名稱偏工程語言，例如 Build / Bridge / Backfill。

### 2.2 產品資訊架構

建議把目前 Promotion/Validation 區域整理成：

```text
題材晉級審核中心

目前模式：僅報告 / Shadow 觀察 / 不影響交易

流程：
1. 發現題材 / 股票
2. 建立審核清單
3. 策略模擬與後續追蹤
4. 驗證報告
5. 畢業準備度 / 門檻建議
```

### 2.3 中文化規劃

#### 狀態中文化

| 原始代碼 | 中文顯示 | 說明 |
|---|---|---|
| `PENDING_REVIEW` | 待人工審核 | 等待人工確認 |
| `RESEARCH_ONLY` | 僅研究 | 只作研究紀錄，不作交易 |
| `CANDIDATE_POOL_SHADOW` | 影子候選 | 只在 Shadow 觀察，不可交易 |
| `WATCH_ONLY` | 僅觀察 | 保留觀察，不推進 |
| `NEED_MORE_EVIDENCE` | 證據不足 | 樣本或績效不足 |
| `BLOCKED_BY_DATA_GAP` | 資料不足 | 缺後續報酬或追蹤資料 |
| `BLOCKED_BY_RISK` | 風險阻擋 | 風控條件未通過 |
| `BLOCKED_BY_GOVERNANCE` | 治理阻擋 | 規則或人工審核未通過 |
| `ELIGIBLE_FOR_SOFT_BOOST_SHADOW` | 可進 Shadow 加分觀察 | 僅 Shadow，不改正式分數 |

#### 安全旗標中文化

| 原始旗標 | 中文顯示 |
|---|---|
| `reviewOnly` | 僅審核 |
| `reportOnly` | 僅報告 |
| `simulationOnly` | 僅模擬 |
| `doesNotAffectFinalDecision` | 不影響最終決策 |
| `doesNotAffectBuySellEnter` | 不影響買賣進場 |
| `doesNotWriteCandidateStock` | 不寫入正式候選股 |
| `doesNotWriteProductionScore` | 不修改正式分數 |
| `noAutoPromotion` | 不自動升級 |
| `noThresholdMutation` | 不自動改門檻 |
| `candidatePoolShadowIsNotTradable` | Shadow 候選不可交易 |

### 2.4 前端工程分批 plan

#### Phase F1：快速中文化與降噪

目標：先解決「看不懂」。

修改：
- `src/main/resources/static/index.html`
- `src/main/resources/static/mobile.html`

內容：
- 建立 status / safety flag 中文字典。
- 所有 badge 顯示中文主標。
- 英文 raw code 改成小字或 tooltip。
- 按鈕中文化：
  - Build / Rebuild Review Queue → 重新產生審核清單
  - Bridge Forward Tracking → 建立追蹤紀錄
  - Backfill Returns → 補齊報酬資料
  - Run Daily Summary → 產生每日驗證摘要

#### Phase F2：共用 formatter / view model

目標：桌機與手機一致。

建議新增：
- `src/main/resources/static/js/promotion-ui-dictionary.js`
- `src/main/resources/static/js/promotion-ui-formatters.js`
- `src/main/resources/static/js/promotion-view-models.js`

內容：
- `STATUS_META`
- `SAFETY_FLAG_META`
- `statusLabel()`
- `statusTone()`
- `safetyFlagLabel()`
- `formatPercent()`
- `buildPromotionReviewViewModel()`
- `buildValidationReportViewModel()`
- `buildGraduationReadinessViewModel()`

#### Phase F3：手機補齊 validation/report-only pipeline

手機新增卡片：
- 晉級審核摘要
- 策略模擬摘要
- 驗證報告摘要
- 畢業準備度摘要
- 資料缺口摘要

手機每筆資料用卡片，不用寬表格。

#### Phase F4：桌機整理成「題材晉級審核中心」

桌機頁面新增：
- 頁首安全模式卡
- 五步驟流程導覽
- 今日結論卡
- 審核清單
- 模擬與驗證
- 畢業準備度

#### Phase F5：按鈕安全 UX

不同動作分級：
- 查詢：一般按鈕
- report-only：資訊按鈕
- tracking write / backfill：警示按鈕 + 中文確認

所有成功/失敗都用中文提示。

---

## 建議立即執行順序

1. 後端先修正 daily summary 狀態查詢邏輯，讓報表看得到 queue 實際資料。
2. UI 先做中文 dictionary + safety labels，最快改善可讀性。
3. 手機補齊 validation/graduation 摘要。
4. 桌機整理成「題材晉級審核中心」。
5. 最後再做 threshold/promotion evidence 的人工審核流程；在此之前不得接選股權重。

---

## 驗證指令

```bash
cd /mnt/d/ai/stock/trading-system
mvn -q -DskipTests compile
mvn -q test
curl -s http://localhost:8888/api/scheduler/jobs
curl -s 'http://localhost:8888/api/promotion-review/queue?date=2026-06-23'
curl -s 'http://localhost:8888/api/promotion-review/validation-report?startDate=2026-06-23&endDate=2026-06-23&status=PENDING_REVIEW'
curl -s 'http://localhost:8888/api/promotion-review/validation-report?startDate=2026-06-23&endDate=2026-06-23&status=RESEARCH_ONLY'
```

驗收重點：
- Queue 有資料。
- Validation summary 不再只顯示 0。
- 所有輸出仍標記 report-only / doesNotAffectFinalDecision / noAutoPromotion。
- UI 不再直接以英文 code 作為主要顯示。
