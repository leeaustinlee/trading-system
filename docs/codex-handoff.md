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

## 候選股 fallback（週一 / 假日後首個交易日必做）

Java `PremarketDataPrepJob` 在 08:10 建 task 時，若「昨日」是週末或假日，
會自動 fallback 到 DB 最新有候選的交易日。但 task 的 `targetCandidatesJson`
不保證永遠同步最新 DB 狀態，**Codex 必須額外呼叫** `GET /api/candidates/current`
取真實候選股清單，以此為主線。若兩者不一致：

- 以 `/api/candidates/current` 為準。
- 在 markdown 標示「候選來源：最新交易日 YYYY-MM-DD（週末/假日 fallback）」。
- Claude 的 `claudeScoresJson` 只對能匹配到 `/api/candidates/current` 的 symbol 有效，
  其餘 symbol 作 Claude 研究脈絡參考，不直接用於 Codex 評分。

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
- Java 下一次 workflow 能讀取 `CODEX_DONE` 並發 LINE。

## 禁止事項

- Codex 不直接呼叫 LINE API。
- Codex 不管理 LINE token。
- Codex 不在市場等級 C 或硬性風控觸發時推薦進場。
- Codex 不推薦超過單檔 3-5 萬或破壞 30% 現金保留的標的。

