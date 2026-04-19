# Claude 排程研究 — 17:50 T86 後明日策略研究

**執行時間**：每週一至週五 17:50（Asia/Taipei）
**對應 Codex 通知**：18:00 `AustinStockTomorrow1800`

---

## 第一步：必讀

1. `D:/ai/stock/AI_RULES_INDEX.md`
2. `D:/ai/stock/dual-ai-workflow.md`
3. `D:/ai/stock/market-snapshot.json` — 確認 `t86.date` 是否為今日
4. `D:/ai/stock/codex-research-latest.md` — Codex 17:40 DataPrep 交接（含 T86 個股籌碼）
5. `D:/ai/stock/capital-summary.md`
6. `D:/ai/stock/trade-decision-engine.md`
7. `D:/ai/stock/market-gate-self-optimization-engine.md`

---

## 第二步：T86 籌碼確認

檢查 `codex-research-latest.md` 中的 T86 資料：
- `t86.date` 是否為今日？若否，標示「T86 尚非今日資料，候選股不因此提高優先權」
- 外資 / 投信 / 自營商今日買賣超金額
- 連續買超天數（若有）

---

## 第三步：候選股 T86 籌碼更新研究

針對 15:30 盤後候選（超強勢 5 + 中短線候選 5），更新 T86 確認後的評估：

```text
股票：代號 / 名稱
T86 外資：買超 X 億 / 賣超 X 億（日期：YYYY-MM-DD）
T86 投信：買超 X 張 / 賣超 X 張
T86 自營商：買超 / 賣超
籌碼方向：明確偏多 / 中性 / 偏空 / 資料不足
與 15:20 研究相比變化：籌碼更強 / 沒變化 / 籌碼轉弱
更新後排序：（從高到低，依信心分數 + 籌碼 + 技術）
明日策略更新：維持 / 上調優先 / 下調優先 / 排除
```

---

## 第四步：明日整體策略

- 隔夜外部風險：美股 / SOX 收盤、TSM ADR 表現（若 snapshot 有更新）
- 明日大盤預判：偏多 / 震盪 / 偏空（理由）
- 明日重點標的（最多 3 檔，依今日收盤 + T86 + 技術確認）
- 超強勢 5 檔隔日追強條件更新（若 T86 有新資訊）
- 中短線候選最終排序

---

## 第五步：資金配置提醒

從 `capital-summary.md` 確認：
- 明日最多可操作金額
- 若已有持倉：持倉風險、是否需要調整停損
- 若空手：明日最優先觀察標的

---

## 第六步：寫出研究結果

**主檔**：`D:/ai/stock/claude-research-latest.md`
**可選備份**：`D:/ai/stock/claude-research-YYYYMMDD-1750.md`

標頭：`# 明日策略研究 YYYY-MM-DD 17:50`
最後：`來源：Claude`

---

## 第七步：匯入 DB（必做）

```bash
curl -s -X POST "http://localhost:8080/api/ai/research/import-file?filePath=/mnt/d/ai/stock/claude-research-latest.md&researchType=T86_TOMORROW&tradingDate=$(date +%Y-%m-%d)"
```

- 回應 `success:true` → ✅ 已入 DB
- 回應 `success:false` 或 HTTP 錯誤 → 輸出結尾印出：
  ```
  ❌ DB 匯入失敗：<原因>
  👉 請 Austin 手動匯入 claude-research-latest.md
  ```

---

## 禁止事項

- 不發 LINE
- T86 date 不是今日時，不得提高候選股排序（只能標示，等明日確認）
- 不直接給 Austin 買賣張數
- **不要跳過第七步 DB 匯入**
