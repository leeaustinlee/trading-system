# Claude Code 排程研究架構

## 目的

此目錄存放 Claude 排程 Agent 的 5 個研究 prompt。這些 prompt 同時被**兩種執行環境**使用，提供雙軌可靠性。

## 執行架構（2026-05 現況）

| 執行軌 | 觸發來源 | 優勢 | 限制 |
|---|---|---|---|
| **Hermes 本機軌（現行）** | Hermes cron → `~/.hermes/scripts/stock_claude_*.py` → `scripts/hermes-run-claude.sh` → WSL Claude CLI | 現行唯一正式排程；可直連本機檔案與 Java / WSL 環境 | 依賴本機與 WSL 可用 |
| **舊 Windows / Cowork 軌（歷史）** | 舊 Task Scheduler / Cowork recurring task | 僅供歷史對照 | 已不應作為現行排程來源 |

**策略**：目前以 Hermes 本機軌為唯一正式 scheduler。若文件提到舊 Windows / Cowork 時序，只可視為歷史背景，不可覆蓋 Hermes 現行時序。

## 環境路徑翻譯（所有 prompt 通用）

每份 prompt 檔頭都有「環境與路徑解析」區塊，讓 Claude 自適應：

| 執行環境 | stock 根目錄路徑 |
|---|---|
| 本機 Windows PowerShell / WSL Claude CLI | `D:/ai/stock/` 或 `/mnt/d/ai/stock/` |
| Cowork cloud sandbox Claude | `/sessions/happy-gracious-pasteur/mnt/stock/` |

兩邊指向同一份檔案。prompt 檔正文的 `D:/ai/stock/...` 都要被翻譯成對應路徑。

## 整體流程（v2.1 File Bridge 協定）

```
Codex DataPrep（Windows 排程 PS1 / 本機軌）
  → 更新 D:/ai/stock/market-snapshot.json
  → 寫出 D:/ai/stock/codex-research-latest.md

Java Workflow（Spring Boot）
  → 建立 ai_task（PENDING）
  → 寫出 D:/ai/stock/claude-research-request.json
    （內含 taskId / taskType / submit_filename_hint / allowed_symbols）

Claude（本機軌 或 Cowork 軌，依 prompt 時段）
  → 讀 claude-research-request.json 取 taskId 與 submit_filename_hint
  → 讀 market-snapshot.json / codex-research-latest.md / 規則 MD
  → 做深度研究，寫 claude-research-latest.md
  → 寫 D:/ai/stock/claude-submit/<submit_filename_hint>.tmp
  → rename 成 .json（原子寫檔）

Java ClaudeSubmitWatcher（每 30 秒輪詢）
  → 掃到 claude-submit/*.json
  → 驗 schema + taskId
  → 寫 DB（stock_evaluation.claude_score + ai_task → CLAUDE_DONE）
  → 移動到 processed/<filename>.processed.json

Codex / Java Workflow（讀 CLAUDE_DONE task）
  → 從 DB 讀 Claude 分數與研究 md
  → FinalDecisionService 觸發 consensus + veto + finalRank
  → LineTemplateService 發 LINE
```

**關鍵規則**：

- Claude **不得呼叫** `http://localhost:8888`（本機軌可打但不該；Cowork 軌本就打不到）
- 檔名由 `claude-research-request.json.submit_filename_hint` 決定，**不要自創**
- 寫檔**必須**走 `.tmp → rename` 協定，否則 watcher 可能讀到半成品進 `failed/`
- `scores / thesis` 的 key 必須是 `allowed_symbols` 的子集

## 任務類型對照

| 時段 | 本機 cron | Cowork cron | taskType | 對應 Codex 通知 |
|---|---|---|---|---|
| 08:20 盤前 | `20 8 * * 1-5` | legacy 同名 | `PREMARKET` | 08:30 `AustinStockPremarket0830` |
| 09:20 開盤 | `20 9 * * 1-5` | legacy 同名 | `OPENING` | 09:30 `AustinStockStrategy0930` |
| 11:15 盤中 | `15 11 * * 1-5` | legacy 舊文件常寫 10:50 | `MIDDAY` | 11:25 Codex / 11:30 final |
| 15:18 盤後 | `18 15 * * 1-5` | legacy 舊文件常寫 15:20 | `POSTMARKET` | 15:28 Codex / 15:30 `AustinStockAftermarket1530` |
| 18:18 明日 | `18 18 * * 1-5` | legacy 舊文件常寫 17:50 / 18:20 | `T86_TOMORROW` | 18:28 Codex / 18:30 `TomorrowPlan1800Job` |

