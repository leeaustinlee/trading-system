# Claude 排程研究 — 09:20 開盤候選確認

**執行時間**：每週一至週五 09:20（Asia/Taipei）
**對應 Codex 通知**：09:30 `AustinStockStrategy0930`

---

## 第一步：必讀

1. `D:/ai/stock/AI_RULES_INDEX.md`
2. `D:/ai/stock/market-snapshot.json` — 確認 `generated_at` 不超過 **10 分鐘**（盤中嚴格）
3. `D:/ai/stock/codex-research-latest.md` — Codex 09:10 DataPrep 交接（開盤盤型 + 候選）
4. `D:/ai/stock/capital-summary.md`
5. `D:/ai/stock/claude-research-request.json`（若存在）
6. `D:/ai/stock/market-gate-self-optimization-engine.md`
7. `D:/ai/stock/trade-decision-engine.md`
8. `D:/ai/stock/claude-live-price-check-prompt.md`

---

## 第二步：即時報價複核（最重要）

依 `claude-live-price-check-prompt.md` 規則：

1. 讀 `market-snapshot.json` 的 `candidate_live_quotes`
2. 若 `generated_at` 超過 10 分鐘 或缺少候選股，用 Chrome JS fetch 查 TWSE MIS API：
   ```javascript
   const url = `https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_t00.tw|tse_2330.tw|...&json=1&delay=0`;
   ```
3. 每檔必須列出：
   - 現價 / 最佳買賣價
   - 開盤價、昨收
   - 日高 / 日低
   - 現價相對開盤 %、現價相對昨收 %
   - 是否接近日高（日高 - 現價 < 1%）
   - 10 分鐘方向：`續強 / 轉弱 / 震盪 / 資料不足`

---

## 第三步：行情 Gate 判斷

- 台積電是否站上開盤與均價？（YES/NO）
- 大盤是否創盤中新高？（YES/NO）
- 主流族群是否一致上漲？（YES/NO）
- 行情等級：A / B / C
- 行情階段：開盤洗盤期 / 主升發動期

若行情等級 = C → 輸出「今日建議休息」，不再繼續選股

---

## 第四步：候選股 09:20 確認研究

針對 Codex 09:10 交接的候選股，每檔輸出：

```text
股票：代號 / 名稱
即時報價：現價 / 開盤 / 昨收 / 日高 / 日低
相對開盤：+X.XX%　相對昨收：+X.XX%
接近日高：是 / 否
10 分鐘方向：續強 / 轉弱 / 震盪
技術確認：是否站上開盤均價、量是否持續放大
籌碼確認：今日法人方向（若 snapshot 有）
09:30 建議：可進入 Codex 最終決策 / 觀望 / 排除
排除原因：（若排除）
進場條件：（若可進場）需符合什麼才進場
停損：
目標：第一 / 第二
```

---

## 第五步：寫出研究結果

**主檔**：`D:/ai/stock/claude-research-latest.md`
**可選備份**：`D:/ai/stock/claude-research-YYYYMMDD-0920.md`

標頭：`# 開盤研究 YYYY-MM-DD 09:20`
最後：`來源：Claude`

---

## 第六步：匯入 DB（必做）

```bash
curl -s -X POST "http://localhost:8080/api/ai/research/import-file?filePath=/mnt/d/ai/stock/claude-research-latest.md&researchType=OPENING&tradingDate=$(date +%Y-%m-%d)"
```

- 回應 `success:true` → ✅ 已入 DB
- 回應 `success:false` 或 HTTP 錯誤 → 在輸出結尾印出：
  ```
  ❌ DB 匯入失敗：<原因>
  👉 請 Austin 手動匯入 claude-research-latest.md
  ```

---

## 禁止事項

- 不發 LINE
- snapshot 超過 10 分鐘不得給進場建議（僅可標示方向）
- 跌破開盤、跌破昨收、爆量開高走低的標的，直接排除
- 不直接給張數
- **不要跳過第六步 DB 匯入**
