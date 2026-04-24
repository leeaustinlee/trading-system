# Claude 排程研究 — 08:20 盤前候選研究

**執行時間**：每週一至週五 08:20（Asia/Taipei）
**對應 Codex 通知**：08:30 `AustinStockPremarket0830`

---

## 環境與路徑解析（v2.1 新增）

本 prompt 會同時被兩種環境執行，請先判斷當下環境並自動翻譯路徑：

| 執行環境 | stock 根目錄路徑 |
|---|---|
| 本機 Windows PowerShell / WSL Claude CLI | `D:/ai/stock/` 或 `/mnt/d/ai/stock/` |
| Cowork cloud sandbox Claude | `/sessions/happy-gracious-pasteur/mnt/stock/` |

底下章節出現的 `D:/ai/stock/...` 一律請翻譯成你當下環境能讀寫的對應路徑，兩邊指向同一份檔案。若其中一條路徑 Read 失敗，改用另一條。

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

## 第六步：寫出研究結果到 File Bridge（v2.1 協定）

Java `PremarketDataPrepJob`（08:10）已建立 PREMARKET 任務並寫出 `claude-research-request.json`。你只需要寫一個檔案到 `claude-submit/`，Java `ClaudeSubmitWatcher` 會在 30 秒內自動 submit 並把 task 狀態推進到 `CLAUDE_DONE` — **不需要、也不可以呼叫 `localhost:8080`**（Cowork 雲端環境打不到；本機走 file bridge 更穩）。

### 6.1 讀 request 取得 taskId 與建議檔名

讀 `D:/ai/stock/claude-research-request.json`（路徑請依第 0 章翻譯），取出以下欄位：

| 欄位 | 意義 |
|---|---|
| `taskId` | 本輪 ai_task 的 ID。**不得自行創造** |
| `taskType` | 必須 = `"PREMARKET"`（若不是，代表目前不是 PREMARKET 時段） |
| `tradingDate` | 例如 `"2026-04-23"` |
| `submit_filename_hint` | Java 建議檔名（例如 `claude-PREMARKET-2026-04-23-0847-task-118.json`），**直接沿用** |
| `allowed_symbols` | `scores` / `thesis` 的 key 必須是這個清單的子集 |

若 `taskType` 不是 `PREMARKET` 或 `taskId` 缺失，不要寫 claude-submit，直接在 `claude-research-latest.md` 標註「未取得有效 PREMARKET taskId，本輪不回報」後結束。

### 6.2 組成 JSON 內容

```json
{
  "taskId": 118,
  "taskType": "PREMARKET",
  "tradingDate": "2026-04-23",
  "contentMarkdown": "完整盤前研究 md（與 claude-research-latest.md 同內容）",
  "scores": {"2303": 8.5, "3231": 7.8, "4938": 7.2},
  "thesis": {"2303": "T86 年度級大買，但法說風險", "3231": "..."},
  "riskFlags": ["台積電 T86 大賣，大盤風險偏高"]
}
```

規則：

- `scores` key 必須 ∈ `allowed_symbols`，不在清單的 symbol 一律丟棄
- `thesis` key 應與 `scores` 對齊
- `riskFlags` 沒有風險就給空陣列 `[]`
- `contentMarkdown` 必須有實質研究內容，系統會拒收空殼

### 6.3 原子寫檔（tmp → rename）

**絕對不可省略 `.tmp` 階段**，否則 watcher 可能讀到半成品被丟進 `failed/`。

路徑：`D:/ai/stock/claude-submit/<submit_filename_hint>`（依第 0 章翻譯）

1. 先寫 `.../<submit_filename_hint>.tmp`
2. 完整寫入後 rename 去掉 `.tmp`

範例（本機）：
```bash
mv /mnt/d/ai/stock/claude-submit/claude-PREMARKET-*-task-118.json.tmp \
   /mnt/d/ai/stock/claude-submit/claude-PREMARKET-*-task-118.json
```

Cowork 同理，把路徑前綴換成 `/sessions/happy-gracious-pasteur/mnt/stock/`。

### 6.4 驗證（選用）

- `claude-submit/` 下應有剛寫的 `.json`（不應有 `.tmp` 殘留）
- 30-60 秒後 `claude-submit/processed/` 會出現 `<hint>.processed.json`，代表 Java 已 submit 成功
- 若 `claude-submit/failed/` 出現 `<hint>.failed.json`，開檔看原因（通常是 schema 問題）

Scheduled 環境下寫完就可結束。

---

## 禁止事項

- 不發 LINE
- 不寫 `claude-outbox.json`
- 不直接給 Austin 買賣張數（張數由 Codex 決定）
- 不用超過 30 分鐘的報價給進場建議
- **不要呼叫 `localhost:8080`** — 走 file bridge（6.3）
- **不要跳過 `.tmp` 階段** — watcher 可能讀到半成品
- **不要自行創造 taskId** — 必須用 `claude-research-request.json` 的值
