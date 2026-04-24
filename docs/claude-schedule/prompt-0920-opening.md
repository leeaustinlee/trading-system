# Claude 排程研究 — 09:20 開盤候選確認

**執行時間**：每週一至週五 09:20（Asia/Taipei）
**對應 Codex 通知**：09:30 `AustinStockStrategy0930`

---

## 環境與路徑解析（v2.1 新增）

本 prompt 會同時被兩種環境執行，請先判斷當下環境並自動翻譯路徑：

| 執行環境 | stock 根目錄路徑 |
|---|---|
| 本機 Windows PowerShell / WSL Claude CLI | `D:/ai/stock/` 或 `/mnt/d/ai/stock/` |
| Cowork cloud sandbox Claude | `/sessions/happy-gracious-pasteur/mnt/stock/` |

底下章節出現的 `D:/ai/stock/...` 一律請翻譯成你當下環境能讀寫的對應路徑，兩邊指向同一份檔案。若其中一條路徑 Read 失敗，改用另一條。

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

## 第六步：寫出研究結果到 File Bridge（v2.1 協定）

Java `OpenDataPrepJob`（09:01）已建立 OPENING 任務並寫出 `claude-research-request.json`。你只需要寫一個檔案到 `claude-submit/`，Java `ClaudeSubmitWatcher` 會在 30 秒內自動 submit 並把 task 狀態推進到 `CLAUDE_DONE` — **不需要、也不可以呼叫 `localhost:8080`**。

### 6.1 讀 request 取得 taskId 與建議檔名

讀 `D:/ai/stock/claude-research-request.json`（路徑依第 0 章翻譯），取出：

| 欄位 | 意義 |
|---|---|
| `taskId` | 本輪 ai_task 的 ID。**不得自行創造** |
| `taskType` | 必須 = `"OPENING"` |
| `tradingDate` | 例如 `"2026-04-23"` |
| `submit_filename_hint` | 例如 `claude-OPENING-2026-04-23-0955-task-119.json`，**直接沿用** |
| `allowed_symbols` | `scores` / `thesis` 的 key 必須是子集 |

若 `taskType` 不是 `OPENING` 或 `taskId` 缺失，不要寫 claude-submit，只在 `claude-research-latest.md` 標註「未取得有效 OPENING taskId，本輪不回報」後結束。

### 6.2 組成 JSON 內容

```json
{
  "taskId": 119,
  "taskType": "OPENING",
  "tradingDate": "2026-04-23",
  "contentMarkdown": "完整開盤研究 md（與 claude-research-latest.md 同內容）",
  "scores": {"2303": 8.5, "3231": 7.8},
  "thesis": {"2303": "開盤洗盤轉強", "3231": "..."},
  "riskFlags": ["若跌破均價立即出場"]
}
```

規則：

- `scores` key 必須 ∈ `allowed_symbols`
- `thesis` key 應與 `scores` 對齊
- **09:20 的特殊規則**：跌破開盤、跌破昨收、爆量開高走低的 symbol 一律給低分（≤ 5）或直接不放進 scores
- `riskFlags` 沒有風險就給空陣列 `[]`

### 6.3 原子寫檔（tmp → rename）

**絕對不可省略 `.tmp` 階段**。

路徑：`D:/ai/stock/claude-submit/<submit_filename_hint>`（依第 0 章翻譯）

1. 先寫 `.../<submit_filename_hint>.tmp`
2. 完整寫入後 rename 去掉 `.tmp`

範例（本機）：
```bash
mv /mnt/d/ai/stock/claude-submit/claude-OPENING-*-task-119.json.tmp \
   /mnt/d/ai/stock/claude-submit/claude-OPENING-*-task-119.json
```

### 6.4 驗證（選用）

- `claude-submit/` 下應有剛寫的 `.json`（不應有 `.tmp` 殘留）
- 30-60 秒後 `claude-submit/processed/` 出現 `<hint>.processed.json`
- 若進 `failed/` 開檔看原因

---

## 禁止事項

- 不發 LINE
- snapshot 超過 10 分鐘不得給進場建議（僅可標示方向）
- 跌破開盤、跌破昨收、爆量開高走低的標的，直接排除
- 不直接給張數
- **不要呼叫 `localhost:8080`** — 走 file bridge（6.3）
- **不要跳過 `.tmp` 階段** — watcher 可能讀到半成品
- **不要自行創造 taskId** — 必須用 `claude-research-request.json` 的值
