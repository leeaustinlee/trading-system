# Next Step Dev Plan (2026-04-17)

## 1. 目前基線
- 測試狀態：`mvn -Dmaven.repo.local=/tmp/m2 test` 已通過（27 tests, 0 fail）
- 已落地：Phase 1~3 核心引擎、Phase 5 AI adapter、主要 scheduler 與 API
- 主要缺口：整合測試、外部服務 end-to-end 驗證、`chasedHighEntry` 規則落地

## 2. 下一步開發目標（本輪）
1. 建立「可回歸」的整合測試骨架（優先）
2. 完成外部依賴驗證（TAIFEX / LINE / Claude）
3. 補齊交易欄位邏輯（`chasedHighEntry`）

## 3. 任務切片與驗收

### Slice A：整合測試骨架（P0）
- 範圍檔案：
  - `src/test/java/com/austin/trading/integration/*`
  - `src/test/resources/application-test.yml`（若尚未建立）
- 實作要點：
  - 建立 SpringBoot + DB 測試 profile，啟用 Flyway migration
  - 新增 3 條最小 E2E 測試路徑：
    - `POST /api/decisions/final/evaluate`（含候選輸入）
    - `GET /api/dashboard/current`（檢查組裝欄位）
    - `PATCH /api/positions/{id}/close`（驗證 realizedPnl）
- 驗收條件：
  - 測試可在本機穩定重跑 3 次皆通過
  - 不依賴手動資料修補

### Slice B：外部服務驗證（P1）
- 範圍檔案：
  - `src/main/java/com/austin/trading/client/TaifexClient.java`
  - `src/main/java/com/austin/trading/notify/LineSender.java`
  - `src/main/java/com/austin/trading/client/AiClaudeClient.java`
  - `docs/spec.md`（0.1/0.2 進度同步）
- 實作要點：
  - TAIFEX：記錄實際欄位映射、缺欄位 fallback 與錯誤訊息
  - LINE：實測 token，驗證成功/失敗記錄與 cooldown 行為
  - Claude：實測 API key，驗證 `ai_research_log` 入庫與輸出檔寫入
- 驗收條件：
  - 三項驗證各有成功 log 與一次失敗情境 log
  - 失敗情境不影響主流程（不中斷 scheduler）

### Slice C：`chasedHighEntry` 邏輯（P1）
- 範圍檔案：
  - `src/main/java/com/austin/trading/service/FinalDecisionService.java`
  - `src/main/java/com/austin/trading/entity/PositionEntity.java`（若需）
  - `src/test/java/com/austin/trading/engine/*` 或 `service/*` 測試
- 實作要點：
  - 定義追高判斷：進場價相對當日高點偏離門檻（建議先 0.5%）
  - 僅在資料完整時判斷；資料缺漏時需明確 fallback
- 驗收條件：
  - 至少 3 個測試情境：追高 / 非追高 / 資料不足
  - API response 可查得欄位結果

## 4. 建議執行順序
1. Slice A（先確保回歸保護）
2. Slice C（核心規則補齊）
3. Slice B（對外整合驗證）

## 5. 每次提交前固定檢查
```bash
mvn -Dmaven.repo.local=/tmp/m2 test
```

```bash
./scripts/load-local-seed.sh
```

```bash
./scripts/run-local.sh
```

## 6. 風險提示
- 目前專案目錄未偵測到 Git repo metadata（無法以 commit 歷史判斷進度）
- `docs/spec.md` 的「固定排程」段落含流程層時間（含 Claude 節點），與 Java scheduler 實際 cron 屬不同層；後續要維持雙軌說明，避免混淆

## 7. 執行狀態（2026-04-17）
- Slice A：已建立整合測試骨架（`ApiIntegrationTests`），覆蓋：
  - `POST /api/decisions/final/evaluate`
  - `GET /api/dashboard/current`
  - `PATCH /api/positions/{id}/close`（含 realizedPnl 驗證）
- 測試設定：
  - 新增 `application-integration.yml`
  - 使用獨立資料庫 `trading_system_it`
  - 關閉 scheduler / line / claude，避免排程與外部依賴干擾
- Slice C：已落地 `chasedHighEntry` 自動推算（14:00 檢討）
  - 新增 `ChasedHighEntryEngine`
  - `AftermarketReview1400Job` 改為自動判斷（MIS 日高優先，payload fallback）
  - 新增 3 組單元測試情境（追高 / 非追高 / 資料不足）
- Slice B：已完成「驗證框架」落地（待實機）
  - 新增 `GET /api/system/external/probe`，可檢查 TAIFEX / LINE / Claude
  - 新增 `GET /api/system/external/probe/history`，可查詢探針歷史
  - 新增 `GET /api/system/migration/health`，可檢查 V4/V5/V6 關鍵 migration 狀態
  - 新增 `ExternalProbeHealthJob`（dry-run + 失敗告警）
  - 支援 `liveLine` / `liveClaude` 參數，先 dry-run 再實發
  - 新增 V6 migration：`external_probe_log`（保存成功/失敗與細節）
  - `TaifexClient` 增加欄位 fallback，提高 API 欄位差異容錯
