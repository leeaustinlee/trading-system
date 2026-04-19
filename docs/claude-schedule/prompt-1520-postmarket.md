# Claude 排程研究 — 15:20 盤後候選 5 檔研究

**執行時間**：每週一至週五 15:20（Asia/Taipei）
**對應 Codex 通知**：15:30 `AustinStockAftermarket1530`

---

## 第一步：必讀

1. `D:/ai/stock/AI_RULES_INDEX.md`
2. `D:/ai/stock/dual-ai-workflow.md`
3. `D:/ai/stock/market-snapshot.json` — 收盤版（`generated_at` 可接受 30 分鐘內）
4. `D:/ai/stock/codex-research-latest.md` — Codex 15:10 DataPrep 交接（含收盤法人 + 候選 10 檔）
5. `D:/ai/stock/capital-summary.md`
6. `D:/ai/stock/claude-research-request.json`（Java PostmarketDataPrepJob 寫出，含漲跌家數）
7. `D:/ai/stock/market-gate-self-optimization-engine.md`
8. `D:/ai/stock/trade-decision-engine.md`

---

## 第二步：今日盤後市場總結

- 大盤今日表現（漲跌幅、漲跌家數、成交量）
- 主流族群：今日漲幅最強的 2-3 組族群
- 台積電收盤狀態
- 行情等級評估（今日 A/B/C）
- 今日行情自我評分（0-10）：是否有錯誤判斷

---

## 第三步：超強勢 5 檔研究

針對 Codex 選出的超強勢 5 檔（漲停或接近漲停收在最高點）：

```text
股票：代號 / 名稱
今日漲幅 / 收盤 / 成交量
是否漲停 / 收在最高點附近（收高比 >= 0.96）
題材：是否主流題材 + 延續性
籌碼：今日外資 / 投信 / 自營商方向（若有）
隔日風險：若明日開盤打開 / 爆量開高走低的應對
08:30 追強條件：（需同時符合）量價延續 + 仍鎖強 + 族群同步
建議：列入超強勢追強池 / 謹慎（溢價太高）/ 排除
```

---

## 第四步：中短線候選 5 檔深度研究

針對 Codex 選出的中短線候選 5 檔（漲幅 2%-8.8%，收高比 >= 0.96，非鎖死）：

```text
股票：代號 / 名稱
今日收盤 / 漲幅 / 成交量
題材定位：主題、族群、是否主流、延續性
基本面：近期營收、法說時程、重大事件
籌碼面：外資/投信/自營商、主力籌碼、融資券（資料不足需標示）
技術面：均線結構、量價、支撐壓力、是否追高
風險：高檔爆量、法說/財報、題材退燒、大盤連動
中短線建議：買進 / 觀望 / 等回測 / 排除
建議進場區間：
停損：
第一目標 / 第二目標：
信心分數：0-10
```

---

## 第五步：資金配置確認

從 `capital-summary.md`：
- 目前可操作現金
- 現有持倉曝險
- 下一步最多可操作金額（單檔 3-5 萬原則）
- 槓桿 ETF 目前佔比是否接近 50% 上限

---

## 第六步：明日優化建議

- 今日判斷哪裡做對 / 做錯
- 明日盤前需特別注意的風險
- 候選股排序建議（信心分數高低 + 風控）

---

## 第七步：寫出研究結果

**主檔**：`D:/ai/stock/claude-research-latest.md`
**可選備份**：`D:/ai/stock/claude-research-YYYYMMDD-1520.md`

標頭：`# 盤後研究 YYYY-MM-DD 15:20`
最後：`來源：Claude`

---

## 第八步：認領任務 + 回報結果（PR-2 新流程）

Java PostmarketWorkflowService 在 15:05 已建立 POSTMARKET 任務。

### 8.1 認領
```bash
curl -s "http://localhost:8080/api/ai/tasks/pending?type=POSTMARKET" | jq '.[0]'
```
記下 `id`。

### 8.2 回報
```bash
curl -X POST "http://localhost:8080/api/ai/tasks/$TASK_ID/claude-result" \
  -H "Content-Type: application/json" \
  -d '{
    "contentMarkdown": "完整盤後研究 md（含超強勢5+中短線5）",
    "scores": {"2303": 8.5, "3231": 7.8, "4938": 7.2, "2330": 7.5, "2317": 7.0},
    "thesis": {"2303": "盤後收盤強", "2330": "大盤領頭"},
    "riskFlags": ["若明日台指期貼水擴大，降評"]
  }'
```

分數會立即寫入 `stock_evaluation.claude_score` 並觸發 consensus 重算，
明日 09:30 FinalDecisionService 就能直接使用。

### 8.3 驗證
```bash
curl -s "http://localhost:8080/api/ai/tasks/$TASK_ID" | jq '.status'   # CLAUDE_DONE
curl -s "http://localhost:8080/api/candidates/current" | jq '.[] | {symbol, claudeScore}'
```

### Fallback（無 PENDING 任務時）
```bash
curl -s -X POST "http://localhost:8080/api/ai/research/import-file?filePath=/mnt/d/ai/stock/claude-research-latest.md&researchType=POSTMARKET&tradingDate=$(date +%Y-%m-%d)"
```

---

## 禁止事項

- 不發 LINE
- 不直接給張數與最終下單
- 候選股不得由固定觀察池產生，必須依今日全市場掃描結果
- **不要跳過第八步回報** — FinalDecision 拿分數靠這步