## 5 個 Prompt 檔

| 檔案 | 執行時間 | 任務 |
|---|---|---|
| `prompt-0820-premarket.md` | 08:20 週一至週五 | 盤前候選研究（超強勢 5 + 中短線候選 5） |
| `prompt-0920-opening.md` | 09:20 週一至週五 | 開盤候選確認（即時報價複核 + 10 分鐘方向） |
| `prompt-1050-midday.md` | 11:15 週一至週五（legacy 檔名） | 盤中持倉追蹤 + monitor_mode 建議；晚於 Java 11:00 建 task |
| `prompt-1520-postmarket.md` | 15:18 週一至週五（legacy 檔名） | 盤後候選 5 檔 + 超強勢 5 檔研究；晚於 Java 15:05 建 task |
| `prompt-1750-tomorrow.md` | 18:18 週一至週五（legacy 檔名） | T86 後明日策略；現行 Hermes 時序晚於 Java 18:10 建 task |

## 本機軌設定（Windows Task Scheduler + WSL）

詳見 `windows-task-setup.md`。入口 PowerShell：`D:\ai\stock\run-claude-research.ps1`，呼叫 WSL Claude CLI 並傳入 prompt 檔路徑。

已知問題：PowerShell → WSL → claude CLI 的 arg tokenize 偶有問題（範例：今天早上 `claude-PREMARKET-2026-04-23-0847.failed.json`，後補跑 0847b 才成功）。

## Cowork 軌設定（2026-04-23 新增）

### Cowork Scheduled Task IDs

| Task ID | 用途 | 下次執行觀察位置 |
|---|---|---|
| `stock-ai-claude-premarket` | PREMARKET 08:20 | Hermes cron |
| `stock-ai-claude-opening` | OPENING 09:20 | Hermes cron |
| `stock-ai-claude-midday` | MIDDAY 11:15 | Hermes cron |
| `stock-ai-claude-postmarket` | POSTMARKET 15:18 | Hermes cron |
| `stock-ai-claude-tomorrow` | T86_TOMORROW 18:18 | Hermes cron |

### Cowork Deterministic Jitter

Cowork 對 recurring task 加一個 **per-taskId 固定 jitter**（為了分散服務端負載），實際觸發時間 = cron 時間 + jitter。目前觀察（2026-04-23）：

| Task | Cron 設定 | 實際觸發 | Jitter |
|---|---:|---:|---:|
| PREMARKET | 08:20 | 08:20 | +8s |
| OPENING | 09:20 | 09:22 | +94s |
| MIDDAY | legacy 10:50 | legacy 10:52 | +94s |
| POSTMARKET | legacy 15:20 | 歷史資料，現行改由 Hermes 15:18 | n/a |
| TOMORROW | legacy 17:50 | 歷史資料，現行改由 Hermes 18:18 | n/a |

歷史 Cowork POSTMARKET 15:26 → Java 15:30 job 間隔只剩 4 分鐘；現行 Hermes 改為 15:18，保留 Java 15:05 建 task 後的緩衝，也讓 Codex 15:28 有時間接手。

### Cowork 網路限制

**Cowork sandbox 無法直連外部網路**（至少無法直連 `mis.twse.com.tw`、`localhost:8888`），所以 Cowork 軌的 Claude：

- ❌ 不能抓 TWSE MIS API 即時報價
- ❌ 不能打 Java localhost API
- ✅ 只能用 `market-snapshot.json` 的 Codex DataPrep 快照（新鮮度依賴本機 Codex 有跑）

Cowork Claude 的研究品質因此略低於本機軌，但勝在 24/7 不掛。Claude 會在 `claude-research-latest.md` 自動標註資料限制（例：「Cowork sandbox 無法直連 TWSE MIS，本輪使用 09:55 snapshot 外推」）。

