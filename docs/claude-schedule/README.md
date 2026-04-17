# Claude Code 排程研究架構

## 目的

此目錄存放 Claude Code 排程 Agent 的 5 個研究 prompt。
不需要 Anthropic API Key — Claude Code 本身即為執行環境。

## 整體流程（每個時段三段式）

```
Codex DataPrep（Windows 排程 PS1）
  → 更新 D:/ai/stock/market-snapshot.json
  → 寫出 D:/ai/stock/codex-research-latest.md

Java DataPrep Job（Spring Boot 排程）
  → 更新 DB snapshot
  → 寫出 D:/ai/stock/claude-research-request.json

Claude Code 排程 Agent（本目錄 prompt）
  → 讀 market-snapshot.json + request.json + 規則 MD
  → 做深度研究
  → 寫出 D:/ai/stock/claude-research-latest.md

Codex 最終通知（Windows 排程 PS1）
  → 讀 claude-research-latest.md
  → 發 LINE 給 Austin
```

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
