# Claude 排程研究 — 10:50 盤中持倉研究

**執行時間**：每週一至週五 10:50（Asia/Taipei）
**對應 Codex 通知**：11:00 `AustinStockMidday1100`

---

## 第一步：必讀

1. `D:/ai/stock/AI_RULES_INDEX.md`
2. `D:/ai/stock/market-snapshot.json` — `generated_at` 必須在 10 分鐘內
3. `D:/ai/stock/codex-research-latest.md` — Codex 10:40 DataPrep 交接
4. `D:/ai/stock/capital-summary.md` — 確認目前持倉與未實現損益
5. `D:/ai/stock/claude-research-request.json`（若存在）
6. `D:/ai/stock/market-gate-self-optimization-engine.md`
7. `D:/ai/stock/five-minute-monitor-decision-engine.md`

---

## 第二步：盤中行情判斷

- 行情等級：A / B / C（依目前指數、台積電、主流族群）
- 行情階段：開盤洗盤期 / 主升發動期 / 高檔震盪期 / 出貨鈍化期
- 台積電狀態：強 / 轉弱 / 跌破均價
- 決策鎖：LOCKED / RELEASED（10:30 後無持倉且市場非 A → 強制 LOCKED）

---

## 第三步：持倉追蹤（若有持倉）

從 `capital-summary.md` 讀取持倉，每檔輸出：

```text
股票：代號 / 名稱
成本 / 現價 / 未實現損益
原始停損：是否有觸及風險
移動停損：目前應設在哪
第一目標：是否已觸及 → 是否已減碼
第二目標：距離多遠
續抱條件：目前是否符合
盤中建議：續抱 / 注意停損 / 考慮減碼
```

---

## 第四步：候選股盤中確認（若無持倉或有新候選）

若 `codex-research-latest.md` 有新候選且 `decision_lock = RELEASED`：

```text
股票：代號 / 名稱
現價相對開盤 / 昨收
是否洗盤轉強（5分K由綠翻紅 + 量回升）
是否仍在日高附近
盤中建議：可進場觀察 / 等下午確認 / 排除
```

若行情等級 = C 或 `decision_lock = LOCKED` → 不推薦任何新進場

---

## 第五步：監控模式建議

依 `five-minute-monitor-decision-engine.md`：
- `monitor_mode`：OFF / WATCH / ACTIVE
- 11:00 後無持倉且無明確轉強 → 優先建議 `monitor_mode = OFF`

---

## 第六步：寫出研究結果

**主檔**：`D:/ai/stock/claude-research-latest.md`
**可選備份**：`D:/ai/stock/claude-research-YYYYMMDD-1050.md`

標頭：`# 盤中研究 YYYY-MM-DD 10:50`
最後：`來源：Claude`

---

## 第七步：認領任務 + 回報結果（PR-2 新流程）

### 7.1 認領任務
```bash
curl -s "http://localhost:8080/api/ai/tasks/pending?type=MIDDAY" | jq '.[0]'
```
記下 `id`。若無 PENDING 可用舊 API fallback。

### 7.2 回報結果
```bash
curl -X POST "http://localhost:8080/api/ai/tasks/$TASK_ID/claude-result" \
  -H "Content-Type: application/json" \
  -d '{
    "contentMarkdown": "完整盤中研究 md",
    "scores": {"2303": 8.0},
    "thesis": {"2303": "持倉續抱，動能尚在"},
    "riskFlags": []
  }'
```

### 7.3 驗證
```bash
curl -s "http://localhost:8080/api/ai/tasks/$TASK_ID" | jq '.status'   # CLAUDE_DONE
```

### Fallback（僅無 PENDING 任務時）
```bash
curl -s -X POST "http://localhost:8080/api/ai/research/import-file?filePath=/mnt/d/ai/stock/claude-research-latest.md&researchType=MIDDAY&tradingDate=$(date +%Y-%m-%d)"
```

失敗處理：
```
❌ 任務回報失敗：<原因>
👉 請 Austin 手動匯入 claude-research-latest.md
```

---

## 禁止事項

- 不發 LINE
- 不執行盤中監控（監控由 Codex 負責）
- 不直接給張數
- **不要跳過第七步回報**
