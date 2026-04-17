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

## 禁止事項

- 不發 LINE
- 不寫 `claude-outbox.json`
- 不直接給 Austin 買賣張數（張數由 Codex 決定）
- 不用超過 30 分鐘的報價給進場建議
