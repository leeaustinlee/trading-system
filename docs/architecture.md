# Architecture

## 分層原則
- `client`：只打外部 API，不做交易判斷
- `engine`：只做規則與決策
- `service`：組裝流程，串接 engine/repository/notify
- `scheduler`：只做時間觸發
- `notify`：只做訊息組裝與送出
- `ai`：只做 prompt 與 adapter，不介入交易規則

## 主流程
1. scheduler 觸發作業
2. service 呼叫 client 抓資料
3. service 呼叫 engine 做決策
4. 結果寫入 repository
5. notify 依事件輸出 LINE
6. controller/API 提供 UI 查詢
## v2.1 Workflow Correctness 架構原則

最新 AI orchestration 規格見 `docs/workflow-correctness-ai-orchestration-spec.md`。

核心架構從「固定時間硬跑」升級為「時間 + 狀態雙重驅動」：

```text
DataPrep 建 ai_task(PENDING)
  -> Claude file bridge submit
  -> ai_task(CLAUDE_DONE)
  -> Codex API submit
  -> ai_task(CODEX_DONE)
  -> Java Decision / Notify
  -> ai_task(FINALIZED)
```

架構硬限制：

- `ai_task` 狀態只能單向前進，不得倒退。
- Claude file bridge 只讀完整 `.json`，Claude 必須先寫 `.tmp` 再 rename。
- POSTMARKET / T86_TOMORROW 必須在 DataPrep 階段建立 task，通知 job 不可晚建 task。
- FinalDecision 預設要求 Claude + Codex 都完成；未完成只能輸出明確 fallback。
- 所有 fallback 必須能由 DB、dashboard、orchestration log、LINE 文案追溯。
