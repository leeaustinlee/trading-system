# POSTMARKET Universe Unification Design

Pipeline 有接，但 Decision Context 不一致。

## 1. 問題摘要

目前 POSTMARKET pipeline 並不是斷線，而是同一輪 15:05 → 15:30 流程中，同時混用了兩個 universe 與兩套 market / theme 判斷上下文。

現況已確認：
- 15:05 Java `PostmarketDataPrepJob` 會建立 POSTMARKET task
- 15:18 Claude 會消化 task 並回寫 `CLAUDE_DONE`
- 15:28 Codex 會接手 finalize task
- 15:30 Java 會執行 postmarket workflow / report

真正問題不是 pipeline 沒接，而是 Claude、Codex、Java finalization 並沒有共享單一 Decision Context。

一句話定義：

「Pipeline 有接，但 Decision Context 不一致。」

具體症狀：
- Claude 分析 task allowed_symbols 的 5 檔
- Codex finalize task allowed_symbols 的 5 檔
- 但 Codex 報告又混入另一批 fresh scan 明日候選
- Claude / Codex marketGrade 可能分別自行推論，產生 B / A 不一致
- Codex 可能拿 fresh scan 強勢題材，去否決 task candidate 的題材延續性
- Claude prompt 寫成固定雙五結構，但 runtime `request.allowed_symbols` 實際可能只有 5 檔

## 2. 現況流程

### 現況資料流

```text
15:05 Java PostmarketDataPrepJob
  -> getCurrentCandidates(20)
  -> 建 task.allowed_symbols / targetCandidatesJson
  -> 寫 claude-research-request.json

15:18 Claude
  -> 研究 task.allowed_symbols
  -> 提交 claude result

15:28 Codex
  -> 先跑 market-breadth-scan fresh scan
  -> publish next-day candidates
  -> 再 review / finalize task.allowed_symbols
  -> 報告中混入 fresh scan strong themes / tomorrow watchlist

15:30 Java PostmarketAnalysis1530Job
  -> 讀取 task / Claude / Codex 結果
  -> finalize / persist / notify
```

### 問題點

1. fresh scan universe 與 task universe 不一致
   - Claude 看的是 Java 15:05 task universe
   - Codex 在正式 task review 前後，又重跑 fresh scan 產生 next-day watchlist universe
   - 同一份報告內混入 task universe 與 tomorrow watchlist universe

2. marketGrade source 不一致
   - Claude / Codex 可能各自從當下 market snapshot 或本地 fallback 推論
   - 缺少單一 truth source

3. theme confirmation source 不一致
   - task candidate 自身 `themeTag / reason / setup reason`
   - Java theme mapping / theme engine
   - fresh scan strong themes
   目前這三者混用，導致 Codex 可能用 B universe 的題材脈絡去否決 A universe candidate

4. report semantics 混在一起
   - 正式 task scoring 候選
   - 明日觀察 watchlist
   - 強勢族群敘事
   這三者目前沒有明確切乾淨

5. prompt contract 與 runtime request 不一致
   - prompt 寫「超強勢 5 + 中短線 5」
   - request.allowed_symbols 實際不一定是 10 檔
   - 造成 Claude 研究語義與提交 contract 脫鉤

## 3. 目標流程 V2

### V2 核心流程

```text
15:05 Java PostmarketDataPrepJob
  -> run / load fresh scan result
  -> build POSTMARKET universe
       - super_strong_5
       - final_candidates_5
       - dedupe
       - max 10 symbols
  -> compute marketGrade
  -> create task with allowed_symbols = unified universe
  -> write task market context

15:18 Claude
  -> analyze exactly task.allowed_symbols
  -> no symbols outside allowed_symbols

15:28 Codex
  -> review exactly task.allowed_symbols
  -> no independent hard universe replacement
  -> optional fresh scan reference only if it is already inside task context

15:30 Java
  -> finalize / persist / notify
```

### V2 設計重點

