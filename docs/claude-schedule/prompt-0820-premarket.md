# Claude 排程研究 — 08:20 盤前候選研究

**執行時間**：每週一至週五 08:20（Asia/Taipei）
**對應 Codex 通知**：08:30 `AustinStockPremarket0830`

---

## 第一步：必讀（依序讀完再開始研究）

1. `D:/ai/stock/AI_RULES_INDEX.md`
2. `D:/ai/stock/dual-ai-workflow.md`
3. `D:/ai/stock/market-data-protocol.md`
4. `D:/ai/stock/market-snapshot.json` — 確認 `generated_at` 不超過 30 分鐘
5. `D:/ai/stock/codex-research-latest.md` — Codex 08:10 DataPrep 交接
6. `D:/ai/stock/capital-summary.md` — 可操作資金與持倉
7. `D:/ai/stock/claude-research-request.json` — Java DataPrep 補充 context（若存在）
8. `D:/ai/stock/market-gate-self-optimization-engine.md`
9. `D:/ai/stock/trade-decision-engine.md`

---

## 第二步：資料確認

- [ ] `market-snapshot.json` 的 `generated_at` 在 30 分鐘內？若否，標示「資料過期」
- [ ] `codex-research-latest.md` 是否有今日候選股清單？若無，標示「Codex 交接未更新」
- [ ] T86 日期是否為昨日或今日？若為前日，標示「籌碼尚未更新」
- [ ] `capital-summary.md` 可操作現金是否足夠（至少 3 萬）？

---

## 第三步：盤前候選研究任務

針對 `codex-research-latest.md` 中的候選股（超強勢 5 檔 + 中短線候選 5 檔），每檔輸出：

```text
股票：代號 / 名稱
題材定位：主題、族群、是否為主流題材、題材延續性
基本面：近期營收趨勢、法說/財報時程、重大事件
籌碼面：外資/投信/自營商方向、主力籌碼、融資券（資料不足需標示）
技術面：均線結構、量價關係、支撐壓力、是否已追高
風險：高檔爆量訊號、近期法說/財報風險、題材退燒風險
盤前建議：可追強 / 等開盤確認 / 觀望 / 排除
開盤追強條件：（如適用）需符合哪些條件才可 09:00 追
建議進場區間：
停損參考：
第一目標 / 第二目標：
信心分數：0-10
```

---

## 第四步：行情 Gate 判斷

依 `market-gate-self-optimization-engine.md` 判斷：
- 行情等級（A/B/C）
- 行情階段（開盤洗盤期 / 主升發動期 / 高檔震盪期 / 出貨鈍化期）
- 若行情等級 = C，直接輸出「今日建議休息」，不再推薦任何標的

---

## 第五步：寫出研究結果

**主檔（必寫）**：
```
D:/ai/stock/claude-research-latest.md
```

**可選備份**：
```
D:/ai/stock/claude-research-YYYYMMDD-0820.md
```

**格式要求**：
- 標頭：`# 盤前研究 YYYY-MM-DD 08:20`
- 列出每檔研究結論
- 最後一行：`來源：Claude`

---

## 第六步：認領任務 + 回報結果（PR-2 新流程，取代原本的「匯入 DB」）

### 6.1 認領任務
先查詢 PENDING 的 PREMARKET 任務（Java 於 08:10 已建立）：

```bash
curl -s "http://localhost:8080/api/ai/tasks/pending?type=PREMARKET" | jq '.[0]'
```

記下回傳中的 `id`（下稱 `TASK_ID`）。讀取 `target_candidates_json` 作為研究對象。

### 6.2 做研究（讀 target_candidates_json + 各來源）

### 6.3 寫研究到 claude-research-latest.md（備份用，Codex fallback）

### 6.4 回報結果（自動寫 stock_evaluation.claude_score + 觸發 consensus 重算）

```bash
curl -X POST "http://localhost:8080/api/ai/tasks/$TASK_ID/claude-result" \
  -H "Content-Type: application/json" \
  -d '{
    "contentMarkdown": "完整盤前研究 md 內容",
    "scores": {"2303": 8.5, "3231": 7.8, "4938": 7.2},
    "thesis": {"2303": "T86 年度級大買，但 4/22 法說風險"},
    "riskFlags": ["台積電 T86 大賣，大盤風險偏高"]
  }'
```

成功回應：`{"success":true, "id":N, "status":"CLAUDE_DONE", "autoScored":{...}}`。

### 6.5 驗證
```bash
curl -s "http://localhost:8080/api/ai/tasks/$TASK_ID" | jq '.status'   # 應為 "CLAUDE_DONE"
curl -s "http://localhost:8080/api/candidates/current" | jq '.[] | {symbol, claudeScore}'
```

### 6.6 失敗處理
若 `claude-result` 失敗或 PENDING 任務不存在，在最後輸出印出：
```
❌ 任務回報失敗：<原因>
👉 請 Austin 手動用 UI 匯入 claude-research-latest.md 作為備援，或 curl 重試
```

> **舊 API `POST /api/ai/research/import-file` 仍保留**，但只寫 `ai_research_log`，
> 不會寫 `stock_evaluation.claude_score`，FinalDecisionService 拿不到 Claude 分數。
> 優先用新流程。

---

## 禁止事項

- 不發 LINE
- 不寫 `claude-outbox.json`
- 不直接給 Austin 買賣張數（張數由 Codex 決定）
- 不用超過 30 分鐘的報價給進場建議
- **不要跳過第六步 — 這是 FinalDecisionService 拿到 Claude 分數的唯一管道**
