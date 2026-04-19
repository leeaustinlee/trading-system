# Claude Code 排程研究架構

## 目的

此目錄存放 Claude Code 排程 Agent 的 5 個研究 prompt。
不需要 Anthropic API Key — Claude Code 本身即為執行環境。

## 整體流程（PR-2 新流程：AI 任務佇列 + 自動分數回寫）

```
Codex DataPrep（Windows 排程 PS1）
  → 更新 D:/ai/stock/market-snapshot.json
  → 寫出 D:/ai/stock/codex-research-latest.md

Java Workflow（Spring Boot）
  → 更新 DB snapshot
  → 寫出 D:/ai/stock/claude-research-request.json
  → **POST 建立 ai_task（PENDING）**  ← PR-2 新增

Claude Code 排程 Agent（本目錄 prompt）
  → GET /api/ai/tasks/pending?type=XXX 認領任務
  → 讀 market-snapshot.json + request.json + 規則 MD
  → 做深度研究，寫 claude-research-latest.md（備份）
  → **POST /api/ai/tasks/{id}/claude-result**（含 scores map）
    → Service 自動寫 stock_evaluation.claude_score
    → 自動觸發 consensus / finalRank 重算
    → 同步寫 ai_research_log（舊 API 相容）
  → 若 API 失敗才請 Austin 用 UI 手動補匯

Codex 最終通知（Windows 排程 PS1）
  → 從 DB 讀 Claude 研究 & candidate.claudeScore（不再讀 md）
  → FinalDecisionService 可直接用 Claude 分數
  → 發 LINE 給 Austin
```

## 任務佇列規則（PR-2，2026-04-19 起）

所有 Claude 研究完成後必須回報到 ai_task，不再只寫 md 或 ai_research_log。

| 時段 | taskType |
|---|---|
| 08:20 盤前 | `PREMARKET` |
| 09:20 開盤 | `OPENING` |
| 10:50 盤中 | `MIDDAY` |
| 15:20 盤後 | `POSTMARKET` |
| 17:50 明日 | `T86_TOMORROW` |
| 個股研究 | `STOCK_EVAL`（target_symbol 必填） |

**新流程優先**：`POST /api/ai/tasks/{id}/claude-result` 會自動寫 stock_evaluation.claude_score，
FinalDecisionService 在 09:30 就能看到 Claude 分數。

**Fallback**：舊 `POST /api/ai/research/import-file` 仍保留，但只寫 ai_research_log，
不會觸發 stock_evaluation 回寫 — FinalDecision 拿不到分數。只在 ai_task 不存在時才用。

## 分工原則

| 角色 | 負責 |
|---|---|
| **Codex** | 選題材、抓行情、法人資金、最終 LINE 決策 |
| **Claude** | 個股基本面、籌碼面、技術面、風險深度研究 |
| **Java** | 資料持久化、DB、API、UI、寫出 context 檔 |

## 5 個排程 Prompt

| 檔案 | 執行時間 | 任務 | 對應 Codex 通知 |
|---|---|---|---|
| `prompt-0820-premarket.md` | 08:20 週一至週五 | 盤前候選研究 | 08:30 |
| `prompt-0920-opening.md` | 09:20 週一至週五 | 開盤候選確認 | 09:30 |
| `prompt-1050-midday.md` | 10:50 週一至週五 | 盤中持倉研究 | 11:00 |
| `prompt-1520-postmarket.md` | 15:20 週一至週五 | 盤後候選 5 檔研究 | 15:30 |
| `prompt-1750-tomorrow.md` | 17:50 週一至週五 | T86 後明日策略 | 18:00 |

## 設定 Claude Code 排程

用 `/schedule` 指令建立各任務。每個任務的 prompt 格式：

```
請讀取 /mnt/d/ai/stock/trading-system/docs/claude-schedule/prompt-XXXX-xxx.md
並執行該檔案定義的研究任務。
```

cron 格式（Asia/Taipei）：
- 08:20 → `20 8 * * 1-5`
- 09:20 → `20 9 * * 1-5`
- 10:50 → `50 10 * * 1-5`
- 15:20 → `20 15 * * 1-5`
- 17:50 → `50 17 * * 1-5`

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
```