1. Java 在 15:05 建立正式 POSTMARKET scoring universe
   - 優先採用 `market-breadth-scan.json`
   - universe = `super_strong_5 + final_candidates_5`
   - dedupe 後最多 10 檔
   - 若 fresh scan 不存在 / 失敗，才 fallback `getCurrentCandidates()`

2. Java 在同一個步驟產生正式 market context
   - `marketGrade`
   - `marketGradeSource`
   - `universeSource`
   - `scoringUniverseSymbols`
   - `superStrongSymbols`
   - `finalCandidateSymbols`

3. Claude / Codex 都只讀 task-scoped universe
   - Claude 只能分析 request.allowed_symbols
   - Codex 只能 review task targetCandidatesJson / allowed_symbols
   - Codex 不可自行把 fresh scan symbols 混進 scoring universe

4. watchlist semantics 分離
   - tomorrow watchlist 可另外 publish / 另存 artifact
   - 但不直接混入本次 POSTMARKET scoring report 主體

## 4. 決策

### Adopted Decision A
POSTMARKET scoring universe 由 Java 15:05 建立，Claude / Codex 只使用 task-scoped universe。

說明：
- Java 是唯一建立正式 POSTMARKET universe 的角色
- Claude / Codex 都是消費者，不是 universe 替換者
- 後續若要擴充 universe，也必須回到 Java task construction 完成

### Adopted Decision B
Codex 不得在正式 task review 中用 fresh scan universe 的 theme 去 veto task candidate。

說明：
- candidate 自身 `themeTag / reason / Java theme mapping` 是 primary source
- fresh scan strong themes 只能作為 bonus reference
- fresh scan mismatch 不能直接轉成 hard penalty / veto

### Adopted Decision C
Codex 報告若仍顯示 next-day watchlist，必須獨立區塊標示：

「Next-day Watchlist，僅供明日觀察，不參與本次 task scoring。」

V2 實作建議：
- 主報告先不混入 next-day watchlist
- tomorrow watchlist 另輸出為獨立 artifact / 檔案

### Adopted Decision D
marketGrade 由 Java 統一計算並注入 Claude / Codex request。

說明：
- Java 15:05 task context 是正式 market context
- Claude / Codex 若讀到 task request 中的 marketGrade，必須直接沿用
- 只有 request 缺失時，才允許保守 fallback，且必須記 log

## 5. 修改範圍

預期涉及檔案：

- `src/main/java/com/austin/trading/scheduler/PostmarketDataPrepJob.java`
- `src/main/java/com/austin/trading/service/CandidateScanService.java`（如需補 helper / 對照）
- `src/main/java/com/austin/trading/service/ClaudeCodeRequestWriterService.java`
- `src/main/java/com/austin/trading/scheduler/PostmarketAnalysis1530Job.java`（若需補 finalize 語意）
- `D:/ai/stock/run-codex-v2-task.ps1`
- `docs/claude-schedule/prompt-1520-postmarket.md`
- Codex POSTMARKET report builder / payload builder（目前位於 `run-codex-v2-task.ps1`）
- market-breadth-scan 讀取 / publish 相關函式
- task DTO / request JSON schema / processed JSON schema（如有必要）

## 6. Acceptance Criteria

### AC1：單一 universe
同一輪 POSTMARKET task 中：
- `claude-research-request.json` `allowed_symbols`
- Claude processed output symbols
- Codex review payload symbols
- final report scoring symbols

必須一致。

### AC2：不得 cross-universe theme penalty
如果 candidate 不在 fresh scan strong themes，不能因此直接標記「題材延續未確認」並扣重分。

### AC3：marketGrade 一致
Claude 與 Codex 報告中的 marketGrade 必須一致，且來自同一個 Java task market context。

### AC4：prompt 與 request 一致
Claude prompt 不得要求分析 `request.allowed_symbols` 以外的 symbols。

### AC5：報告語意清楚
如果報告有 next-day watchlist，必須明確標示不參與本次 scoring。
V2 建議主報告先完全移除 tomorrow watchlist，另輸出 artifact。

### AC6：回歸測試
既有 premarket / intraday / position monitor 不得被破壞。

## 7. 測試計畫

### Unit tests

