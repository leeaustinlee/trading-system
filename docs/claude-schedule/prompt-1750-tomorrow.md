# Claude 排程研究 — 17:50 T86 後明日策略研究

**執行時間**：每週一至週五 17:50（Asia/Taipei）
**對應 Codex 通知**：18:00 `AustinStockTomorrow1800`

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

## 第七步：寫出研究結果到 File Bridge（v2.1 協定）

Java `T86DataPrepJob`（18:10）會建立 T86_TOMORROW 任務 — **但 17:50 的 Claude 排程可能早於 Java 建 task**，請務必依 request.json 的 taskType 判斷。一般流程：

- 若 17:40 Codex DataPrep + 17:50 Claude 研究流程跟 Java 對齊，`claude-research-request.json` 的 `taskType` 已經是 `T86_TOMORROW`
- 若 17:50 時 Java 還沒建 task（例如時序錯位），request.json 可能仍是 POSTMARKET 或其他，此時放棄寫 claude-submit，只寫 `claude-research-latest.md` 給 Codex 18:00 讀

**不需要、也不可以呼叫 `localhost:8080`**。

### 7.1 讀 request 取得 taskId 與建議檔名

讀 `D:/ai/stock/claude-research-request.json`（路徑依第 0 章翻譯），取出：

| 欄位 | 意義 |
|---|---|
| `taskId` | 本輪 ai_task 的 ID。**不得自行創造** |
| `taskType` | 必須 = `"T86_TOMORROW"` |
| `tradingDate` | 例如 `"2026-04-23"` |
| `submit_filename_hint` | 例如 `claude-T86_TOMORROW-2026-04-23-1750-task-121.json`，**直接沿用** |
| `allowed_symbols` | `scores` / `thesis` 的 key 必須是子集 |

若 `taskType` 不是 `T86_TOMORROW` 或 `taskId` 缺失，不要寫 claude-submit，在 `claude-research-latest.md` 標註「17:50 時 Java T86_TOMORROW task 尚未建立，本輪僅寫 research md 供 Codex 18:00 讀取」後結束。

### 7.2 組成 JSON 內容

```json
{
  "taskId": 121,
  "taskType": "T86_TOMORROW",
  "tradingDate": "2026-04-23",
  "contentMarkdown": "完整 T86 + 明日策略研究 md（與 claude-research-latest.md 同內容）",
  "scores": {"2303": 8.5, "3231": 7.8, "4938": 7.2},
  "thesis": {"2303": "T86 年度級大買，但法說風險", "3231": "..."},
  "riskFlags": ["台積電 T86 大賣，大盤風險偏高"]
}
```

規則：

- `scores` key 必須 ∈ `allowed_symbols`
- **17:50 T86_TOMORROW 特殊規則**：若 `t86.date` 不是今日（代表 TWSE T86 尚未更新），scores 給低信心（≤ 6）並在 `riskFlags` 標明「T86 尚非今日資料」；不要因 T86 方向直接提高排序
- `riskFlags` 沒有就空陣列 `[]`

### 7.3 原子寫檔（tmp → rename）

**絕對不可省略 `.tmp` 階段**。

路徑：`D:/ai/stock/claude-submit/<submit_filename_hint>`（依第 0 章翻譯）

1. 先寫 `.../<submit_filename_hint>.tmp`
2. 完整寫入後 rename 去掉 `.tmp`

範例（本機）：
```bash
mv /mnt/d/ai/stock/claude-submit/claude-T86_TOMORROW-*-task-121.json.tmp \
   /mnt/d/ai/stock/claude-submit/claude-T86_TOMORROW-*-task-121.json
```

### 7.4 驗證（選用）

- `claude-submit/` 下應有剛寫的 `.json`（不應有 `.tmp` 殘留）
- 30-60 秒後 `claude-submit/processed/` 出現 `<hint>.processed.json`
- 若進 `failed/` 開檔看原因

---

## 禁止事項

- 不發 LINE
- T86 date 不是今日時，不得提高候選股排序（只能標示，等明日確認）
- 不直接給 Austin 買賣張數
- **不要呼叫 `localhost:8080`** — 走 file bridge（7.3）
- **不要跳過 `.tmp` 階段** — watcher 可能讀到半成品
- **不要自行創造 taskId** — 必須用 `claude-research-request.json` 的值
