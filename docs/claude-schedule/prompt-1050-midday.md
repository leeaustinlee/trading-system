# Claude 排程研究 — 10:50 盤中持倉研究

**執行時間**：每週一至週五 10:50（Asia/Taipei）
**對應 Codex 通知**：11:00 `AustinStockMidday1100`

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

## 第七步：寫出研究結果到 File Bridge（v2.1 協定）

Java 於盤中階段已建立 MIDDAY 任務並寫出 `claude-research-request.json`。你只需要寫一個檔案到 `claude-submit/`，Java `ClaudeSubmitWatcher` 會在 30 秒內自動 submit 並把 task 狀態推進到 `CLAUDE_DONE` — **不需要、也不可以呼叫 `localhost:8080`**。

### 7.1 讀 request 取得 taskId 與建議檔名

讀 `D:/ai/stock/claude-research-request.json`（路徑依第 0 章翻譯），取出：

| 欄位 | 意義 |
|---|---|
| `taskId` | 本輪 ai_task 的 ID。**不得自行創造** |
| `taskType` | 必須 = `"MIDDAY"` |
| `tradingDate` | 例如 `"2026-04-23"` |
| `submit_filename_hint` | 例如 `claude-MIDDAY-2026-04-23-1105-task-120.json`，**直接沿用** |
| `allowed_symbols` | `scores` / `thesis` 的 key 必須是子集 |

若 `taskType` 不是 `MIDDAY` 或 `taskId` 缺失，不要寫 claude-submit，只在 `claude-research-latest.md` 標註「未取得有效 MIDDAY taskId，本輪不回報」後結束。

### 7.2 組成 JSON 內容

```json
{
  "taskId": 120,
  "taskType": "MIDDAY",
  "tradingDate": "2026-04-23",
  "contentMarkdown": "完整盤中研究 md（與 claude-research-latest.md 同內容）",
  "scores": {"2303": 8.0},
  "thesis": {"2303": "持倉續抱，動能尚在"},
  "riskFlags": []
}
```

規則：

- `scores` key 必須 ∈ `allowed_symbols`
- **10:50 MIDDAY 特殊規則**：若 `decision_lock = LOCKED` 或行情等級 = C，scores 一律保守（≤ 6）或留空
- 11:00 後無持倉且無新轉強，`thesis` 可註明「建議 monitor_mode = OFF」
- `riskFlags` 沒有就空陣列 `[]`

### 7.3 原子寫檔（tmp → rename）

**絕對不可省略 `.tmp` 階段**。

路徑：`D:/ai/stock/claude-submit/<submit_filename_hint>`（依第 0 章翻譯）

1. 先寫 `.../<submit_filename_hint>.tmp`
2. 完整寫入後 rename 去掉 `.tmp`

範例（本機）：
```bash
mv /mnt/d/ai/stock/claude-submit/claude-MIDDAY-*-task-120.json.tmp \
   /mnt/d/ai/stock/claude-submit/claude-MIDDAY-*-task-120.json
```

### 7.4 驗證（選用）

- `claude-submit/` 下應有剛寫的 `.json`（不應有 `.tmp` 殘留）
- 30-60 秒後 `claude-submit/processed/` 出現 `<hint>.processed.json`
- 若進 `failed/` 開檔看原因

---

## 禁止事項

- 不發 LINE
- 不執行盤中監控（監控由 Codex 負責）
- 不直接給張數
- **不要呼叫 `localhost:8080`** — 走 file bridge（7.3）
- **不要跳過 `.tmp` 階段** — watcher 可能讀到半成品
- **不要自行創造 taskId** — 必須用 `claude-research-request.json` 的值