1. `PostmarketDataPrepJobUnifiedUniverseTest`
   - fresh scan 存在時：
     - task allowed symbols 取自 unified universe
     - `super_strong_5 + final_candidates_5` dedupe 後 <= 10
     - request market context 內含 `universeSource=FRESH_SCAN`
   - fresh scan 缺失時：
     - fallback `getCurrentCandidates()`
     - request market context 內含 `universeSource=FALLBACK_CURRENT_CANDIDATES`
     - log / summary 可追蹤 fallback

2. `MarketGradeContextConsistencyTest`
   - Java 15:05 產生 `marketGrade`
   - request `market_context_payload.marketGrade` 存在
   - snapshot / request / task summary 使用同一來源

3. `ClaudeRequestAllowedSymbolsContractTest`
   - `allowed_symbols` 與 `candidates` 一致
   - `tradingDate` / `trading_date` 可被 prompt 消費
   - `market_context_payload.scoringUniverse.symbols` 與 `allowed_symbols` 一致

### Script / integration tests

1. `CodexTaskScopedUniverseTest`
   - 給定 task.allowed_symbols = A
   - fresh scan = B
   - Codex scoring payload keys 只能來自 A
   - 不可把 B symbols 混入 `scores / selected / rejected / watchlist`

2. `ThemeConfirmationNoCrossUniversePenaltyTest`
   - candidate 自身有 `themeTag`
   - fresh scan strong themes 不匹配
   - Codex 不可自動塞入 `題材延續未確認`

3. `ReportSectionSeparationTest`
   - 主 POSTMARKET report 不含 tomorrow watchlist scoring 混入
   - 若另有 watchlist artifact，需清楚標示僅供明日觀察

### Manual dry-run

模擬一輪：
- fresh scan = A symbols
- fallback current candidates = B symbols
- 確認 task 最終只採設計指定的 universe
- Claude / Codex / final report symbols 一致

建議 dry-run：
1. 準備 sample `market-breadth-scan.json`
2. 觸發 `PostmarketDataPrepJob`
3. 檢查 `claude-research-request.json`
4. 準備 task + Claude result
5. 執行 `run-codex-v2-task.ps1 -Type POSTMARKET -NoSubmit`
6. 檢查 log / payload / markdown

### Log verification checklist

必查 log：
- `universeSource=FRESH_SCAN` 或 `FALLBACK_CURRENT_CANDIDATES`
- `task_scoring_symbols=...`
- `fresh_scan_symbols=...`
- `scoring_universe_source=...`
- `next_day_watchlist_source=...`
- `market_grade_source=JAVA_POSTMARKET_1505`
- fallback 觸發時有明確 warning / info log

## 8. 實作順序

1. Java `PostmarketDataPrepJob`
   - 建 unified universe
   - 計算 marketGrade
   - 把 universe + market context 寫入 request

2. `ClaudeCodeRequestWriterService`
   - request JSON 加入結構化 `market_context_payload`
   - 補齊 `tradingDate` 等欄位，減少 prompt / runtime mismatch

3. `run-codex-v2-task.ps1`
   - task-scoped universe only
   - marketGrade 優先讀 Java request context
   - theme confirmation 拆成 primary / bonus
   - tomorrow watchlist 與 scoring report 分離

4. `prompt-1520-postmarket.md`
   - 完全改成 allowed_symbols contract-first

5. 測試與 dry-run

## 9. 風險與相容性

- 不改自動下單，仍維持分析與通知
- 不改 premarket / intraday / position monitor 主流程
- PowerShell runner 需保留 POSTMARKET 之外時段行為
- 若 request market context 缺失，Codex 只能保守 fallback，且必須明確記 log
- 若 task candidates 為空，POSTMARKET 應視為 context 不完整，不應自行替換 scoring universe

## 10. 最終目標

POSTMARKET V2 應達成：

- Java 建 universe
- Claude 做研究
- Codex 做 review
- Java 做 finalization

四者都基於同一份 task context。

也就是：

- Single Universe
- Single Market Context
- Single Scoring Contract
- Separated Watchlist Semantics
