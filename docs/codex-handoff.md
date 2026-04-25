# Codex Handoff — Java Task Queue v2

本文件給 Austin 台股 Java App 串接 Codex 使用。Codex 不直接發 LINE，只提交 `codex-result`；Java 負責固定時間發 LINE。

## 時段對應

| Java taskType | Claude 完成 | Codex 提交前 | Java 發 LINE |
|---|---:|---:|---:|
| `PREMARKET` | 08:25 | 08:28 | 08:30 |
| `OPENING` | 09:25 | 09:28 | 09:30 |
| `MIDDAY` | 10:55 | 10:58 | 11:00 |
| `POSTMARKET` | 15:25 | 15:28 | 15:30 |
| `T86_TOMORROW` | 17:55 | 17:58 | 18:00 |

## Codex 執行步驟

1. 讀規則：`D:\ai\stock\codex-role-v2-prompt.md`。
2. 找 `CLAUDE_DONE` task。
3. 讀 task 的 `claudeScoresJson` 與 `D:\ai\stock\claude-research-latest.md`。
4. 補 Java DB 狀態與 TWSE MIS 報價。
5. 套市場 Gate、交易決策引擎、資金限制與 veto。
6. 提交 `POST /api/ai/tasks/{id}/codex-result`。
7. 寫 `D:\ai\stock\codex-research-latest.md`。

## Windows 排程

v1 PowerShell 通知任務已停用。v2 使用：

| 任務名 | 時間 | Type |
|---|---:|---|
| `Codex-Premarket-0828` | 08:28 | `PREMARKET` |
| `Codex-Opening-0928` | 09:28 | `OPENING` |
| `Codex-Midday-1058` | 10:58 | `MIDDAY` |
| `Codex-Postmarket-1528` | 15:28 | `POSTMARKET` |
| `Codex-TomorrowPlan-1758` | 17:58 | `T86_TOMORROW` |

排程執行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\ai\stock\run-codex-v2-task.ps1 -Type <TYPE>
```

## 候選股權威來源

每一輪 Codex 必須以 Java `ai_task.targetCandidatesJson` 作為本輪候選 universe 的權威來源。
這可避免 `/api/candidates/current` 因跨時段、跨日或人工刷新而污染本輪 Claude / Codex
共同研究範圍。

只有以下情況才可 fallback 到 `GET /api/candidates/current`：

- `targetCandidatesJson` 為空陣列或無法解析。
- task payload 明確標示本輪候選來源需要 fallback。
- Java API 回傳 task 本身沒有任何候選，但流程仍要求保守產出觀察/休息結論。

若使用 fallback，Codex 必須在 markdown 標示「候選來源：/api/candidates/current fallback」。
Claude 的 `claudeScoresJson` 只對本輪候選 universe 中能匹配到的 symbol 有效，
其餘 symbol 只能作研究脈絡參考，不得直接用於 Codex 評分。

## API

```http
GET  http://localhost:8080/api/ai/tasks
GET  http://localhost:8080/api/ai/tasks/{id}
POST http://localhost:8080/api/ai/tasks/{id}/codex-result
POST http://localhost:8080/api/ai/tasks/{id}/finalize
POST http://localhost:8080/api/ai/tasks/{id}/fail
```

## Submit Body

```json
{
  "contentMarkdown": "完整 Codex 決策 Markdown",
  "scores": {"3231": 8.0, "2303": 7.5},
  "vetoSymbols": ["6213"],
  "reviewIssues": {"6213": "外資籌碼凌亂，等深回測"}
}
```

## 成功條件

- API 回傳 `success=true`。
- Task 狀態變為 `CODEX_DONE`。
- `D:\ai\stock\codex-research-latest.md` 已更新。
- 對應時段 latest 檔已更新，例如 `codex-opening-latest.md` / `codex-postmarket-latest.md`。
- `D:\ai\stock\codex-result-{taskId}.json` 已保存。
- Java 下一次 workflow 能讀取 `CODEX_DONE` 並發 LINE。

## 禁止事項

- Codex 不直接呼叫 LINE API。
- Codex 不管理 LINE token。
- Codex 不在市場等級 C 或硬性風控觸發時推薦進場。
- Codex 不推薦超過單檔 3-5 萬或破壞 30% 現金保留的標的。

## v2.1 Workflow Correctness 更新

完整規格見 `docs/workflow-correctness-ai-orchestration-spec.md`。

Codex 必須遵守：

- 只處理狀態為 `CLAUDE_DONE` 的 task。
- 若 task 狀態不是 `CLAUDE_DONE`，不可 submit `codex-result`，應回報 `CODEX_WAITING_FOR_CLAUDE` 或 `AI_TASK_INVALID_STATE`。
- submit 成功後 task 狀態必須是 `CODEX_DONE`。
- Codex 不得 finalize task；finalize 由 Java Decision / Notify workflow 在使用結果後處理。
- Codex 不直接發 LINE。
- Codex 不讀過期 markdown 作為權威來源；DB task 的 Claude result 才是權威來源。
- 若 Claude 未完成，Codex 不應自行完整推薦股票，只能提交降級審核或讓 Java fallback。

FinalDecision 預設 `final_decision.require_codex=true`；Codex 未完成時，Java 不得輸出正常 ENTER。