### 首次執行與工具權限

Cowork scheduled task 第一次跑時 Claude 會請求 Read / Write / Bash / Glob 權限。建議在建完 task 後**手動點「Run now」一次**，預先批准所有工具權限 —— 未來自動執行就不會卡在 permission prompt。

## 分工原則

| 角色 | 負責 |
|---|---|
| **Codex** | 選題材、抓行情、法人資金、最終 LINE 決策（由 Java 代發） |
| **Claude** | 個股基本面、籌碼面、技術面、風險深度研究；寫 claude-submit 給 Java |
| **Java** | 資料持久化、DB、API、UI、寫 `claude-research-request.json`、掃 `claude-submit/`、發 LINE |

## 規則檔位置

專案內備份（版本控制用）：

```
docs/ai-rules/
  AI_RULES_INDEX.md
  dual-ai-workflow.md
  claude-research-prompt.md
  final-notification-flow.md
  market-data-protocol.md
  trade-decision-engine.md
  market-gate-self-optimization-engine.md
  five-minute-monitor-decision-engine.md
  claude-live-price-check-prompt.md
```

Runtime 資料（不在版本控制）：

```
D:/ai/stock/market-snapshot.json       ← Codex DataPrep 更新
D:/ai/stock/codex-research-latest.md   ← Codex DataPrep 更新
D:/ai/stock/capital-summary.md         ← 手動維護
D:/ai/stock/claude-research-request.json ← Java DataPrep Job 更新
D:/ai/stock/claude-research-latest.md  ← Claude 研究輸出
D:/ai/stock/claude-submit/             ← Claude 寫入、Java watcher 處理
  ├─ *.tmp                             ← 寫檔中，watcher 忽略
  ├─ *.json                            ← 寫完，watcher 會在 30 秒內處理
  ├─ processed/*.processed.json        ← Java 已 submit 成功
  └─ failed/*.failed.json              ← schema 驗證或 submit 失敗
```

## 故障排查

| 症狀 | 可能原因 | 處置 |
|---|---|---|
| 本機軌 .failed.json 出現 | PowerShell arg tokenize bug、schema 錯誤 | 開檔看內容；Cowork 軌若有成功則可忽略 |
| Cowork/legacy 軌 `△ 只寫 md 沒寫 submit` | `taskType` 不符（例如 10:50 MIDDAY 早於 Java 11:00 建 task，或舊 17:50 早於 T86 18:10 建 task） | 排程漂移；現行 Hermes 應使用 11:15 MIDDAY / 18:18 T86 |
| 15:30 LINE 沒有 Claude 分數 | POSTMARKET task 還在 PENDING | 檢查 claude-submit/processed/ 有沒有今天的 POSTMARKET；若沒有，看 failed/ |
| Java 兩邊都沒 submit | Java App 掛掉 → watcher 沒在跑 | 跑 `scripts/recover-and-catchup.sh`；建議裝 `scripts/trading-system.service` systemd unit |

## 相關文件

- `docs/SYSTEM-OVERVIEW-v2.md` — 系統總覽與每日時序
- `docs/claude-handoff.md` — Claude File Bridge 協定細節
- `docs/codex-handoff.md` — Codex 提交規則
- `docs/workflow-correctness-ai-orchestration-spec.md` — ai_task 狀態機規格
- `windows-task-setup.md` — 本機 Windows Task Scheduler 設定

## Theme-first MVP-3 governance submit rule

若 request JSON 含 `prompt_governance_contract` / `theme_governance_trace` / `leadership_symbols` / `peer_shadow_candidates` / divergence or taxonomy gap signals，Claude submit JSON 必須包含並填寫：

- `leadership_analysis`
- `divergence_analysis`
- `taxonomy_gap_analysis`
- `peer_shadow_analysis`

`peer_shadow_candidates` 與 universe 外 symbol 只能作 observability / shadow-only 分析；不得進 `scores`、`thesis`、`final_enter_candidates`。如需提及 universe 外 symbol，標示 `OUTSIDE_ALLOWED_UNIVERSE_SHADOW_ONLY`。
